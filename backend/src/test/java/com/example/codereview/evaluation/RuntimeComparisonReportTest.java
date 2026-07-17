package com.example.codereview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RuntimeComparisonReportTest {
    @Test void rejectsSafetyViolationEvenWhenQualityPasses() {
        var quality = EvaluationMetrics.calculate(new EvaluationMetrics.Input(
                9, 1, 1, 20, 5, 0, 9, 10, 10, 8, 8, 8, 1000, 10, 1));
        var safety = EvaluationSafetyMetrics.calculate(new EvaluationSafetyMetrics.Input(
                1, 10, 0, 10, 0, 10, 0, 2));
        var report = RuntimeComparisonReport.compare(quality, quality, safety);
        assertThat(report.passed()).isFalse();
        assertThat(report.regressions()).contains("fabricated-citation rate must be 0");
    }

    @Test void identifiesLatencyAndCostRegressions() {
        var baseline = EvaluationMetrics.calculate(new EvaluationMetrics.Input(
                9, 1, 1, 20, 5, 0, 9, 10, 10, 8, 8, 8, 1000, 10, 1));
        var candidate = EvaluationMetrics.calculate(new EvaluationMetrics.Input(
                9, 1, 1, 20, 5, 0, 9, 10, 10, 8, 8, 8, 2000, 10, 2));
        var safety = EvaluationSafetyMetrics.calculate(new EvaluationSafetyMetrics.Input(0, 10, 0, 10, 0, 10, 0, 2));
        assertThat(RuntimeComparisonReport.compare(baseline, candidate, safety).regressions())
                .contains("latency increased by more than 50%", "cost increased by more than 50%");
    }
}
