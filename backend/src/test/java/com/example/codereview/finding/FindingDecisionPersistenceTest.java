package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class FindingDecisionPersistenceTest {

    @Autowired
    private FindingDecisionRepository decisions;

    @Autowired
    private FindingScoreContributionRepository contributions;

    @Test
    void persistsWeightVersionThresholdAndEveryScoreContribution() {
        GateDecision decision = new GateDecisionService(new FindingConfidenceService(), 0.75).decide(
                new FindingCandidate(FindingSeverity.HIGH, "security.sql", "SQL", "description",
                        "src/Repo.java", 7, 7, null, "sql", "rules-v1", null),
                new ConfidenceSignals(1, 1, 1, 0, 0, 0, false));

        FindingDecisionEntity stored = decisions.saveAndFlush(FindingDecisionEntity.from(11L, decision));
        decision.contributions().forEach(contribution -> contributions.save(
                FindingScoreContributionEntity.from(stored.getId(), contribution)));
        contributions.flush();

        assertThat(decisions.findById(stored.getId())).get().satisfies(actual -> {
            assertThat(actual.getWeightVersion()).isEqualTo("evidence-confidence-v1");
            assertThat(actual.getThreshold()).isEqualTo(0.75);
            assertThat(actual.getConfidence()).isEqualTo(0.75);
            assertThat(actual.getBlocking()).isTrue();
        });
        assertThat(contributions.findByDecisionIdOrderByIdAsc(stored.getId()))
                .extracting(FindingScoreContributionEntity::getFactor)
                .containsExactly(
                        ConfidenceFactor.TOOL,
                        ConfidenceFactor.REPRODUCIBLE_LOCATION,
                        ConfidenceFactor.KNOWLEDGE,
                        ConfidenceFactor.VERIFIER_AGREEMENT,
                        ConfidenceFactor.TEST_REPRODUCTION);
    }
}
