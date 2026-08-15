package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 沙箱协议 runner 侧的签名契约。{@code GOLDEN} 与 backend 模块测试断言的是**同一个**固定向量:
 * 相同 job + 相同密钥必须在两侧算出相同签名,backend 签的 job 才能在 runner 验通。
 */
class SandboxJobSignerTest {

    static final String KEY = "sandbox-signing-key";
    // 必须与 backend SandboxJobSignerTest 里的 GOLDEN 完全相等。
    static final String GOLDEN = "dde08c4962152bb7acf06adea4cb45b30444daa4bbc43c94d09f420bb851c289";

    private final SandboxJobSigner signer = new SandboxJobSigner();

    static SandboxJob sampleJob() {
        return new SandboxJob(
                "job-1",
                "s3://bucket/ws/job-1.tar.zst",
                "sha256:abcdef",
                "git.diff",
                List.of("--base", "main", "--head", "feature"),
                new SandboxJob.Limits(1000, 512, 128, 60000),
                1_900_000_000L,
                "nonce-1");
    }

    @Test
    void signatureMatchesCrossModuleGoldenVector() {
        assertThat(signer.sign(sampleJob(), KEY)).isEqualTo(GOLDEN);
    }

    @Test
    void verifyAcceptsValidRejectsTamperedAndExpired() {
        SandboxJob job = sampleJob();
        String signature = signer.sign(job, KEY);
        assertThat(signer.verify(job, signature, KEY, job.expiryEpochSeconds() - 1))
                .isEqualTo(SandboxJobSigner.Verification.VALID);
        assertThat(signer.verify(job, "bad", KEY, 0))
                .isEqualTo(SandboxJobSigner.Verification.INVALID_SIGNATURE);
        assertThat(signer.verify(job, signature, KEY, job.expiryEpochSeconds() + 1))
                .isEqualTo(SandboxJobSigner.Verification.EXPIRED);
    }

    @Test
    void replayGuardRejectsReusedNonce() {
        SandboxReplayGuard guard = new SandboxReplayGuard();
        assertThat(guard.checkAndRecord("n1")).isTrue();
        assertThat(guard.checkAndRecord("n1")).isFalse();
    }
}
