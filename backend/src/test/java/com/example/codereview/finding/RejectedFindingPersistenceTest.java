package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RejectedFindingPersistenceTest {

    @Autowired
    private FindingRepository findings;

    @Test
    void persistsRejectedCandidateFingerprintAndReason() {
        Finding finding = new Finding(
                42L, FindingSeverity.HIGH, "security.sql", "SQL", "description",
                "src/Repo.java", 7, 7, "find", "candidate");
        finding.applyVerification("b".repeat(64), false, "conflicting verifier evidence");

        Finding stored = findings.saveAndFlush(finding);

        assertThat(findings.findById(stored.getId())).get().satisfies(actual -> {
            assertThat(actual.getStatus()).isEqualTo("rejected");
            assertThat(actual.getFingerprint()).isEqualTo("b".repeat(64));
            assertThat(actual.getRejectionReason()).isEqualTo("conflicting verifier evidence");
        });
    }
}
