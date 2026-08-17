package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.GateVerdict;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingDecisionEntity;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingLifecycle;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class RunGateVerdictServiceTest {

    private static final Long RUN_ID = 41L;

    @Mock
    private AgentRunRepository runs;
    @Mock
    private FindingRepository findings;
    @Mock
    private FindingDecisionRepository decisions;

    private RunGateVerdictService service;

    @BeforeEach
    void setUp() {
        service = new RunGateVerdictService(runs, findings, decisions, new ObjectMapper());
    }

    @Test
    void verifiedBlockingDecisionProducesBlock() {
        Finding finding = finding(1L, "verified", FindingSeverity.HIGH, FindingLifecycle.OPEN);
        when(decisions.findByFindingIdInOrderByIdAsc(any())).thenReturn(
                List.of(decision(1L, true)));

        RunGateVerdictService.GateEvaluation evaluation = evaluate(List.of(finding), null);

        assertThat(evaluation.verdict()).isEqualTo(GateVerdict.BLOCK);
        assertThat(evaluation.blockingFindings()).containsExactly(finding);
    }

    @Test
    void coverageNotFoundProducesWarn() {
        String coverage = """
                {"requirementId":1,"requirementCode":"REQ-1","requirementTitle":"title",
                 "coverage":[{"acId":"AC1","acText":"criterion","verdict":"NOT_FOUND",
                               "evidence":[],"rationale":"missing"}]}
                """;

        assertThat(evaluate(List.of(), coverage).verdict()).isEqualTo(GateVerdict.WARN);
    }

    @ParameterizedTest(name = "{0} {1} produces WARN")
    @MethodSource("unclosedVerifiedFindingCases")
    void unclosedVerifiedHighCriticalOrFixedProducesWarn(
            FindingSeverity severity, FindingLifecycle lifecycle) {
        Finding finding = finding(1L, "verified", severity, lifecycle);

        assertThat(evaluate(List.of(finding), null).verdict()).isEqualTo(GateVerdict.WARN);
    }

    static Stream<Arguments> unclosedVerifiedFindingCases() {
        return Stream.of(
                Arguments.of(FindingSeverity.HIGH, FindingLifecycle.OPEN),
                Arguments.of(FindingSeverity.CRITICAL, FindingLifecycle.CONFIRMED),
                Arguments.of(FindingSeverity.LOW, FindingLifecycle.FIXED)
        );
    }

    @Test
    void allVerifiedFindingsClosedProducesPass() {
        Finding finding = finding(1L, "verified", FindingSeverity.CRITICAL, FindingLifecycle.CLOSED);

        assertThat(evaluate(List.of(finding), null).verdict()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void candidateAndRejectedHighFindingsDoNotProduceWarn() {
        Finding candidate = finding(1L, "candidate", FindingSeverity.HIGH, FindingLifecycle.OPEN);
        Finding rejected = finding(2L, "rejected", FindingSeverity.CRITICAL, FindingLifecycle.OPEN);

        assertThat(evaluate(List.of(candidate, rejected), null).verdict()).isEqualTo(GateVerdict.PASS);
    }

    private RunGateVerdictService.GateEvaluation evaluate(List<Finding> currentFindings,
                                                            String coverageJson) {
        AgentRun run = new AgentRun(7L, 8L, 9L, "trigger-" + RUN_ID, "head");
        run.attachCoverage(coverageJson);
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(findings.findByAgentRunIdOrderByIdAsc(RUN_ID)).thenReturn(currentFindings);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return service.evaluateAndAttach(RUN_ID);
    }

    private Finding finding(Long id, String status, FindingSeverity severity, FindingLifecycle lifecycle) {
        Finding finding = mock(Finding.class);
        when(finding.getId()).thenReturn(id);
        when(finding.getStatus()).thenReturn(status);
        when(finding.getSeverity()).thenReturn(severity);
        when(finding.getLifecycle()).thenReturn(lifecycle);
        return finding;
    }

    private FindingDecisionEntity decision(Long findingId, boolean blocking) {
        FindingDecisionEntity decision = mock(FindingDecisionEntity.class);
        when(decision.getFindingId()).thenReturn(findingId);
        when(decision.getBlocking()).thenReturn(blocking);
        return decision;
    }
}