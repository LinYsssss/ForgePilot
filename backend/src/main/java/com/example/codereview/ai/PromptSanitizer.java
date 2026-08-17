package com.example.codereview.ai;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Shared pure prompt-boundary helpers. Model context is untrusted and must be redacted before use. */
public final class PromptSanitizer {

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)Authorization\\s*:\\s*(?:Bearer|Basic)\\s+[^\\s]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Z0-9_]*(?:TOKEN|PASSWORD|SECRET|API_KEY|ACCESS_KEY)[A-Z0-9_]*)"
                    + "\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b");

    private PromptSanitizer() {
    }

    public static String redact(String value) {
        String result = value == null ? "" : value;
        result = PRIVATE_KEY.matcher(result).replaceAll("[REDACTED]");
        result = AUTHORIZATION.matcher(result).replaceAll("Authorization: [REDACTED]");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=[REDACTED]");
        return GITHUB_TOKEN.matcher(result).replaceAll("[REDACTED]");
    }

    public static String truncate(String value, int maxBytes, int maxCodePoints) {
        String source = value == null ? "" : value;
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        int points = 0;
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            int nextBytes = bytes(next);
            if (bytes + nextBytes > maxBytes || points + 1 > maxCodePoints) {
                break;
            }
            result.append(next);
            bytes += nextBytes;
            points++;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    public static int bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
    }
}
