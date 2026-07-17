package com.example.codereview.finding;

import java.util.List;

public record ConfidenceResult(String weightVersion, double score, List<ScoreContribution> contributions) {
    public ConfidenceResult {
        if (weightVersion == null || weightVersion.isBlank()) {
            throw new IllegalArgumentException("weightVersion is required");
        }
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }
}
