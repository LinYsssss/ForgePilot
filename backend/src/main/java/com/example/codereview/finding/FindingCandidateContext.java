package com.example.codereview.finding;

public record FindingCandidateContext(FindingCandidate candidate, String lineNeighborhood) {
    public FindingCandidateContext {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        lineNeighborhood = lineNeighborhood == null ? "" : lineNeighborhood;
    }
}
