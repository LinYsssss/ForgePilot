package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class FindingResolutionSuggesterTest {

    private static final Long RUN_ID = 41L;
    private static final Long INSTALLATION_ID = 17L;
    private static final int PULL_REQUEST_NUMBER = 12;
    private static final Instant CREATED_AT = Instant.parse("2026-08-17T08:00:00Z");
    private static final String PRESENT_FINGERPRINT = "a".repeat(64);
    private static final String MISSING_FINGERPRINT = "b".repeat(64);

    @Mock
    private AgentScmContextRepository scmContexts;
    @Mock
    private FindingRepository findings;
    @Mock
    private AgentScmContext currentContext;

    private FindingResolutionSuggester service;

    @BeforeEach
    void setUp() {
        service = new FindingResolutionSuggester(scmContexts, findings);
    }

    @Test
    void matchingFingerprintSuggestsStillPresentWithoutChangingLifecycle() {
        Finding historical = historicalFinding(PRESENT_FINGERPRINT);
        stubCurrentContext();
        when(findings.findVerifiedFingerprintsByAgentRunId(RUN_ID))
                .thenReturn(List.of(PRESENT_FINGERPRINT));
        when(findings.findHistoricalActiveForPullRequest(
                RUN_ID, INSTALLATION_ID, PULL_REQUEST_NUMBER, CREATED_AT))
                .thenReturn(List.of(historical));

        service.suggest(RUN_ID);

        assertThat(historical.getResolutionSuggestion()).isEqualTo("STILL_PRESENT");
        assertThat(historical.getLifecycle()).isEqualTo(com.example.codereview.finding.FindingLifecycle.OPEN);
        verify(findings).saveAll(List.of(historical));
    }

    @Test
    void missingFingerprintSuggestsResolvedWithoutChangingLifecycle() {
        Finding historical = historicalFinding(MISSING_FINGERPRINT);
        stubCurrentContext();
        when(findings.findVerifiedFingerprintsByAgentRunId(RUN_ID))
                .thenReturn(List.of(PRESENT_FINGERPRINT));
        when(findings.findHistoricalActiveForPullRequest(
                RUN_ID, INSTALLATION_ID, PULL_REQUEST_NUMBER, CREATED_AT))
                .thenReturn(List.of(historical));

        service.suggest(RUN_ID);

        assertThat(historical.getResolutionSuggestion()).isEqualTo("RESOLVED_SUGGESTED");
        assertThat(historical.getLifecycle()).isEqualTo(com.example.codereview.finding.FindingLifecycle.OPEN);
        verify(findings).saveAll(List.of(historical));
    }

    @Test
    void repositoryFailureIsSilentAndDoesNotPersistAnything() {
        when(scmContexts.findByAgentRunId(RUN_ID))
                .thenThrow(new IllegalStateException("repository unavailable"));

        assertThatCode(() -> service.suggest(RUN_ID)).doesNotThrowAnyException();

        verify(findings, never()).saveAll(any());
    }

    private void stubCurrentContext() {
        when(scmContexts.findByAgentRunId(RUN_ID)).thenReturn(Optional.of(currentContext));
        when(currentContext.getInstallationId()).thenReturn(INSTALLATION_ID);
        when(currentContext.getPullRequestNumber()).thenReturn(PULL_REQUEST_NUMBER);
        when(currentContext.getCreatedAt()).thenReturn(CREATED_AT);
    }

    private Finding historicalFinding(String fingerprint) {
        Finding finding = new Finding(
                99L, com.example.codereview.finding.FindingSeverity.HIGH, "security",
                "historical finding", "description", "src/App.java", 10, 12,
                "App.run", "verified");
        finding.applyVerification(fingerprint, true, null);
        return finding;
    }
}