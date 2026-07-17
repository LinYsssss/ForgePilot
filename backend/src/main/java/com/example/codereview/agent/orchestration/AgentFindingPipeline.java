package com.example.codereview.agent.orchestration;

import com.example.codereview.finding.ConfidenceSignals;
import com.example.codereview.finding.DeduplicatedFinding;
import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingCandidateContext;
import com.example.codereview.finding.FindingDeduplicator;
import com.example.codereview.finding.FindingEvidence;
import com.example.codereview.finding.FindingVerifier;
import com.example.codereview.finding.GateDecision;
import com.example.codereview.finding.GateDecisionService;
import com.example.codereview.finding.VerifiedFinding;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentFindingPipeline {

    private final FindingDeduplicator deduplicator;
    private final FindingVerifier verifier;
    private final GateDecisionService gates;

    public AgentFindingPipeline(
            FindingDeduplicator deduplicator,
            FindingVerifier verifier,
            GateDecisionService gates
    ) {
        this.deduplicator = deduplicator;
        this.verifier = verifier;
        this.gates = gates;
    }

    public List<EvaluatedFinding> evaluate(
            List<FindingCandidate> candidates,
            Set<String> changedPaths,
            String headSha
    ) {
        List<DeduplicatedFinding> deduplicated = deduplicator.deduplicate(
                candidates.stream().map(candidate -> new FindingCandidateContext(
                        candidate, candidate.description()
                )).toList()
        );
        return deduplicated.stream().map(item -> evaluate(item, changedPaths, headSha)).toList();
    }

    private EvaluatedFinding evaluate(
            DeduplicatedFinding finding,
            Set<String> changedPaths,
            String headSha
    ) {
        FindingCandidate candidate = finding.candidate();
        boolean stale = candidate.filePath() == null
                || !changedPaths.contains(normalize(candidate.filePath()))
                || candidate.evidence().stream().anyMatch(evidence -> !headSha.equals(evidence.sourceVersion()));
        VerifiedFinding verified = verifier.verify(finding);
        List<FindingEvidence> normalizedEvidence = new ArrayList<>(candidate.evidence());
        if (!stale && candidate.filePath() != null && candidate.lineStart() != null
                && normalizedEvidence.stream().noneMatch(
                        evidence -> evidence.evidenceType() == EvidenceType.CODE_LOCATION)) {
            normalizedEvidence.add(FindingEvidence.create(
                    EvidenceType.CODE_LOCATION,
                    headSha,
                    candidate.filePath(),
                    candidate.lineStart(),
                    candidate.lineEnd(),
                    candidate.title(),
                    1.0
            ));
        }
        normalizedEvidence.add(FindingEvidence.create(
                EvidenceType.VERIFIER,
                headSha,
                candidate.filePath(),
                candidate.lineStart(),
                candidate.lineEnd(),
                verified.accepted() ? "independent verifier accepted" : verified.rejectionReason(),
                verified.accepted() ? 1.0 : 0.0
        ));
        FindingCandidate normalized = new FindingCandidate(
                candidate.severity(), candidate.category(), candidate.title(), candidate.description(),
                candidate.filePath(), candidate.lineStart(), candidate.lineEnd(), candidate.symbol(),
                candidate.ruleId(), candidate.sourceVersion(), normalizedEvidence
        );
        DeduplicatedFinding normalizedFinding = new DeduplicatedFinding(
                normalized, finding.fingerprint(), finding.mergedCandidateCount()
        );
        VerifiedFinding normalizedVerification = new VerifiedFinding(
                normalizedFinding, verified.accepted(), verified.rejectionReason()
        );
        ConfidenceSignals signals = signals(normalized, stale, verified.accepted());
        GateDecision decision = gates.decide(normalized, signals);
        return new EvaluatedFinding(normalizedFinding, normalizedVerification, decision);
    }

    private ConfidenceSignals signals(FindingCandidate candidate, boolean stale, boolean verifierAccepted) {
        return new ConfidenceSignals(
                max(candidate, EvidenceType.STATIC_ANALYZER),
                stale ? 0 : max(candidate, EvidenceType.CODE_LOCATION),
                max(candidate, EvidenceType.KNOWLEDGE),
                verifierAccepted ? 1.0 : 0.0,
                max(candidate, EvidenceType.TEST_REPRODUCTION),
                verifierAccepted ? 0.0 : 1.0,
                stale
        );
    }

    private double max(FindingCandidate candidate, EvidenceType type) {
        return candidate.evidence().stream()
                .filter(evidence -> evidence.evidenceType() == type)
                .mapToDouble(FindingEvidence::score)
                .max().orElse(0.0);
    }

    private String normalize(String path) {
        return path.replace('\\', '/').replaceAll("^\\./+", "");
    }

    public record EvaluatedFinding(
            DeduplicatedFinding deduplicated,
            VerifiedFinding verified,
            GateDecision decision
    ) {
        public boolean accepted() {
            return verified.accepted();
        }

        public boolean blocking() {
            return accepted() && decision.blocking();
        }
    }
}
