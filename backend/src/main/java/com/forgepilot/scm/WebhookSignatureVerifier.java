package com.forgepilot.scm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Verifies {@code X-Hub-Signature-256} over the <em>raw request bytes</em>.
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
    private static final String PREFIX = "sha256=";

    boolean matches(byte[] body, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = hex(body, secret).getBytes(StandardCharsets.UTF_8);
        byte[] provided = signatureHeader.substring(PREFIX.length())
                .toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        // Constant time, and length safe: a truncated hex digest is simply unequal.
        return MessageDigest.isEqual(expected, provided);
    }

    private static String hex(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException impossible) {
            // HmacSHA256 is required of every JDK, and the key is never empty.
            throw new IllegalStateException(impossible);
        }
    }
}
