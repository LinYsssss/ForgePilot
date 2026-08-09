package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.orchestration.steps.ValidatingPatchStepExecutor;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ToolCommand;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateRepository;
import com.example.codereview.patch.PatchValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValidatingPatchStepExecutorTest {

    @Test
    void runsPinnedBuildTestAndScanIndependentlyBeforeApproval() throws Exception {
        PatchCandidateRepository patches = mock(PatchCandidateRepository.class);
        PatchValidationService validation = mock(PatchValidationService.class);
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        PatchCandidate patch = mock(PatchCandidate.class);
        when(patch.getId()).thenReturn(9L);
        when(patch.getAgentRunId()).thenReturn(1L);
        when(patch.getHeadSha()).thenReturn("abcdef1");
        when(patch.getStatus()).thenReturn("SCOPE_VALID");
        when(patch.getFindingIds()).thenReturn(java.util.Set.of(42L));
        when(patch.getPatchHash()).thenReturn("a".repeat(64));
        when(patch.isApprovable()).thenReturn(true);
        when(patch.getApplyStatus()).thenReturn("SUCCEEDED");
        when(patch.getBuildStatus()).thenReturn("PASSED");
        when(patch.getTestStatus()).thenReturn("PASSED");
        when(patch.getScanStatus()).thenReturn("PASSED");
        when(patch.isTargetDisappeared()).thenReturn(true);
        when(patches.findByAgentRunIdOrderByIdAsc(1L)).thenReturn(List.of(patch));
        Finding finding = mock(Finding.class);
        when(finding.getFingerprint()).thenReturn("b".repeat(64));
        when(findings.findById(42L)).thenReturn(Optional.of(finding));
        AgentAnalysisContext context = new AgentAnalysisContext(1L, "abcdef1");
        context.repositoryPrepared("agent-run-1-abcdef1.tar", "abcdef0", "{}", "diff");
        context.changeAnalyzed(mapper.writeValueAsString(new AgentChangeAnalysisCheckpoint(
                "agent-change-analysis-v1",
                new com.example.codereview.language.ChangeSet("abcdef0", "abcdef1", List.of()),
                List.of("java"),
                List.of(new ChangeAnalysis("java", List.of(
                        command("java.maven.compile"), command("java.maven.test"), command("java.pmd")
                ), List.of(), List.of()))
        )));
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.of(context));
        when(validation.validate(any(Long.class), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(patch);
        WorkspaceArchiveService workspaceArchives = mock(WorkspaceArchiveService.class);
        // 精确入参存根兼作装配断言:补丁归档在验证前才产出,入参为 repositoryId=3 与当前候选补丁。
        when(workspaceArchives.produceForPatch(3L, patch))
                .thenReturn("patch-9-" + "a".repeat(64) + ".tar");

        AgentStepResult result = new ValidatingPatchStepExecutor(
                patches, validation, contexts, findings, workspaceArchives, mapper
        ).execute(new AgentStepExecutionContext(
                1L, 16L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.VALIDATING_PATCH, 8, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        verify(workspaceArchives).produceForPatch(3L, patch);
        verify(validation, org.mockito.Mockito.times(3)).validate(
                any(Long.class), any(), any(), any(), any(), any(), any(), any()
        );
    }

    private ToolCommand command(String id) {
        return new ToolCommand(id, List.of(), "analysis@sha256:" + "0".repeat(64), "v1");
    }
}
