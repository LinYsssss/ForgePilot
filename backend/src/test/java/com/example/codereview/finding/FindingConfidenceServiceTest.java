package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FindingConfidenceServiceTest {

    private final FindingConfidenceService service = new FindingConfidenceService();

    @Test
    void appliesExactVersionedWeightsAndPersistsExplainableContributions() {
        ConfidenceResult result = service.calculate(new ConfidenceSignals(1, 1, 1, 1, 1, 0, false));

        assertThat(result.weightVersion()).isEqualTo("evidence-confidence-v1");
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.contributions()).extracting(ScoreContribution::contribution)
                .containsExactly(0.35, 0.20, 0.20, 0.15, 0.10);
        assertThat(result.contributions()).extracting(ScoreContribution::factor)
                .containsExactly(
                        ConfidenceFactor.TOOL,
                        ConfidenceFactor.REPRODUCIBLE_LOCATION,
                        ConfidenceFactor.KNOWLEDGE,
                        ConfidenceFactor.VERIFIER_AGREEMENT,
                        ConfidenceFactor.TEST_REPRODUCTION);
    }

    @Test
    void conflictsAndStaleLocationsReduceConfidenceAndClampToRange() {
        ConfidenceResult penalized = service.calculate(new ConfidenceSignals(1, 1, 1, 1, 1, 0.5, true));
        ConfidenceResult floor = service.calculate(new ConfidenceSignals(0, 0, 0, 0, 0, 1, true));

        assertThat(penalized.score()).isEqualTo(0.675);
        assertThat(penalized.contributions()).extracting(ScoreContribution::factor)
                .contains(ConfidenceFactor.CONFLICT, ConfidenceFactor.STALE_LOCATION);
        assertThat(floor.score()).isZero();
    }
}
