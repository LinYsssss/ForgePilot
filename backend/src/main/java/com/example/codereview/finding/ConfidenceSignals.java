package com.example.codereview.finding;

public record ConfidenceSignals(
        double tool,
        double reproducibleLocation,
        double knowledge,
        double verifierAgreement,
        double testReproduction,
        double conflict,
        boolean staleLocation) {

    public ConfidenceSignals {
        validate(tool, "tool");
        validate(reproducibleLocation, "reproducibleLocation");
        validate(knowledge, "knowledge");
        validate(verifierAgreement, "verifierAgreement");
        validate(testReproduction, "testReproduction");
        validate(conflict, "conflict");
    }

    private static void validate(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
