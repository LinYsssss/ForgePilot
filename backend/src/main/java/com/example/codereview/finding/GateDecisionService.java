package com.example.codereview.finding;

public final class GateDecisionService {

    private final FindingConfidenceService confidenceService;
    private final double threshold;

    public GateDecisionService(FindingConfidenceService confidenceService, double threshold) {
        if (confidenceService == null || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("confidenceService and threshold are required");
        }
        this.confidenceService = confidenceService;
        this.threshold = threshold;
    }

    public GateDecision decide(FindingCandidate candidate, ConfidenceSignals signals) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        ConfidenceResult confidence = confidenceService.calculate(signals);
        boolean highSeverity = candidate.severity() == FindingSeverity.HIGH
                || candidate.severity() == FindingSeverity.CRITICAL;
        boolean validLocation = candidate.filePath() != null && !candidate.filePath().isBlank()
                && candidate.lineStart() != null && candidate.lineStart() > 0
                && !signals.staleLocation();
        boolean blocking = highSeverity && validLocation && confidence.score() >= threshold;
        String reason = !highSeverity ? "severity below blocking level"
                : !validLocation ? "missing or stale code location"
                : confidence.score() < threshold ? "confidence below threshold"
                : "high-confidence verified finding";
        return new GateDecision(
                blocking,
                reason,
                confidence.score(),
                threshold,
                confidence.weightVersion(),
                confidence.contributions());
    }
}
