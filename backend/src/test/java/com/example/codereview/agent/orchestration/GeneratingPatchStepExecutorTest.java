package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.model.AgentModelBudgetPolicy;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.AgentPatchModelService;
import com.example.codereview.agent.orchestration.steps.GeneratingPatchStepExecutor;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.prompt.PromptTemplateRegistry;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingDecisionEntity;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeneratingPatchStepExecutorTest {

    @Test
    void generatesPatchOnlyForVerifiedBlockingFindingAndBindsHead() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        FindingDecisionRepository decisions = mock(FindingDecisionRepository.class);
        AgentModelClient client = mock(AgentModelClient.class);
        AgentPatchModelService patchModel = mock(AgentPatchModelService.class);
        // r8-R1 后指令文本来自模板注册表:spy 真组装器让 instruction() 走真模板,
        // assemble 仍打桩隔离信封逻辑。
        AgentPromptAssembler prompts = spy(new AgentPromptAssembler(new PromptTemplateRegistry()));
        PatchCandidateService patches = mock(PatchCandidateService.class);
        Finding finding = new Finding(
                1L, com.example.codereview.finding.FindingSeverity.HIGH, "security", "Leak",
                "unsafe", "src/Main.java", 10, 10, "run", "verified"
        );
        org.springframework.test.util.ReflectionTestUtils.setField(finding, "id", 42L);
        org.springframework.test.util.ReflectionTestUtils.setField(finding, "fingerprint", "a".repeat(64));
        FindingDecisionEntity decision = mock(FindingDecisionEntity.class);
        when(decision.getBlocking()).thenReturn(true);
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.of(context(1L, mapper)));
        when(findings.findByAgentRunIdOrderByIdAsc(1L)).thenReturn(List.of(finding));
        when(decisions.findByFindingIdOrderByIdAsc(42L)).thenReturn(List.of(decision));
        doReturn(new com.example.codereview.agent.model.PromptEnvelope(
                "policy", "patch", "diff", "", "", "", "{}",
                "review-v1", null, "patch-candidate-v1", List.of(), List.of()
        )).when(prompts).assemble(any());
        when(patchModel.generate(any(), any(), any())).thenReturn(new AgentPatchModelService.Result(
                new com.example.codereview.agent.model.PatchModelResponse(
                        "diff --git a/src/Main.java b/src/Main.java\n"
                                + "--- a/src/Main.java\n+++ b/src/Main.java\n"
                                + "@@ -1 +1 @@\n-old\n+new\n"
                ), 100, 50, 10
        ));
        PatchCandidate patch = mock(PatchCandidate.class);
        when(patch.getId()).thenReturn(9L);
        when(patch.getPatchHash()).thenReturn("b".repeat(64));
        when(patch.getStatus()).thenReturn("SCOPE_VALID");
        when(patches.create(any())).thenReturn(patch);

        AgentStepResult result = new GeneratingPatchStepExecutor(
                contexts, findings, decisions, Optional.of(client), patchModel,
                AgentModelBudgetPolicy.defaults(), prompts, patches, mapper
        ).execute(new AgentStepExecutionContext(
                1L, 15L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.GENERATING_PATCH, 7, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.VALIDATING_PATCH);
        ArgumentCaptor<PatchCandidateService.CreateCommand> command =
                ArgumentCaptor.forClass(PatchCandidateService.CreateCommand.class);
        verify(patches).create(command.capture());
        assertThat(command.getValue().boundHeadSha()).isEqualTo("abcdef1");
        assertThat(command.getValue().findingIds()).containsExactly(42L);
    }

    private AgentAnalysisContext context(Long runId, ObjectMapper mapper) throws Exception {
        AgentAnalysisContext context = new AgentAnalysisContext(runId, "abcdef1");
        context.repositoryPrepared("workspace://archive", "abcdef0", "{}", "diff");
        context.changeAnalyzed("{}");
        context.contextRetrieved("{}");
        return context;
    }
}
