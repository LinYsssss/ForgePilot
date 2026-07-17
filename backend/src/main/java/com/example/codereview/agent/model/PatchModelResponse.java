package com.example.codereview.agent.model;

public record PatchModelResponse(String unifiedDiff) {
    public PatchModelResponse {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            throw new IllegalArgumentException("unifiedDiff is required");
        }
    }
}
