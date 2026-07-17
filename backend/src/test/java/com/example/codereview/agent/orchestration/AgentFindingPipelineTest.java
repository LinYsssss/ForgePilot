package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingConfidenceService;
import com.example.codereview.finding.FindingDeduplicator;
import com.example.codereview.finding.FindingEvidence;
import com.example.codereview.finding.FindingSeverity;
import com.example.codereview.finding.FindingVerifier;
import com.example.codereview.finding.GateDecisionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentFindingPipelineTest {

    private final AgentFindingPipeline pipeline = new AgentFindingPipeline(
            new FindingDeduplicator(), new FindingVerifier(),
            new GateDecisionService(new FindingConfidenceService(), 0.70)
    );

    @Test
    void modelOnlyHighSeverityClaimCannotBlock() {
        FindingCandidate candidate = candidate(List.of(
                FindingEvidence.create(EvidenceType.MODEL, "head", "src/Main.java", 10, 10,
                        "model claim", 1.0),
                FindingEvidence.create(EvidenceType.CODE_LOCATION, "head", "src/Main.java", 10, 10,
                        "line 10", 0.8)
        ));

        var evaluated = pipeline.evaluate(List.of(candidate), Set.of("src/Main.java"), "head");

        assertThat(evaluated).singleElement().satisfies(result -> {
            assertThat(result.accepted()).isTrue();
            assertThat(result.blocking()).isFalse();
        });
    }

    @Test
    void independentStaticKnowledgeAndLocationEvidenceCanBlock() {
        FindingCandidate candidate = candidate(List.of(
                FindingEvidence.create(EvidenceType.STATIC_ANALYZER, "head", "src/Main.java", 10, 10,
                        "rule", 1.0),
                FindingEvidence.create(EvidenceType.CODE_LOCATION, "head", "src/Main.java", 10, 10,
                        "line", 1.0),
                FindingEvidence.create(EvidenceType.KNOWLEDGE, "head", null, null, null,
                        "knowledge", 1.0)
        ));

        assertThat(pipeline.evaluate(List.of(candidate), Set.of("src/Main.java"), "head"))
                .singleElement().extracting(AgentFindingPipeline.EvaluatedFinding::blocking)
                .isEqualTo(true);
    }

    @Test
    void staleOrCrossHeadEvidenceCannotBlock() {
        FindingCandidate candidate = candidate(List.of(
                FindingEvidence.create(EvidenceType.STATIC_ANALYZER, "old-head", "src/Main.java", 10, 10,
                        "rule", 1.0),
                FindingEvidence.create(EvidenceType.CODE_LOCATION, "old-head", "src/Main.java", 10, 10,
                        "line", 1.0),
                FindingEvidence.create(EvidenceType.KNOWLEDGE, "old-head", null, null, null,
                        "knowledge", 1.0)
        ));

        assertThat(pipeline.evaluate(List.of(candidate), Set.of("src/Main.java"), "head"))
                .singleElement().extracting(AgentFindingPipeline.EvaluatedFinding::blocking)
                .isEqualTo(false);
    }

    private FindingCandidate candidate(List<FindingEvidence> evidence) {
        return new FindingCandidate(
                FindingSeverity.HIGH, "security", "unsafe", "description",
                "src/Main.java", 10, 10, "run", "RULE", "head", evidence
        );
    }
}
