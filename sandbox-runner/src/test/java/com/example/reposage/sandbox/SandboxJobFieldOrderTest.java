package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * SandboxJob field-order snapshot. The HMAC canonical serialization and the backend's isomorphic
 * record both depend on this layout; workspaceArchiveRef is pinned at position 2 (the audit's
 * field-by-field baseline). Reordering the record components breaks here before it breaks in
 * production. The backend module carries the matching half.
 */
class SandboxJobFieldOrderTest {

    @Test
    void recordComponentOrderIsFrozen() {
        assertThat(Arrays.stream(SandboxJob.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly(
                        "jobId",
                        "workspaceArchiveRef",
                        "imageDigest",
                        "commandId",
                        "args",
                        "limits",
                        "expiryEpochSeconds",
                        "nonce");
    }

    @Test
    void limitsComponentOrderIsFrozen() {
        assertThat(Arrays.stream(SandboxJob.Limits.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("cpuMillis", "memoryMb", "pids", "timeoutMs");
    }
}
