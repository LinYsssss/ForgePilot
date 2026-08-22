package com.forgepilot.scm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Verifies provider authentication over the <em>raw request bytes</em>.
 *
 * <p>The HMAC covers the exact octets the provider signed. Parsing the delivery
 * into an object and re-serializing it changes key order, whitespace, escaping and
 * number formatting, so honest deliveries would fail — and an implementation that
 * "fixes" that by canonicalizing before verifying has stopped authenticating the
 * bytes it then acts on. {@code WebhookSignatureVerifierTest} pins this with a body
 * that parses into an identical document but whose bytes differ.
 */
@Component
class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String GITHUB_PREFIX = "sha256=";
    private static final String GITLAB_PREFIX = "v1,";
    private static final String GITLAB_SECRET_PREFIX = "whsec_";
    private static final Duration GITLAB_MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    WebhookSignatureVerifier() {
        this(Clock.systemUTC());
    }

    WebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    boolean matches(byte[] body, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(GITHUB_PREFIX)) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), body))
                .getBytes(StandardCharsets.UTF_8);
        byte[] provided = signatureHeader.substring(GITHUB_PREFIX.length())
                .toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        // Constant time, and length safe: a truncated hex digest is simply unequal.
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * Current GitLab signs Standard Webhooks; older installations send a plain
     * secret token. A present but invalid Standard form never downgrades to the
     * weaker legacy form.
     */
    boolean matchesGitLab(byte[] body, String secret, String signatureHeader,
            String messageId, String timestampHeader, String tokenHeader) {
        boolean hasStandardHeader = signatureHeader != null || messageId != null || timestampHeader != null;
        if (!hasStandardHeader) {
            return constantTimeTextEquals(secret, tokenHeader);
        }
        if (signatureHeader == null || messageId == null || timestampHeader == null
                || !secret.startsWith(GITLAB_SECRET_PREFIX)) {
            return false;
        }

        Instant signedAt;
        byte[] key;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(timestampHeader));
            key = Base64.getDecoder().decode(secret.substring(GITLAB_SECRET_PREFIX.length()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (key.length == 0 || Duration.between(signedAt, clock.instant()).abs()
                .compareTo(GITLAB_MAX_CLOCK_SKEW) > 0) {
            return false;
        }

        byte[] prefix = (messageId + "." + timestampHeader + ".").getBytes(StandardCharsets.UTF_8);
        byte[] expected = (GITLAB_PREFIX + Base64.getEncoder().encodeToString(hmac(key, prefix, body)))
                .getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatureHeader.trim().split("\\s+")) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeTextEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            for (int index = 0; index < parts.length - 1; index++) {
                mac.update(parts[index]);
            }
            return mac.doFinal(parts[parts.length - 1]);
        } catch (GeneralSecurityException impossible) {
            // HmacSHA256 is required of every JDK, and the key is never empty.
            throw new IllegalStateException(impossible);
        }
    }
}
