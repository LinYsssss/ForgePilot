package com.example.codereview.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 用 HMAC-SHA256 对 {@link SandboxJob} 的规范序列化做签名与验签。
 *
 * <p>规范形式是一个确定性 JSON 串:键按字母序、无空白、显式转义——**手写**而非借助 JSON 库,
 * 这样 backend 与 sandbox-runner 无论各自依赖如何,都能产出逐字节一致的输入。
 * 本类在 runner 模块里有一份逐字镜像;两侧各有一个 golden 向量测试钉住跨模块兼容性。
 *
 * <p>验签会拒绝无效签名与已过期的 job。重放拒绝(nonce 已见过)由 {@link SandboxReplayGuard}
 * 负责,runner 每次投递都会查它。
 */
public final class SandboxJobSigner {

    /** {@link #verify} 的结果。 */
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

    /** 确定性、不依赖任何库的规范 JSON:键有序、无空白。 */
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
