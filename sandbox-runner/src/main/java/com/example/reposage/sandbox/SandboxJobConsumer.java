package com.example.reposage.sandbox;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SandboxJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(SandboxJobConsumer.class);

    private final SandboxJobSigner signer;
    private final SandboxReplayGuard replayGuard;
    private final SandboxExecutor executor;
    private final String signingSecret;
    private final Clock clock;

    public SandboxJobConsumer(
            SandboxJobSigner signer,
            SandboxReplayGuard replayGuard,
            SandboxExecutor executor,
            @Value("${app.sandbox.signing-secret}") String signingSecret,
            Clock clock) {
        this.signer = signer;
        this.replayGuard = replayGuard;
        this.executor = executor;
        this.signingSecret = requireSecret(signingSecret);
        this.clock = clock;
    }

    @RabbitListener(queues = SandboxRabbitConfig.JOB_QUEUE)
    public SandboxResult consume(SignedSandboxJob message) {
        SandboxJob job = message.job();
        SandboxJobSigner.Verification verification = signer.verify(
                job, message.signature(), signingSecret, clock.instant().getEpochSecond());
        if (verification != SandboxJobSigner.Verification.VALID) {
            log.warn("Rejected sandbox job {}: {}", job.jobId(), verification);
            return rejected(job, "sandbox job verification failed: " + verification);
        }
        if (!replayGuard.checkAndRecord(job.nonce())) {
            log.warn("Rejected replayed sandbox job {}", job.jobId());
            return rejected(job, "sandbox job nonce was already used");
        }
        return executor.execute(job);
    }

    private static SandboxResult rejected(SandboxJob job, String message) {
        return new SandboxResult(job.jobId(), SandboxJobStatus.REJECTED, null, "", false, message);
    }

    private static String requireSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.sandbox.signing-secret is required");
        }
        return secret;
    }
}
