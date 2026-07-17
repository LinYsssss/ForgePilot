package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FindingDeduplicatorTest {

    private final FindingDeduplicator deduplicator = new FindingDeduplicator();

    @Test
    void buildsStableFingerprintFromNormalizedPathSymbolAndNeighborhood() {
        FindingCandidate first = candidate("./SRC\\UserRepository.java", " findByName ", List.of(evidence("tool-v1", 0.9)));
        FindingCandidate second = candidate("src/UserRepository.java", "findByName", List.of(evidence("tool-v1", 0.9)));

        String a = deduplicator.fingerprint(first, "query.execute(input)\nreturn result");
        String b = deduplicator.fingerprint(second, "query.execute(input)\r\nreturn result");

        assertThat(a).isEqualTo(b).matches("[a-f0-9]{64}");
    }

    @Test
    void mergesSemanticDuplicatesWithoutCountingSameEvidenceSourceTwice() {
        FindingCandidate tool = candidate("src/UserRepository.java", "findByName", List.of(evidence("tool-v1", 0.9)));
        FindingCandidate duplicateTool = candidate("src/UserRepository.java", "findByName", List.of(evidence("tool-v1", 0.9)));
        FindingCandidate knowledge = candidate("src/UserRepository.java", "findByName", List.of(
                FindingEvidence.create(EvidenceType.KNOWLEDGE, "policy-v2", null, null, null, "Parameterized SQL required", 0.8)));

        List<DeduplicatedFinding> result = deduplicator.deduplicate(List.of(
                new FindingCandidateContext(tool, "execute(input)"),
                new FindingCandidateContext(duplicateTool, "execute(input)"),
                new FindingCandidateContext(knowledge, "execute(input)")));

        assertThat(result).singleElement().satisfies(merged -> {
            assertThat(merged.mergedCandidateCount()).isEqualTo(3);
            assertThat(merged.candidate().evidence()).hasSize(2);
            assertThat(merged.candidate().evidence()).extracting(FindingEvidence::sourceVersion)
                    .containsExactlyInAnyOrder("tool-v1", "policy-v2");
        });
    }

    private static FindingCandidate candidate(String file, String symbol, List<FindingEvidence> evidence) {
        return new FindingCandidate(
                FindingSeverity.HIGH, "security.sql", "SQL injection", "unsafe query",
                file, 20, 20, symbol, "sql", "rules-v1", evidence);
    }

    private static FindingEvidence evidence(String version, double score) {
        return FindingEvidence.create(
                EvidenceType.STATIC_ANALYZER, version, "src/UserRepository.java", 20, 20,
                "execute(input)", score);
    }
}
