package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runner half of the bidirectional wire-format contract. The literals below are the backend's real
 * encoder output, pinned by the backend's own golden test with the same strings — if either side
 * changes the format unilaterally, its golden test breaks first. This is the guard that was missing
 * when the backend emitted {@code workspace://...} and this module rejected every colon.
 */
class WorkspaceArchiveReferenceTest {

    @Test
    void parseAcceptsBackendPinnedWireFormat() {
        assertThat(WorkspaceArchiveReference.parse("agent-run-42-abcdef1234567.tar"))
                .isEqualTo("agent-run-42-abcdef1234567.tar");
        assertThat(WorkspaceArchiveReference.parse("patch-9-" + "a".repeat(64) + ".tar"))
                .isEqualTo("patch-9-" + "a".repeat(64) + ".tar");
    }

    @Test
    void mirrorEncoderMatchesBackendPinnedWireFormat() {
        assertThat(WorkspaceArchiveReference.forAgentRun(42L, "ABCdef1234567"))
                .isEqualTo("agent-run-42-abcdef1234567.tar");
        assertThat(WorkspaceArchiveReference.forPatch(9L, "A".repeat(64)))
                .isEqualTo("patch-9-" + "a".repeat(64) + ".tar");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "workspace://agent-run-42-abcdef1.tar",   // the historical drift: scheme-carrying refs
            "file:///etc/passwd",                     // scheme forgery
            "../x.tar",                               // traversal
            "a\\b.tar",                               // backslash
            "/etc/passwd",                            // absolute path
            "a/b.tar",                                // subdirectory
            "-leading.tar",                           // leading dash
            ".hidden.tar",                            // leading dot
            "a..b.tar",                               // inner traversal token
            " ",                                      // blank
            "bad name.tar"                            // outside the whitelist
    })
    void parseRejectsUnsafeReferences(String reference) {
        assertThatThrownBy(() -> WorkspaceArchiveReference.parse(reference))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace archive reference is invalid");
    }

    @Test
    void parseRejectsNullEmptyAndOversize() {
        assertThatThrownBy(() -> WorkspaceArchiveReference.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceArchiveReference.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceArchiveReference.parse(
                "x".repeat(WorkspaceArchiveReference.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
