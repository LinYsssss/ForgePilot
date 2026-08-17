package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.patch.PatchApprovalDecision;
import com.example.codereview.patch.PatchApprovalRepository;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateRepository;
import com.example.codereview.scm.ReviewPublication;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmPublicationContext;
import com.example.codereview.scm.ScmPublicationResult;
import com.example.codereview.scm.ScmReviewPublisher;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentPublicationService {

    private static final Logger log = LoggerFactory.getLogger(AgentPublicationService.class);

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
    private final AgentCoverageService agentCoverage;
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
            AgentCoverageService agentCoverage,
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
        this.agentCoverage = agentCoverage;
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
        ReviewPublication publication = publication(runId, patch, patchApproved,
                // P4b:发布前判定 AC 覆盖(best-effort,失败=无摘要行),摘要并入 notes 内容,
                // 冻结的载荷结构与 Conclusion 映射不动(门禁语义 P5 才扩展)。
                agentCoverage.judgeAndAttach(runId, headSha));
        ScmInstallation installation = installations.findById(context.getInstallationId())
                .filter(value -> Boolean.TRUE.equals(value.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("SCM installation is inactive"));
        if (installation.getProvider() != context.getProvider()) {
            throw new IllegalStateException("SCM provider context mismatch");
        }
        // 无凭证 installation 是合法演示姿态(run17 实证,2026-08-09:演示 installation
        // credentialConfigured=false、apiBaseUrl=null,仓库在 GitHub 并不存在,凭空投递必失败,
        // 反而把合法姿态打成 FAILED,与 run16 的"零知识入库项目"同理)。发布步的职责分层——
        // 渲染与落库必做,远端投递有凭证才做;因此在解密/构造 publisher 上下文之前就分流,
        // 发布记录可审计地写明"渲染完成、远端投递跳过"。生产投递路径(有凭证)零改动。
        String credential = installation.getEncryptedCredential();
        if (credential == null || credential.isBlank()) {
            ScmPublicationResult skipped = new ScmPublicationResult(
                    true, List.of(),
                    "SCM delivery skipped: installation has no credential (local demo posture)"
            );
            record.record(skipped);
            publications.save(record);
            log.warn("Agent run {} 远端投递已跳过:installation {} 未配置凭证(本地演示姿态),渲染与发布记录照常落库",
                    runId, installation.getId());
            return record;
        }
        ScmPublicationResult result = deliverToRemote(context, installation, publication, patchApproved, headSha);
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

    /**
     * 远端投递与失败分类:publisher 按 provider 解析,底层失败映射为
     * SECURITY_VIOLATION / RETRYABLE / PERMANENT 并附定界原因。盲错修复(与 run15 教训同类):
     * 只有一句 "SCM publication failed" 时,连"null baseUrl 还是 401"都分不出来,run17 只能
     * 靠翻库反推根因;截断手法沿用 ModelOutputValidator.limit,retryable 分类逻辑保持不变。
     */
    private ScmPublicationResult deliverToRemote(
            AgentScmContext context,
            ScmInstallation installation,
            ReviewPublication publication,
            boolean patchApproved,
            String headSha
    ) {
        ScmReviewPublisher publisher = publishers.stream()
                .filter(value -> value.type() == context.getProvider()).findFirst()
                .orElseThrow(() -> new IllegalStateException("SCM publisher is unavailable"));
        try {
            return publisher.publish(
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
                    "SCM publication failed: " + describe(failure)
            );
        }
    }

    private ReviewPublication publication(Long runId, PatchCandidate patch, boolean patchApproved,
                                          List<String> coverageLines) {
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
        List<String> notes = new java.util.ArrayList<>();
        notes.add("Agent Run " + runId);
        notes.addAll(coverageLines);
        return new ReviewPublication(
                conclusion,
                blocking.isEmpty() ? "No verified blocking findings" : "Verified findings require action",
                blocking,
                List.copyOf(notes),
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

    /** 底层失败的定界描述:优先原始 message,无 message 时退回异常类型名,不能再回到全盲。 */
    private String describe(RuntimeException failure) {
        String detail = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        return detail.length() <= 2_000 ? detail : detail.substring(0, 2_000);
    }
}
