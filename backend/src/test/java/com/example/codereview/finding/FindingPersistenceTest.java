package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class FindingPersistenceTest {

    @Autowired
    private FindingRepository findings;

    @Autowired
    private FindingEvidenceRepository evidenceRepository;

    @Test
    void persistsVersionedEvidenceLocationScoreExcerptAndHash() {
        Finding finding = findings.saveAndFlush(new Finding(
                42L,
                FindingSeverity.HIGH,
                "security.sql-injection",
                "SQL injection",
                "Untrusted input reaches a query",
                "src/UserRepository.java",
                21,
                21,
                "findByName",
                "candidate"));
        FindingEvidence evidence = FindingEvidence.create(
                EvidenceType.STATIC_ANALYZER,
                "semgrep-rules-2026.07",
                "src/UserRepository.java",
                21,
                21,
                "statement.execute(userInput)",
                0.92);

        FindingEvidenceEntity stored = evidenceRepository.saveAndFlush(
                FindingEvidenceEntity.from(finding.getId(), evidence));

        assertThat(evidenceRepository.findById(stored.getId())).get().satisfies(actual -> {
            assertThat(actual.getEvidenceType()).isEqualTo(EvidenceType.STATIC_ANALYZER);
            assertThat(actual.getSourceVersion()).isEqualTo("semgrep-rules-2026.07");
            assertThat(actual.getFilePath()).isEqualTo("src/UserRepository.java");
            assertThat(actual.getLineStart()).isEqualTo(21);
            assertThat(actual.getLineEnd()).isEqualTo(21);
            assertThat(actual.getExcerpt()).isEqualTo("statement.execute(userInput)");
            assertThat(actual.getScore()).isEqualTo(0.92);
            assertThat(actual.getContentHash()).isEqualTo(evidence.contentHash());
        });
    }
}
