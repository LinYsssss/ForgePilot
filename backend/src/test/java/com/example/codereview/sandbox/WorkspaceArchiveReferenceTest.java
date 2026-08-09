package com.example.codereview.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 双向契约测试的后端半边:把线上格式钉成字面量。Runner 侧同名测试用同一批字面量
 * 驱动它那份同构编解码器,任何一侧单方面改格式都会先撞碎各自的金标测试 ——
 * 这正是当年 workspace:// 契约漂移(后端产出、Runner 必拒)缺失的防线。
 */
class WorkspaceArchiveReferenceTest {

    @Test
    void encodesAgentRunReferenceAsPinnedWireFormat() {
        assertThat(WorkspaceArchiveReference.forAgentRun(42L, "ABCdef1234567"))
                .isEqualTo("agent-run-42-abcdef1234567.tar");
    }

    @Test
    void encodesPatchReferenceAsPinnedWireFormat() {
        assertThat(WorkspaceArchiveReference.forPatch(9L, "A".repeat(64)))
                .isEqualTo("patch-9-" + "a".repeat(64) + ".tar");
    }

    @Test
    void parseAcceptsEveryEncodedOutput() {
        assertThat(WorkspaceArchiveReference.parse(WorkspaceArchiveReference.forAgentRun(1L, "abcdef1")))
                .isEqualTo("agent-run-1-abcdef1.tar");
        assertThat(WorkspaceArchiveReference.parse(WorkspaceArchiveReference.forPatch(7L, "f".repeat(40))))
                .startsWith("patch-7-");
        // Runner 测试夹具沿用的裸文件名依旧合法
        assertThat(WorkspaceArchiveReference.parse("repo.zip")).isEqualTo("repo.zip");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "workspace://agent-run-42-abcdef1.tar",   // 历史断链格式:含 scheme,必须拒绝
            "file:///etc/passwd",                     // scheme 伪造
            "../x.tar",                               // 目录穿越
            "a\\b.tar",                               // 反斜杠
            "/etc/passwd",                            // 绝对路径
            "a/b.tar",                                // 子目录
            "-leading.tar",                           // 前导 -(命令注入面)
            ".hidden.tar",                            // 前导 .
            "a..b.tar",                               // 名内 ..(纵深防御)
            " ",                                      // 空白
            "bad name.tar"                            // 白名单外字符
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
        // 恰好在上限内则通过
        assertThat(WorkspaceArchiveReference.parse("x".repeat(WorkspaceArchiveReference.MAX_LENGTH)))
                .hasSize(WorkspaceArchiveReference.MAX_LENGTH);
    }

    @Test
    void encodeValidatesItsInputs() {
        assertThatThrownBy(() -> WorkspaceArchiveReference.forAgentRun(null, "abcdef1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceArchiveReference.forAgentRun(0L, "abcdef1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceArchiveReference.forAgentRun(1L, "not hex!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceArchiveReference.forPatch(1L, "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
