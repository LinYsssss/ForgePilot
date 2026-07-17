package com.example.codereview.finding;

public record DeduplicatedFinding(FindingCandidate candidate, String fingerprint, int mergedCandidateCount) {
    public DeduplicatedFinding {
        if (candidate == null || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")
                || mergedCandidateCount <= 0) {
            throw new IllegalArgumentException("deduplicated finding is invalid");
        }
    }
}
