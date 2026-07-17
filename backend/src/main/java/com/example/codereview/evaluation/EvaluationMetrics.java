package com.example.codereview.evaluation;

import java.util.ArrayList;
import java.util.List;

public record EvaluationMetrics(double precision, double recall, double f1, double highRiskRecall,
                                double falsePositiveRate, double locationAccuracy, double patchApplyRate,
                                double patchBuildRate, double patchTestRate, double averageDurationMs,
                                double totalCost, QualityGate gate) {
    public static EvaluationMetrics calculate(Input i) {
        double precision = ratio(i.truePositives, i.truePositives + i.falsePositives);
        double recall = ratio(i.truePositives, i.truePositives + i.falseNegatives);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double highRisk = ratio(i.highRiskTruePositives, i.highRiskTruePositives + i.highRiskFalseNegatives);
        double fpr = ratio(i.falsePositives, i.falsePositives + i.trueNegatives);
        double location = ratio(i.correctLocations, i.locatedFindings);
        double apply = ratio(i.patchApplied, i.repairableCases);
        double build = ratio(i.patchBuildPassed, i.patchApplied);
        double test = ratio(i.patchTestPassed, i.patchApplied);
        double duration = ratio(i.totalDurationMs, i.caseCount);
        List<String> failures = new ArrayList<>();
        if (recall < 0.80) failures.add("recall < 0.80");
        if (precision < 0.70) failures.add("precision < 0.70");
        if (location < 0.90) failures.add("location accuracy < 0.90");
        if (apply < 0.70) failures.add("patch application < 0.70");
        return new EvaluationMetrics(precision, recall, f1, highRisk, fpr, location, apply, build, test,
                duration, i.totalCost, new QualityGate(failures.isEmpty(), List.copyOf(failures)));
    }
    private static double ratio(long numerator, long denominator) { return denominator <= 0 ? 0 : numerator / (double) denominator; }
    public record QualityGate(boolean passed, List<String> failures) {}
    public record Input(long truePositives, long falsePositives, long falseNegatives, long trueNegatives,
                        long highRiskTruePositives, long highRiskFalseNegatives,
                        long correctLocations, long locatedFindings, long repairableCases, long patchApplied,
                        long patchBuildPassed, long patchTestPassed, long totalDurationMs, long caseCount,
                        double totalCost) {}
}
