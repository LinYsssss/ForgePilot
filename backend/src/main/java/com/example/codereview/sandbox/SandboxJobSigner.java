package com.example.codereview.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies {@link SandboxJob}s with HMAC-SHA256 over a canonical serialization.
 *
 * <p>The canonical form is a deterministic JSON string with alphabetically-ordered keys, no
 * whitespace, and explicit string escaping — built by hand (not via a JSON library) so the backend
 * and the sandbox-runner produce byte-identical input regardless of their dependencies. This class
 * is mirrored verbatim in the runner module; a golden-vector test in each module pins cross-module
 * compatibility.
 *
 * <p>Verification rejects an invalid signature and an expired job. Replay rejection (nonce already
 * seen) is enforced by {@link SandboxReplayGuard}, which the runner consults per delivery.
 */
public final class SandboxJobSigner {

    /** Outcome of {@link #verify}. */
    public enum Verification {
        VALID,
        INVALID_SIGNATURE,
        EXPIRED
    }

    public String sign(SandboxJob job, String secret) {
        return hmacSha256Hex(canonicalJson(job).getBytes(StandardCharsets.UTF_8), secret);
    }

    public boolean matches(SandboxJob job, String signature, String secret) {
        String expected = sign(job, secret);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    public Verification verify(SandboxJob job, String signature, String secret, long nowEpochSeconds) {
        if (!matches(job, signature, secret)) {
            return Verification.INVALID_SIGNATURE;
        }
        if (job.expiryEpochSeconds() < nowEpochSeconds) {
            return Verification.EXPIRED;
        }
        return Verification.VALID;
    }

    /** Deterministic, library-independent canonical JSON: sorted keys, no whitespace. */
    static String canonicalJson(SandboxJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"args\":[");
        for (int i = 0; i < job.args().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(job.args().get(i)));
        }
        sb.append("],");
        sb.append("\"commandId\":").append(quote(job.commandId())).append(',');
        sb.append("\"expiryEpochSeconds\":").append(job.expiryEpochSeconds()).append(',');
        sb.append("\"imageDigest\":").append(quote(job.imageDigest())).append(',');
        sb.append("\"jobId\":").append(quote(job.jobId())).append(',');
        sb.append("\"limits\":{")
                .append("\"cpuMillis\":").append(job.limits().cpuMillis()).append(',')
                .append("\"memoryMb\":").append(job.limits().memoryMb()).append(',')
                .append("\"pids\":").append(job.limits().pids()).append(',')
                .append("\"timeoutMs\":").append(job.limits().timeoutMs())
                .append("},");
        sb.append("\"nonce\":").append(quote(job.nonce())).append(',');
        sb.append("\"workspaceArchiveRef\":").append(quote(job.workspaceArchiveRef()));
        sb.append('}');
        return sb.toString();
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String hmacSha256Hex(byte[] body, String secret) {
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
