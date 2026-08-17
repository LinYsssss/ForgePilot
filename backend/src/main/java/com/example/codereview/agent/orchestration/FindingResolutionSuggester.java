package com.example.codereview.agent.orchestration;

import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Best-effort exact-fingerprint suggestions; lifecycle is never mutated here. */
@Service
public class FindingResolutionSuggester {

    private static final Logger log = LoggerFactory.getLogger(FindingResolutionSuggester.class);
    private final AgentScmContextRepository scmContexts;
    private final FindingRepository findings;

    public FindingResolutionSuggester(AgentScmContextRepository scmContexts, FindingRepository findings) {
        this.scmContexts = scmContexts;
        this.findings = findings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suggest(Long runId) {
        try {
            AgentScmContext current = scmContexts.findByAgentRunId(runId).orElse(null);
            if (current == null) {
                return;
            }
            Set<String> currentFingerprints = new HashSet<>(
                    findings.findVerifiedFingerprintsByAgentRunId(runId));
            List<Finding> historical = findings.findHistoricalActiveForPullRequest(
                    runId, current.getInstallationId(), current.getPullRequestNumber(), current.getCreatedAt());
            for (Finding finding : historical) {
                finding.suggestResolution(currentFingerprints.contains(finding.getFingerprint())
                        ? "STILL_PRESENT" : "RESOLVED_SUGGESTED");
            }
            if (!historical.isEmpty()) {
                findings.saveAll(historical);
            }
        } catch (RuntimeException failure) {
            log.debug("finding resolution suggestion skipped: runId={}", runId, failure);
        }
    }
}
