package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GateDecisionServiceTest {

    private final GateDecisionService service = new GateDecisionService(new FindingConfidenceService(), 0.75);

    @Test
    void blocksOnlyHighSeverityConfidentFindingsWithValidCodeLocation() {
        FindingCandidate high = candidate(FindingSeverity.HIGH, "src/UserRepository.java", 21);
        ConfidenceSignals strong = new ConfidenceSignals(1, 1, 1, 0, 0, 0, false);

        GateDecision decision = service.decide(high, strong);

        assertThat(decision.blocking()).isTrue();
        assertThat(decision.confidence()).isEqualTo(0.75);
        assertThat(decision.threshold()).isEqualTo(0.75);
        assertThat(decision.weightVersion()).isEqualTo("evidence-confidence-v1");
    }

    @Test
    void modelOnlyLowConfidenceMissingOrStaleLocationsNeverBlock() {
        assertThat(service.decide(candidate(FindingSeverity.HIGH, "src/App.java", 8),
                new ConfidenceSignals(0, 1, 0, 0, 0, 0, false)).blocking()).isFalse();
        assertThat(service.decide(candidate(FindingSeverity.MEDIUM, "src/App.java", 8),
                new ConfidenceSignals(1, 1, 1, 1, 1, 0, false)).blocking()).isFalse();
        assertThat(service.decide(candidate(FindingSeverity.HIGH, null, null),
                new ConfidenceSignals(1, 1, 1, 1, 1, 0, false)).blocking()).isFalse();
        assertThat(service.decide(candidate(FindingSeverity.HIGH, "src/App.java", 8),
                new ConfidenceSignals(1, 1, 1, 1, 1, 0, true)).blocking()).isFalse();
    }

    private static FindingCandidate candidate(FindingSeverity severity, String file, Integer line) {
        return new FindingCandidate(
                severity, "security.test", "Test finding", "description", file, line, line,
                null, "rule", "rules-v1", List.of());
    }
}
