package com.example.codereview.finding;

public record VerifiedFinding(DeduplicatedFinding finding, boolean accepted, String rejectionReason) {
    public VerifiedFinding {
        if (finding == null || !accepted && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("verified finding is invalid");
        }
        if (accepted) {
            rejectionReason = null;
        }
    }
}
