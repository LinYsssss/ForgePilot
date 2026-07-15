package com.example.codereview.scm.github;

import com.example.codereview.webhook.WebhookSignatures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * Verifies a GitHub webhook delivery against the {@code X-Hub-Signature-256} HMAC-SHA256 header.
 *
 * <p>Stricter than the legacy demo path: an SCM installation always carries a webhook secret, so a
 * missing secret or missing/malformed signature is a hard failure (never a skip). The HMAC is taken
 * over the exact raw request bytes the controller captured — never over reserialized JSON — and the
 * comparison is constant-time. Reuses the shared HMAC primitive in {@link WebhookSignatures}.
 */
@Component
public class GitHubWebhookVerifier {

    public boolean verify(byte[] body, String signatureHeader, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String expected = "sha256=" + WebhookSignatures.hmacSha256Hex(
                body == null ? new byte[0] : body, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }
}
