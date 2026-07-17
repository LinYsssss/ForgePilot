package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FindingEvidenceTest {

    @Test
    void boundsExcerptAndHashesTheOriginalEvidenceContent() {
        String raw = "x".repeat(FindingEvidence.MAX_EXCERPT_CHARS + 100);

        FindingEvidence evidence = FindingEvidence.create(
                EvidenceType.STATIC_ANALYZER,
                "pmd-7.5.0",
                "src/OrderService.java",
                14,
                18,
                raw,
                0.85);

        assertThat(evidence.excerpt()).hasSize(FindingEvidence.MAX_EXCERPT_CHARS);
        assertThat(evidence.excerpt()).endsWith("...");
        assertThat(evidence.contentHash()).matches("[a-f0-9]{64}");
        assertThat(evidence.contentHash()).isNotEqualTo(FindingEvidence.sha256(evidence.excerpt()));
    }

    @Test
    void rejectsInvalidScoreAndLineRanges() {
        assertThatThrownBy(() -> FindingEvidence.create(
                EvidenceType.TEST_REPRODUCTION, "pytest-8", "test_order.py", 5, 4, "failure", 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line");
        assertThatThrownBy(() -> FindingEvidence.create(
                EvidenceType.KNOWLEDGE, "policy-v1", null, null, null, "rule", 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }
}
