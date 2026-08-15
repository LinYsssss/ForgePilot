package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 双向线格式契约的 runner 半边。下面的字面量就是 backend 编码器的真实输出,backend 自己的
 * golden 测试用同样的字符串钉住——任何一侧单方面改格式,它那侧的 golden 测试会先炸。
 * 当初 backend 发 {@code workspace://...} 而本模块拒收一切冒号时,缺的正是这道闸。
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
