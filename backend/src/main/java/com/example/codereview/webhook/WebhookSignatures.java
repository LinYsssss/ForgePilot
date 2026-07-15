package com.example.codereview.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared HMAC-SHA256 primitive for webhook signature verification.
 *
 * <p>{@link #hmacSha256Hex} is the raw MAC used by the SCM verifiers, which compare it in constant
 * time against the provider's signature header over the exact raw request bytes.
 */
public final class WebhookSignatures {

    private WebhookSignatures() {
    }

    public static boolean verifyGithub(byte[] body, String signatureHeader, String secret) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String expected = "sha256=" + hmacSha256Hex(body == null ? new byte[0] : body, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }
}
