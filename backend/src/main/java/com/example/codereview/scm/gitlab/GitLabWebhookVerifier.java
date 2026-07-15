package com.example.codereview.scm.gitlab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * Verifies a GitLab webhook delivery by comparing the {@code X-Gitlab-Token} header against the
 * installation's webhook secret.
 *
 * <p>GitLab uses a shared secret token, not an HMAC signature, so this is a direct equality check —
 * done in constant time, and strict: a missing secret or missing token is a hard failure.
 */
@Component
public class GitLabWebhookVerifier {

    public boolean verify(String tokenHeader, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        if (tokenHeader == null) {
            return false;
        }
        return MessageDigest.isEqual(
                tokenHeader.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }
}
