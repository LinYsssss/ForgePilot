package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingSeverity;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PatchCandidatePersistenceTest {
    @Autowired AgentRunRepository runs;
    @Autowired FindingRepository findings;
    @Autowired PatchCandidateRepository patches;

    @Test
    void persistsImmutableGenerationBindingsAndFindingIds() {
        AgentRun run = runs.save(new AgentRun(1L, 2L, 3L, "patch-trigger", "head-sha"));
        Finding finding = findings.save(new Finding(run.getId(), FindingSeverity.HIGH, "security", "title",
                "description", "src/App.java", 1, 1, "App", "verified"));
        String diff = "diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-old\n+new\n";
        PatchCandidate saved = patches.saveAndFlush(new PatchCandidate(run.getId(), "head-sha", Set.of(finding.getId()),
                "gpt-5", "patch-v1", diff, new PatchValidation(true, null, java.util.List.of("src/App.java"), 2)));

        PatchCandidate loaded = patches.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getAgentRunId()).isEqualTo(run.getId());
        assertThat(loaded.getHeadSha()).isEqualTo("head-sha");
        assertThat(loaded.getFindingIds()).containsExactly(finding.getId());
        assertThat(loaded.getGeneratorModel()).isEqualTo("gpt-5");
        assertThat(loaded.getPromptVersion()).isEqualTo("patch-v1");
        assertThat(loaded.getPatchHash()).matches("[a-f0-9]{64}");
        assertThat(loaded.getStatus()).isEqualTo("SCOPE_VALID");
    }
}
