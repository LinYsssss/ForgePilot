package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentPatchModelService;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class GeneratingPatchStepExecutor implements AgentStepExecutor {

    private final AgentAnalysisContextRepository contexts;
    private final FindingRepository findings;
    private final FindingDecisionRepository decisions;
    private final Optional<AgentModelClient> client;
    private final AgentPatchModelService patchModel;
    private final AgentModelBudgetPolicy modelBudget;
    private final AgentPromptAssembler prompts;
    private final PatchCandidateService patches;
    private final ObjectMapper mapper;

    @Autowired
    public GeneratingPatchStepExecutor(
            AgentAnalysisContextRepository contexts,
            FindingRepository findings,
            FindingDecisionRepository decisions,
            Optional<AgentModelClient> client,
            AgentPatchModelService patchModel,
            AgentModelBudgetPolicy modelBudget,
            AgentPromptAssembler prompts,
            PatchCandidateService patches,
            ObjectMapper mapper
    ) {
        this.contexts = contexts;
        this.findings = findings;
        this.decisions = decisions;
        this.client = client;
        this.patchModel = patchModel;
        this.modelBudget = modelBudget;
        this.prompts = prompts;
        this.patches = patches;
        this.mapper = mapper;
    }

    public GeneratingPatchStepExecutor() {
        this.contexts = null;
        this.findings = null;
        this.decisions = null;
        this.client = Optional.empty();
        this.patchModel = null;
        this.modelBudget = null;
        this.prompts = null;
        this.patches = null;
        this.mapper = null;
    }

    @Override
    public AgentRunStatus state() { return AgentRunStatus.GENERATING_PATCH; }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (contexts == null) {
            return AgentStepResult.checkpoint(state());
        }
        if (client.isEmpty()) {
            return publishWithoutPatch("patch model is not configured");
        }
        var analysis = contexts.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Verified finding context is missing"
                ));
        analysis.requireHead(context.headSha());
        List<Finding> eligible = findings.findByAgentRunIdOrderByIdAsc(context.agentRunId()).stream()
                .filter(finding -> "verified".equals(finding.getStatus()))
                .filter(this::isBlocking)
                .toList();
        if (eligible.isEmpty()) {
            return publishWithoutPatch("no verified blocking findings are patch eligible");
        }
        try {
            var prompt = prompts.assemble(new AgentPromptAssembler.Input(
                    "review-v1",
                    "Generate one minimal unified diff only for the supplied verified findings. "
                            + "Never alter CI, CODEOWNERS, Flyway history, secrets, or unrelated files.",
                    analysis.getChangedDiff(),
                    mapper.writeValueAsString(eligible.stream().map(this::findingSummary).toList()),
                    "",
                    List.of(),
                    "{\"unifiedDiff\":\"unified diff string\"}",
                    "patch-candidate-v1",
                    budget(), budget(), budget(), budget()
            ));
            AgentPatchModelService.Result generated = patchModel.generate(
                    context.agentRunId(), client.orElseThrow(), prompt
            );
            modelBudget.requireWithinBudget(List.of(generated.asGeneration()));
            PatchCandidate candidate = patches.create(new PatchCandidateService.CreateCommand(
                    context.agentRunId(), context.headSha(), context.headSha(),
                    eligible.stream().map(Finding::getId).toList(),
                    client.orElseThrow().model(), prompt.promptVersion(), generated.response().unifiedDiff()
            ));
            if (!"SCOPE_VALID".equals(candidate.getStatus())) {
                return publishWithoutPatch("generated patch failed scope policy");
            }
            return new AgentStepResult(
                    "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                    AgentRunStatus.VALIDATING_PATCH,
                    Map.of(
                            "patchId", candidate.getId(),
                            "patchHash", candidate.getPatchHash(),
                            "findingCount", eligible.size()
                    )
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Patch prompt evidence is not serializable", ex
            );
        }
    }

    private boolean isBlocking(Finding finding) {
        return decisions.findByFindingIdOrderByIdAsc(finding.getId()).stream()
                .reduce((ignored, latest) -> latest)
                .map(value -> Boolean.TRUE.equals(value.getBlocking()))
                .orElse(false);
    }

    private Map<String, Object> findingSummary(Finding finding) {
        return Map.of(
                "id", finding.getId(),
                "severity", finding.getSeverity().name(),
                "title", finding.getTitle(),
                "description", finding.getDescription(),
                "filePath", finding.getFilePath(),
                "lineStart", finding.getLineStart() == null ? 0 : finding.getLineStart(),
                "fingerprint", finding.getFingerprint()
        );
    }

    private AgentStepResult publishWithoutPatch(String reason) {
        return new AgentStepResult(
                "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                AgentRunStatus.PUBLISHING_RESULT, Map.of("patchGenerated", false, "reason", reason)
        );
    }

    private AgentPromptAssembler.SectionBudget budget() {
        return new AgentPromptAssembler.SectionBudget(65_536, 16_384);
    }
}
