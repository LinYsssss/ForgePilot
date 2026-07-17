package com.example.codereview.finding;

import java.util.List;

public record FindingCandidate(
        FindingSeverity severity,
        String category,
        String title,
        String description,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String symbol,
        String ruleId,
        String sourceVersion,
        List<FindingEvidence> evidence) {

    public FindingCandidate {
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        requireText(category, "category");
        requireText(title, "title");
        requireText(description, "description");
        requireText(sourceVersion, "sourceVersion");
        if (lineStart != null && lineStart <= 0 || lineEnd != null && lineEnd <= 0
                || lineStart != null && lineEnd != null && lineEnd < lineStart) {
            throw new IllegalArgumentException("line range is invalid");
        }
        if ((lineStart != null || lineEnd != null) && (filePath == null || filePath.isBlank())) {
            throw new IllegalArgumentException("filePath is required for line finding");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
