package com.example.codereview.evaluation;

import java.util.ArrayList;
import java.util.List;

public record RuntimeComparisonReport(String baselineRuntime, String candidateRuntime,
                                      EvaluationMetrics baseline, EvaluationMetrics candidate,
                                      EvaluationSafetyMetrics candidateSafety,
                                      List<String> regressions, boolean passed) {
    public static RuntimeComparisonReport compare(EvaluationMetrics baseline,
                                                  EvaluationMetrics candidate,
                                                  EvaluationSafetyMetrics safety) {
        List<String> regressions = new ArrayList<>();
        if (!candidate.gate().passed()) regressions.addAll(candidate.gate().failures());
        if (!safety.gate().passed()) regressions.addAll(safety.gate().failures());
        if (candidate.precision() < baseline.precision()) regressions.add("precision regressed");
        if (candidate.recall() < baseline.recall()) regressions.add("recall regressed");
        if (candidate.locationAccuracy() < baseline.locationAccuracy()) regressions.add("location accuracy regressed");
        if (candidate.patchApplyRate() < baseline.patchApplyRate()) regressions.add("Patch apply rate regressed");
        if (candidate.averageDurationMs() > baseline.averageDurationMs() * 1.50) regressions.add("latency increased by more than 50%");
        if (candidate.totalCost() > baseline.totalCost() * 1.50) regressions.add("cost increased by more than 50%");
        return new RuntimeComparisonReport("legacy", "langchain4j", baseline, candidate, safety,
                List.copyOf(regressions), regressions.isEmpty());
    }
}
