package com.example.codereview.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record FindingEvidence(
        EvidenceType evidenceType,
        String sourceVersion,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String excerpt,
        double score,
        String contentHash) {

    public static final int MAX_EXCERPT_CHARS = 2048;

    public FindingEvidence {
        if (evidenceType == null) {
            throw new IllegalArgumentException("evidenceType is required");
        }
        requireText(sourceVersion, "sourceVersion");
        if (lineStart != null && lineStart <= 0 || lineEnd != null && lineEnd <= 0
                || lineStart != null && lineEnd != null && lineEnd < lineStart) {
            throw new IllegalArgumentException("line range is invalid");
        }
        if ((lineStart != null || lineEnd != null) && (filePath == null || filePath.isBlank())) {
            throw new IllegalArgumentException("filePath is required for line evidence");
        }
        excerpt = excerpt == null ? "" : bound(excerpt);
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        if (contentHash == null || !contentHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("contentHash must be lowercase SHA-256");
        }
    }

    public static FindingEvidence create(
            EvidenceType type,
            String sourceVersion,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String rawExcerpt,
            double score) {
        String raw = rawExcerpt == null ? "" : rawExcerpt;
        return new FindingEvidence(type, sourceVersion, filePath, lineStart, lineEnd, raw, score, sha256(raw));
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String bound(String value) {
        if (value.length() <= MAX_EXCERPT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_EXCERPT_CHARS - 3) + "...";
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
