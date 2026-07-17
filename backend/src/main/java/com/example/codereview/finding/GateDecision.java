package com.example.codereview.finding;

import java.util.List;

public record GateDecision(
        boolean blocking,
        String reason,
        double confidence,
        double threshold,
        String weightVersion,
        List<ScoreContribution> contributions) {

    public GateDecision {
        if (reason == null || reason.isBlank() || weightVersion == null || weightVersion.isBlank()) {
            throw new IllegalArgumentException("reason and weightVersion are required");
        }
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }
}
