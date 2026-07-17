package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatchCandidateValidationTest {

    @Test
    void recordsApplyBuildAndTestIndependentlyAndRequiresTargetDisappearance() {
        PatchCandidate candidate = candidate();

        candidate.recordSandboxValidation(PatchValidationKind.BUILD,
                new PatchSandboxValidation(true, true, false, false, "{\"build\":false}", "compile failed"));
        assertThat(candidate.isApprovable()).isFalse();
        assertThat(candidate.getApplyStatus()).isEqualTo("SUCCEEDED");
        assertThat(candidate.getBuildStatus()).isEqualTo("FAILED");

        candidate.recordSandboxValidation(PatchValidationKind.SCAN,
                new PatchSandboxValidation(true, true, true, true, "{\"scan\":true}", "fingerprint removed"));
        candidate.recordSandboxValidation(PatchValidationKind.TEST,
                new PatchSandboxValidation(true, true, true, true, "{\"test\":true}", "tests passed"));

        assertThat(candidate.isApprovable()).isTrue();
        assertThat(candidate.getBuildStatus()).isEqualTo("FAILED");
        assertThat(candidate.getTestStatus()).isEqualTo("PASSED");
        assertThat(candidate.getScanStatus()).isEqualTo("PASSED");
        assertThat(candidate.getValidationResultJson()).contains("build", "scan", "test");
        assertThat(candidate.getValidationLog()).contains("compile failed", "fingerprint removed", "tests passed");
    }

    private static PatchCandidate candidate() {
        return new PatchCandidate(1L, "head", Set.of(2L), "model", "prompt", "diff",
                new PatchValidation(true, null, List.of("src/App.java"), 2));
    }
}
