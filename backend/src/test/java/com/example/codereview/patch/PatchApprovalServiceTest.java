package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
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

    @Test
    void authorizesProjectAndCreatesImmutableApproval() {
        PatchCandidate patch = validatedPatch();
        AgentRun run = new AgentRun(5L, 2L, 3L, "trigger", "head");
        when(patches.findById(9L)).thenReturn(Optional.of(patch));
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        when(approvals.findByPatchCandidateIdAndApproverId(9L, 11L)).thenReturn(Optional.empty());
        when(approvals.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        PatchApprovalService service = new PatchApprovalService(patches, approvals, runs, projects);

        PatchApproval approval = service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, "looks good");

        verify(projects).getRequired(5L, 11L);
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
        PatchApprovalService service = new PatchApprovalService(patches, approvals, runs, projects);

        assertThat(service.decide(5L, 9L, 7L, 11L, "head", PatchApprovalDecision.APPROVED, "again"))
                .isSameAs(existing);
        assertThatThrownBy(() -> service.decide(5L, 9L, 7L, 11L, "new-head",
                PatchApprovalDecision.APPROVED, "stale")).hasMessageContaining("stale");
    }

    private static PatchCandidate validatedPatch() {
        PatchCandidate patch = new PatchCandidate(7L, "head", Set.of(2L), "model", "prompt", "diff",
                new PatchValidation(true, null, List.of("src/App.java"), 2));
        patch.recordSandboxValidation(PatchValidationKind.SCAN,
                new PatchSandboxValidation(true, true, true, true, "{}", "ok"));
        return patch;
    }
}
