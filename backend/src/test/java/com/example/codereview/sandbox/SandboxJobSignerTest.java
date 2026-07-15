package com.example.codereview.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Signing contract for the backend side of the sandbox protocol. The {@code GOLDEN} signature is a
 * fixed vector shared verbatim with the sandbox-runner module's test, proving both modules produce
 * byte-identical signatures for the same job and key.
 */
class SandboxJobSignerTest {

    static final String KEY = "sandbox-signing-key";
    // Shared with sandbox-runner SandboxJobSignerTest — both must agree.
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
    void signatureIsDeterministicAndMatchesGoldenVector() {
        SandboxJob job = sampleJob();
        String signature = signer.sign(job, KEY);
        assertThat(signature).isEqualTo(signer.sign(job, KEY)); // deterministic
        assertThat(signature).isEqualTo(GOLDEN);
    }

    @Test
    void verifyAcceptsValidJobWithinExpiry() {
        SandboxJob job = sampleJob();
        String signature = signer.sign(job, KEY);
        assertThat(signer.verify(job, signature, KEY, job.expiryEpochSeconds() - 1))
                .isEqualTo(SandboxJobSigner.Verification.VALID);
    }

    @Test
    void verifyRejectsTamperedSignatureAndWrongKey() {
        SandboxJob job = sampleJob();
        String signature = signer.sign(job, KEY);
        assertThat(signer.verify(job, signature + "00", KEY, 0))
                .isEqualTo(SandboxJobSigner.Verification.INVALID_SIGNATURE);
        assertThat(signer.verify(job, signer.sign(job, "other-key"), KEY, 0))
                .isEqualTo(SandboxJobSigner.Verification.INVALID_SIGNATURE);
    }

    @Test
    void verifyRejectsExpiredJob() {
        SandboxJob job = sampleJob();
        String signature = signer.sign(job, KEY);
        assertThat(signer.verify(job, signature, KEY, job.expiryEpochSeconds() + 1))
                .isEqualTo(SandboxJobSigner.Verification.EXPIRED);
    }

    @Test
    void replayGuardRejectsReusedNonce() {
        SandboxReplayGuard guard = new SandboxReplayGuard();
        assertThat(guard.checkAndRecord("nonce-1")).isTrue();
        assertThat(guard.checkAndRecord("nonce-1")).isFalse();
        assertThat(guard.checkAndRecord("nonce-2")).isTrue();
    }
}
