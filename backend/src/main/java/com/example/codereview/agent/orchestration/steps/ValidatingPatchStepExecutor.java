package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentChangeAnalysisCheckpoint;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.orchestration.WorkspaceArchiveService;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.language.ToolCommand;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateRepository;
import com.example.codereview.patch.PatchValidationKind;
import com.example.codereview.patch.PatchValidationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ValidatingPatchStepExecutor implements AgentStepExecutor {

    private final PatchCandidateRepository patches;
    private final PatchValidationService validation;
    private final AgentAnalysisContextRepository contexts;
    private final FindingRepository findings;
    private final WorkspaceArchiveService workspaceArchives;
    private final ObjectMapper mapper;

    @Autowired
    public ValidatingPatchStepExecutor(
            PatchCandidateRepository patches,
            PatchValidationService validation,
            AgentAnalysisContextRepository contexts,
            FindingRepository findings,
            WorkspaceArchiveService workspaceArchives,
            ObjectMapper mapper
    ) {
        this.patches = patches;
        this.validation = validation;
        this.contexts = contexts;
        this.findings = findings;
        this.workspaceArchives = workspaceArchives;
        this.mapper = mapper;
    }

    public ValidatingPatchStepExecutor() {
        this.patches = null;
        this.validation = null;
        this.contexts = null;
        this.findings = null;
        this.workspaceArchives = null;
        this.mapper = null;
    }

    @Override
    public AgentRunStatus state() { return AgentRunStatus.VALIDATING_PATCH; }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (patches == null) {
            return AgentStepResult.checkpoint(state());
        }
        PatchCandidate patch = patches.findByAgentRunIdOrderByIdAsc(context.agentRunId()).stream()
                .max(Comparator.comparing(PatchCandidate::getId))
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Patch candidate is missing"
                ));
        if (!patch.getHeadSha().equals(context.headSha()) || !"SCOPE_VALID".equals(patch.getStatus())) {
            return publish("patch is stale or failed scope policy");
        }
        var analysis = contexts.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Patch analysis context is missing"
                ));
        analysis.requireHead(context.headSha());
        try {
            AgentChangeAnalysisCheckpoint change = mapper.readValue(
                    analysis.getChangeSetJson(), AgentChangeAnalysisCheckpoint.class
            );
            Map<PatchValidationKind, ToolCommand> commands = commands(change);
            if (commands.isEmpty()) {
                return publish("no pinned validation command is available");
            }
            String target = patch.getFindingIds().stream().findFirst()
                    .flatMap(findings::findById)
                    .map(value -> value.getFingerprint() == null ? "" : value.getFingerprint())
                    .orElse("");
            // 补丁归档在验证前才真实产出(head 树 + 候选补丁)。产不出来走既有的设计内降级:
            // approvable=false 附原因,Run 直接去发布,而不是把整个 Run 打成 FAILED。
            String archiveRef;
            try {
                archiveRef = workspaceArchives.produceForPatch(context.repositoryId(), patch);
            } catch (BusinessException | IllegalStateException ex) {
                return publish("sandbox patch validation is unavailable");
            }
            List<String> completed = new ArrayList<>();
            PatchCandidate current = patch;
            for (Map.Entry<PatchValidationKind, ToolCommand> entry : commands.entrySet()) {
                ToolCommand command = entry.getValue();
                try {
                    current = validation.validate(
                            patch.getId(), context.headSha(), archiveRef,
                            command.commandId(), target, command.imageDigest(), entry.getKey(),
                            new ToolContext(
                                    context.agentRunId(), context.agentStepId(),
                                    "patch:" + patch.getId() + ":" + entry.getKey().name().toLowerCase(),
                                    false, context.traceId()
                            )
                    );
                    completed.add(entry.getKey().name());
                } catch (IllegalStateException unavailable) {
                    return publish("sandbox patch validation is unavailable");
                }
            }
            AgentRunStatus next = current.isApprovable()
                    ? AgentRunStatus.WAITING_APPROVAL
                    : AgentRunStatus.PUBLISHING_RESULT;
            return new AgentStepResult(
                    "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                    next,
                    Map.of(
                            "patchId", patch.getId(),
                            "approvable", current.isApprovable(),
                            "validations", completed,
                            "applyStatus", current.getApplyStatus(),
                            "buildStatus", current.getBuildStatus(),
                            "testStatus", current.getTestStatus(),
                            "scanStatus", current.getScanStatus(),
                            "targetDisappeared", current.isTargetDisappeared()
                    )
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Patch validation command checkpoint is invalid", ex
            );
        }
    }

    private Map<PatchValidationKind, ToolCommand> commands(AgentChangeAnalysisCheckpoint change) {
        EnumMap<PatchValidationKind, ToolCommand> selected = new EnumMap<>(PatchValidationKind.class);
        change.analyses().stream().flatMap(value -> value.commands().stream()).forEach(command -> {
            String id = command.commandId();
            if ((id.contains("compile") || id.contains("build")) && !selected.containsKey(PatchValidationKind.BUILD)) {
                selected.put(PatchValidationKind.BUILD, command);
            } else if ((id.contains("test") || id.contains("pytest") || id.contains("jest")
                    || id.contains("vitest")) && !selected.containsKey(PatchValidationKind.TEST)) {
                selected.put(PatchValidationKind.TEST, command);
            } else if ((id.contains("pmd") || id.contains("spotbugs") || id.contains("checkstyle")
                    || id.contains("ruff") || id.contains("bandit") || id.contains("eslint")
                    || id.contains("semgrep")) && !selected.containsKey(PatchValidationKind.SCAN)) {
                selected.put(PatchValidationKind.SCAN, command);
            }
        });
        return selected;
    }

    private AgentStepResult publish(String reason) {
        return new AgentStepResult(
                "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                AgentRunStatus.PUBLISHING_RESULT,
                Map.of("approvable", false, "reason", reason)
        );
    }
}
