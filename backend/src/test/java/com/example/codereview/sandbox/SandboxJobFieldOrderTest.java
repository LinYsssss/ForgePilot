package com.example.codereview.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * SandboxJob 字段序快照:HMAC 的 canonical 序列化与 Runner 侧的同构镜像 record 都依赖
 * 这份字段布局,workspaceArchiveRef 必须钉在第 2 位(审计逐字段核对的基线)。
 * record 组件一旦被重排,这里会先于线上失败。Runner 模块有同构的另一半。
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
