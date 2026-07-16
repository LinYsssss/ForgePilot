package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SandboxJobConsumerTest {

    private static final String SECRET = "consumer-test-secret";
    private static final long NOW = 1_800_000_000L;

    private final SandboxJobSigner signer = new SandboxJobSigner();
    private final SandboxReplayGuard replayGuard = new SandboxReplayGuard();
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    @Test
    void verifiesAndExecutesAcceptedJobOnce() {
        AtomicInteger calls = new AtomicInteger();
        SandboxExecutor executor = job -> {
            calls.incrementAndGet();
            return new SandboxResult(job.jobId(), SandboxJobStatus.SUCCEEDED, 0, "ok", false, null);
        };
        SandboxJobConsumer consumer = new SandboxJobConsumer(signer, replayGuard, executor, SECRET, clock);
        SandboxJob job = job("nonce-1", NOW + 60);
        SignedSandboxJob message = new SignedSandboxJob(job, signer.sign(job, SECRET));

        SandboxResult result = consumer.consume(message);

        assertThat(result.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsInvalidExpiredAndReplayedJobsWithoutExecutingThem() {
        AtomicInteger calls = new AtomicInteger();
        SandboxExecutor executor = job -> {
            calls.incrementAndGet();
            return new SandboxResult(job.jobId(), SandboxJobStatus.SUCCEEDED, 0, "ok", false, null);
        };
        SandboxJobConsumer consumer = new SandboxJobConsumer(signer, replayGuard, executor, SECRET, clock);

        SandboxJob invalid = job("nonce-invalid", NOW + 60);
        assertThat(consumer.consume(new SignedSandboxJob(invalid, "bad-signature")).status())
                .isEqualTo(SandboxJobStatus.REJECTED);

        SandboxJob expired = job("nonce-expired", NOW - 1);
        assertThat(consumer.consume(new SignedSandboxJob(expired, signer.sign(expired, SECRET))).status())
                .isEqualTo(SandboxJobStatus.REJECTED);

        SandboxJob replayed = job("nonce-replay", NOW + 60);
        SignedSandboxJob replayMessage = new SignedSandboxJob(replayed, signer.sign(replayed, SECRET));
        assertThat(consumer.consume(replayMessage).status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(consumer.consume(replayMessage).status()).isEqualTo(SandboxJobStatus.REJECTED);
        assertThat(calls).hasValue(1);
    }

    private static SandboxJob job(String nonce, long expiry) {
        return new SandboxJob(
                "job-" + nonce,
                "archive://workspace/job.tar.zst",
                "sha256:abcdef",
                "git.diff",
                List.of("--base", "main"),
                new SandboxJob.Limits(1000, 512, 128, 60_000),
                expiry,
                nonce);
    }
}
