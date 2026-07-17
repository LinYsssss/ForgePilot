package com.example.codereview.finding;

public final class FindingVerifier {

    public VerifiedFinding verify(DeduplicatedFinding finding) {
        if (finding == null) {
            throw new IllegalArgumentException("finding is required");
        }
        if (finding.candidate().evidence().isEmpty()) {
            return new VerifiedFinding(finding, false, "no supporting evidence");
        }
        boolean conflict = finding.candidate().evidence().stream()
                .anyMatch(evidence -> evidence.evidenceType() == EvidenceType.VERIFIER && evidence.score() < 0.5);
        return conflict
                ? new VerifiedFinding(finding, false, "conflicting verifier evidence")
                : new VerifiedFinding(finding, true, null);
    }
}
