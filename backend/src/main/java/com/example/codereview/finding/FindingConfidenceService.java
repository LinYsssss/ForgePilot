package com.example.codereview.finding;

import java.util.ArrayList;
import java.util.List;

public final class FindingConfidenceService {

    public static final String WEIGHT_VERSION = "evidence-confidence-v1";
    private static final double CONFLICT_PENALTY = -0.25;
    private static final double STALE_LOCATION_PENALTY = -0.20;

    public ConfidenceResult calculate(ConfidenceSignals signals) {
        if (signals == null) {
            throw new IllegalArgumentException("signals are required");
        }
        List<ScoreContribution> contributions = new ArrayList<>();
        contributions.add(contribution(ConfidenceFactor.TOOL, 0.35, signals.tool()));
        contributions.add(contribution(
                ConfidenceFactor.REPRODUCIBLE_LOCATION, 0.20, signals.reproducibleLocation()));
        contributions.add(contribution(ConfidenceFactor.KNOWLEDGE, 0.20, signals.knowledge()));
        contributions.add(contribution(
                ConfidenceFactor.VERIFIER_AGREEMENT, 0.15, signals.verifierAgreement()));
        contributions.add(contribution(
                ConfidenceFactor.TEST_REPRODUCTION, 0.10, signals.testReproduction()));
        if (signals.conflict() > 0) {
            contributions.add(contribution(ConfidenceFactor.CONFLICT, CONFLICT_PENALTY, signals.conflict()));
        }
        if (signals.staleLocation()) {
            contributions.add(contribution(ConfidenceFactor.STALE_LOCATION, STALE_LOCATION_PENALTY, 1.0));
        }
        double raw = contributions.stream().mapToDouble(ScoreContribution::contribution).sum();
        return new ConfidenceResult(WEIGHT_VERSION, Math.max(0.0, Math.min(1.0, raw)), contributions);
    }

    private static ScoreContribution contribution(ConfidenceFactor factor, double weight, double signal) {
        return new ScoreContribution(factor, weight, signal, weight * signal);
    }
}
