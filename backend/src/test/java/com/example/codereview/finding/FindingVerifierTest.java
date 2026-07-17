package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FindingVerifierTest {

    private final FindingVerifier verifier = new FindingVerifier();

    @Test
    void rejectsCandidatesWithConflictingVerifierEvidence() {
        FindingCandidate candidate = new FindingCandidate(
                FindingSeverity.HIGH, "security.sql", "SQL", "description", "src/Repo.java", 7, 7,
                "find", "sql", "rules-v1", List.of(
                        FindingEvidence.create(EvidenceType.STATIC_ANALYZER, "tool-v1", "src/Repo.java", 7, 7, "query", 1),
                        FindingEvidence.create(EvidenceType.VERIFIER, "verifier-v1", "src/Repo.java", 7, 7,
                                "not reproducible", 0.1)));
        DeduplicatedFinding deduplicated = new DeduplicatedFinding(candidate, "a".repeat(64), 1);

        VerifiedFinding result = verifier.verify(deduplicated);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("conflicting verifier evidence");
    }
}
