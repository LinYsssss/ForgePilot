package com.example.codereview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvaluationMetricsTest {
    @Test
    void calculatesKnownConfusionMatrixPatchAndCostMetrics() {
        EvaluationMetrics metrics = EvaluationMetrics.calculate(new EvaluationMetrics.Input(
                8, 2, 2, 18, 4, 1, 9, 10, 7, 6, 5, 4, 120_000, 10, 1.25));
        assertThat(metrics.precision()).isEqualTo(0.8);
        assertThat(metrics.recall()).isEqualTo(0.8);
        assertThat(metrics.f1()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(metrics.highRiskRecall()).isEqualTo(0.8);
        assertThat(metrics.falsePositiveRate()).isEqualTo(0.1);
        assertThat(metrics.locationAccuracy()).isEqualTo(0.9);
        assertThat(metrics.patchApplyRate()).isEqualTo(6d / 7d);
        assertThat(metrics.averageDurationMs()).isEqualTo(12_000);
        assertThat(metrics.totalCost()).isEqualTo(1.25);
        assertThat(metrics.gate().passed()).isTrue();
    }

    @Test
    void failsInitialQualityGateOnRecallPrecisionLocationOrPatchRegression() {
        EvaluationMetrics metrics = EvaluationMetrics.calculate(new EvaluationMetrics.Input(
                6, 4, 4, 10, 2, 2, 7, 10, 10, 6, 6, 6, 10_000, 10, 0.5));
        assertThat(metrics.gate().passed()).isFalse();
        assertThat(metrics.gate().failures()).contains("recall < 0.80", "precision < 0.70",
                "location accuracy < 0.90", "patch application < 0.70");
    }
}
