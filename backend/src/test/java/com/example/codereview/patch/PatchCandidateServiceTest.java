package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingSeverity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchCandidateServiceTest {

    @Mock AgentRunRepository runs;
    @Mock FindingRepository findings;
    @Mock PatchCandidateRepository patches;

    @Test
    void rejectsPatchBoundToStaleHead() {
        AgentRun run = new AgentRun(1L, 2L, 3L, "trigger", "head-1");
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        PatchCandidateService service = new PatchCandidateService(runs, findings, patches,
                new UnifiedDiffValidator(10, 100));

        assertThatThrownBy(() -> service.create(new PatchCandidateService.CreateCommand(
                7L, "head-1", "head-2", List.of(), "gpt", "patch-v1", validDiff())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stale head SHA");
    }

    @Test
    void rejectsFindingFromAnotherAgentRun() {
        AgentRun run = new AgentRun(1L, 2L, 3L, "trigger", "head-1");
        Finding foreign = new Finding(99L, FindingSeverity.HIGH, "security", "title", "description",
                "src/App.java", 1, 1, "App", "verified");
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        when(findings.findAllById(List.of(5L))).thenReturn(List.of(foreign));
        PatchCandidateService service = new PatchCandidateService(runs, findings, patches,
                new UnifiedDiffValidator(10, 100));

        assertThatThrownBy(() -> service.create(new PatchCandidateService.CreateCommand(
                7L, "head-1", "head-1", List.of(5L), "gpt", "patch-v1", validDiff())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finding");
    }

    private static String validDiff() {
        return "diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-old\n+new\n";
    }
}
