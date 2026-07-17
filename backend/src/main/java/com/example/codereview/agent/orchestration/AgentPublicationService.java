package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.patch.PatchApproval;
import com.example.codereview.patch.PatchApprovalDecision;
import com.example.codereview.patch.PatchApprovalRepository;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateRepository;
import com.example.codereview.scm.ReviewPublication;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmPublicationContext;
import com.example.codereview.scm.ScmPublicationResult;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.ScmReviewPublisher;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentPublicationService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.codereview.ai.langchain4j.LangChain4jRolloutPolicy rolloutPolicy;

    private final AgentScmContextRepository scmContexts;
    private final ScmInstallationRepository installations;
    private final AgentPublicationRepository publications;
    private final FindingRepository findings;
    private final FindingDecisionRepository decisions;
    private final PatchCandidateRepository patches;
    private final PatchApprovalRepository approvals;
    private final CryptoService crypto;
    private final List<ScmReviewPublisher> publishers;
    private final ObjectMapper mapper;
    private final String publicUrl;

    public AgentPublicationService(
            AgentScmContextRepository scmContexts,
            ScmInstallationRepository installations,
            AgentPublicationRepository publications,
            FindingRepository findings,
            FindingDecisionRepository decisions,
            PatchCandidateRepository patches,
            PatchApprovalRepository approvals,
            CryptoService crypto,
            List<ScmReviewPublisher> publishers,
            ObjectMapper mapper,
            @Value("${app.agent.public-url:http://localhost:8080}") String publicUrl
    ) {
        this.scmContexts = scmContexts;
        this.installations = installations;
        this.publications = publications;
        this.findings = findings;
        this.decisions = decisions;
        this.patches = patches;
        this.approvals = approvals;
        this.crypto = crypto;
        this.publishers = publishers;
        this.mapper = mapper;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Transactional
    public AgentPublication publish(Long runId, String headSha) {
        if (rolloutPolicy != null && !rolloutPolicy.allowsScmWrites()) {
            throw new AgentStepExecutionException(AgentFailureType.SECURITY_VIOLATION,
                    "SCM publication is disabled by LangChain4j rollout policy");
        }
        AgentScmContext context = scmContexts.findByAgentRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("SCM publication context is missing"));
        context.requireHead(headSha);
        PatchCandidate patch = patches.findByAgentRunIdOrderByIdAsc(runId).stream()
                .max(java.util.Comparator.comparing(PatchCandidate::getId)).orElse(null);
        String patchSuffix = patch == null ? "none" : patch.getPatchHash();
        String key = "scm-publication:" + runId + ":" + headSha + ":" + patchSuffix;
        Optional<AgentPublication> existing = publications.findByIdempotencyKey(key);
        if (existing.isPresent() && existing.get().isPublished()) {
            return existing.get();
        }
        AgentPublication record = existing.orElseGet(() -> publications.save(new AgentPublication(runId, key)));
        boolean patchApproved = patch != null && approvals.findByPatchCandidateIdOrderByDecidedAtDesc(patch.getId()).stream()
                .anyMatch(value -> value.getDecision() == PatchApprovalDecision.APPROVED
                        && patch.getPatchHash().equals(value.getPatchHash())
                        && headSha.equals(value.getHeadSha()));
        ReviewPublication publication = publication(runId, patch, patchApproved);
        ScmInstallation installation = installations.findById(context.getInstallationId())
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("SCM installation is inactive"));
        if (installation.getProvider() != context.getProvider()) {
            throw new IllegalStateException("SCM provider context mismatch");
        }
        ScmReviewPublisher publisher = publishers.stream()
                .filter(value -> value.type() == context.getProvider()).findFirst()
                .orElseThrow(() -> new IllegalStateException("SCM publisher is unavailable"));
        ScmPublicationResult result;
        try {
            result = publisher.publish(
                    new ScmPublicationContext(
                            installation.getApiBaseUrl(), crypto.decrypt(installation.getEncryptedCredential()),
                            context.getRepositoryFullName(), context.getPullRequestNumber(), headSha, patchApproved
                    ),
                    publication
            );
        } catch (SecurityException security) {
            throw new AgentStepExecutionException(AgentFailureType.SECURITY_VIOLATION, security.getMessage());
        } catch (RuntimeException failure) {
            throw new AgentStepExecutionException(
                    retryable(failure) ? AgentFailureType.RETRYABLE_PROVIDER_ERROR
                            : AgentFailureType.PERMANENT_PROVIDER_ERROR,
                    "SCM publication failed"
            );
        }
        record.record(result);
        publications.save(record);
        if (!result.success()) {
            throw new AgentStepExecutionException(
                    retryable(result.responseCodes()) ? AgentFailureType.RETRYABLE_PROVIDER_ERROR
                            : AgentFailureType.PERMANENT_PROVIDER_ERROR,
                    result.message()
            );
        }
        return record;
    }

    private ReviewPublication publication(Long runId, PatchCandidate patch, boolean patchApproved) {
        List<String> blocking = findings.findByAgentRunIdOrderByIdAsc(runId).stream()
                .filter(value -> "verified".equals(value.getStatus()))
                .filter(value -> decisions.findByFindingIdOrderByIdAsc(value.getId()).stream()
                        .reduce((first, second) -> second)
                        .map(decision -> Boolean.TRUE.equals(decision.getBlocking())).orElse(false))
                .map(value -> value.getTitle() + " (" + value.getFilePath() + ":" + value.getLineStart() + ")")
                .toList();
        ReviewPublication.PatchValidationState patchState = patch == null
                ? ReviewPublication.PatchValidationState.NOT_APPLICABLE
                : patch.isApprovable() && patchApproved
                        ? ReviewPublication.PatchValidationState.VALIDATED
                        : patch.isApprovable()
                                ? ReviewPublication.PatchValidationState.PENDING_APPROVAL
                                : "VALIDATION_FAILED".equals(patch.getStatus())
                                        ? ReviewPublication.PatchValidationState.FAILED
                                        : ReviewPublication.PatchValidationState.PENDING;
        ReviewPublication.Conclusion conclusion = blocking.isEmpty()
                ? ReviewPublication.Conclusion.SUCCESS : ReviewPublication.Conclusion.ACTION_REQUIRED;
        return new ReviewPublication(
                conclusion,
                blocking.isEmpty() ? "No verified blocking findings" : "Verified findings require action",
                blocking,
                List.of("Agent Run " + runId),
                publicUrl + "/api/agent-runs/" + runId,
                patchState,
                false
        );
    }

    private boolean retryable(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            String name = current.getClass().getSimpleName();
            if (name.contains("Transient") || name.contains("Timeout")) return true;
            current = current.getCause();
        }
        return false;
    }

    private boolean retryable(List<Integer> codes) {
        return codes.stream().anyMatch(code -> code == 429 || code >= 500);
    }
}
