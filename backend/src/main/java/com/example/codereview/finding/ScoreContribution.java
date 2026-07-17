package com.example.codereview.finding;

public record ScoreContribution(ConfidenceFactor factor, double weight, double signal, double contribution) {
    public ScoreContribution {
        if (factor == null || !Double.isFinite(weight) || !Double.isFinite(signal) || !Double.isFinite(contribution)) {
            throw new IllegalArgumentException("score contribution is invalid");
        }
    }
}
