package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * SandboxJob 字段顺序快照。HMAC 的规范序列化与 backend 那份同构 record 都依赖这个布局;
 * workspaceArchiveRef 被钉在第 2 位(审计逐字段基线)。调换 record 分量的顺序会先在这里炸,
 * 而不是等到生产上才炸。backend 模块持有对应的另一半。
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
