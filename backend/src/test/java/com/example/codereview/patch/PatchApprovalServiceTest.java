package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchApprovalServiceTest {
    @Mock PatchCandidateRepository patches;
    @Mock PatchApprovalRepository approvals;
    @Mock AgentRunRepository runs;
    @Mock ProjectService projects;
    @Mock ProjectAuthorization projectAuthorization;

    @Test
    void authorizesProjectAndCreatesImmutableApproval() {
        PatchCandidate patch = validatedPatch();
        AgentRun run = new AgentRun(5L, 2L, 3L, "trigger", "head");
        when(patches.findById(9L)).thenReturn(Optional.of(patch));
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        when(approvals.findByPatchCandidateIdAndApproverId(9L, 11L)).thenReturn(Optional.empty());
        when(approvals.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        PatchApprovalService service = new PatchApprovalService(patches, approvals, runs, projects, projectAuthorization);

        PatchApproval approval = service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, "looks good");

        verify(projectAuthorization).requireWrite(5L, 11L);
        assertThat(approval.getPatchHash()).isEqualTo(patch.getPatchHash());
        assertThat(approval.getHeadSha()).isEqualTo("head");
        assertThat(approval.getDecision()).isEqualTo(PatchApprovalDecision.APPROVED);
    }

    @Test
    void repeatedSameDecisionIsIdempotentButStaleOrInvalidApprovalIsRejected() {
        PatchCandidate patch = validatedPatch();
        AgentRun run = new AgentRun(5L, 2L, 3L, "trigger", "head");
        PatchApproval existing = new PatchApproval(9L, 11L, PatchApprovalDecision.APPROVED,
                patch.getPatchHash(), "head", "ok");
        when(patches.findById(9L)).thenReturn(Optional.of(patch));
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        when(approvals.findByPatchCandidateIdAndApproverId(9L, 11L)).thenReturn(Optional.of(existing));
        PatchApprovalService service = new PatchApprovalService(patches, approvals, runs, projects, projectAuthorization);

        assertThat(service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, "again"))
                .isSameAs(existing);
        // head 变更是可预期的业务冲突,必须是 409 而不是 500
        assertThatThrownBy(() -> service.decide(5L, 9L, 7L, 11L, "new-head",
                PatchApprovalDecision.APPROVED, "stale"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
    }

    @Test
    void missingPatchOrRunIsNotFoundRatherThanServerError() {
        AgentRun run = new AgentRun(5L, 2L, 3L, "trigger", "head");
        PatchApprovalService service = new PatchApprovalService(patches, approvals, runs, projects, projectAuthorization);

        when(patches.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, ""))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));

        when(patches.findById(9L)).thenReturn(Optional.of(validatedPatch()));
        when(runs.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, ""))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));

        when(runs.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(5L, 99L, 11L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));
    }

    @Test
    void nullRunHeadShaYieldsConflictNotNullPointer() {
        PatchCandidate patch = validatedPatch();
        AgentRun run = new AgentRun(5L, 2L, 3L, "trigger", null); // head 未知
        when(patches.findById(9L)).thenReturn(Optional.of(patch));
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        PatchApprovalService service = new PatchApprovalService(patches, approvals, runs, projects, projectAuthorization);

        assertThatThrownBy(() -> service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, ""))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
    }

    private static PatchCandidate validatedPatch() {
        PatchCandidate patch = new PatchCandidate(7L, "head", Set.of(2L), "model", "prompt", "diff",
                new PatchValidation(true, null, List.of("src/App.java"), 2));
        patch.recordSandboxValidation(PatchValidationKind.SCAN,
                new PatchSandboxValidation(true, true, true, true, "{}", "ok"));
        return patch;
    }
}
