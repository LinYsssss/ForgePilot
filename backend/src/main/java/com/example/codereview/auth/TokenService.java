package com.example.codereview.auth;

import com.example.codereview.common.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public record ParsedToken(Long userId, String username, String role, int sessionVersion, long expiresAt) {
        public CurrentUser toCurrentUser() {
            return new CurrentUser(userId, username, role);
        }
    }

    private final String secret;
    private final long ttlSeconds;

    public TokenService(
            @Value("${app.security.token-secret}") String secret,
            @Value("${app.security.token-ttl-seconds}") long ttlSeconds
    ) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(UserAccount user) {
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        String payload = user.getId() + ":" + user.getUsername() + ":" + user.getRole() + ":" + user.getSessionVersion() + ":" + expiresAt;
        String encodedPayload = base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return encodedPayload + "." + signature;
    }

    public CurrentUser parse(String token) {
        ParsedToken parsed = parseClaims(token);
        return parsed == null ? null : parsed.toCurrentUser();
    }

    public ParsedToken parseClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(
                    sign(parts[0]).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split(":");
            if (fields.length != 5) {
                return null;
            }
            long expiresAt = Long.parseLong(fields[4]);
            if (Instant.now().getEpochSecond() > expiresAt) {
                return null;
            }
            return new ParsedToken(
                    Long.parseLong(fields[0]),
                    fields[1],
                    fields[2],
                    Integer.parseInt(fields[3]),
                    expiresAt
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64Url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign token", ex);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
