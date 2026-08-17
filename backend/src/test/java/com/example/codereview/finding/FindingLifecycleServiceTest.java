package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.orchestration.AgentScmContext;
import com.example.codereview.agent.orchestration.AgentScmContextRepository;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.member.ProjectRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FindingLifecycleServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long FINDING_ID = 99L;
    private static final Long LEADER_ID = 10L;
    private static final Long REVIEWER_ID = 20L;
    private static final Long ASSIGNEE_ID = 30L;

    @Mock
    private FindingRepository findings;
    @Mock
    private FindingEvidenceRepository evidences;
    @Mock
    private FindingDecisionRepository decisions;
    @Mock
    private AgentRunRepository runs;
    @Mock
    private AgentScmContextRepository scmContexts;
    @Mock
    private AgentScmContext scmContext;
    @Mock
    private ProjectAuthorization authorization;

    private FindingLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new FindingLifecycleService(findings, evidences, decisions, runs, scmContexts, authorization);
    }

    @Test
    void reviewerCanConfirmVerifyAndClose() {
        Finding finding = finding();
        stubOwnedFinding(finding, PROJECT_ID);

        assertThat(service.transition(PROJECT_ID, REVIEWER_ID, FINDING_ID, "confirmed", null).lifecycle())
                .isEqualTo("CONFIRMED");
        finding.assignTo(ASSIGNEE_ID);
        finding.moveLifecycle(FindingLifecycle.IN_PROGRESS, ASSIGNEE_ID, null);
        finding.moveLifecycle(FindingLifecycle.FIXED, ASSIGNEE_ID, "abc123");

        assertThat(service.transition(PROJECT_ID, REVIEWER_ID, FINDING_ID, "VERIFIED", null).verifiedBy())
                .isEqualTo(REVIEWER_ID);
        assertThat(service.transition(PROJECT_ID, REVIEWER_ID, FINDING_ID, "CLOSED", null).lifecycle())
                .isEqualTo("CLOSED");
        verify(authorization, atLeastOnce()).requireRole(PROJECT_ID, REVIEWER_ID,
                Set.of(ProjectRole.LEADER, ProjectRole.REVIEWER));
    }

    @Test
    void assigneeCanStartAndMarkFixedButCannotPerformVerificationRejection() {
        Finding finding = finding();
        finding.moveLifecycle(FindingLifecycle.CONFIRMED, REVIEWER_ID, null);
        finding.assignTo(ASSIGNEE_ID);
        stubOwnedFinding(finding, PROJECT_ID);

        assertThat(service.transition(PROJECT_ID, ASSIGNEE_ID, FINDING_ID, "IN_PROGRESS", null).lifecycle())
                .isEqualTo("IN_PROGRESS");
        assertThat(service.transition(PROJECT_ID, ASSIGNEE_ID, FINDING_ID, "FIXED", " deadbeef ")
                .fixCommitSha()).isEqualTo("deadbeef");

        doThrow(new BusinessException(ErrorCode.PROJECT_FORBIDDEN))
                .when(authorization).requireRole(PROJECT_ID, ASSIGNEE_ID,
                        Set.of(ProjectRole.LEADER, ProjectRole.REVIEWER));

        assertThatThrownBy(() -> service.transition(
                PROJECT_ID, ASSIGNEE_ID, FINDING_ID, "IN_PROGRESS", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROJECT_FORBIDDEN));
    }

    @Test
    void nonAssigneeCannotStartOrMarkFixedUnlessLeader() {
        Finding finding = finding();
        finding.moveLifecycle(FindingLifecycle.CONFIRMED, REVIEWER_ID, null);
        finding.assignTo(ASSIGNEE_ID);
        stubOwnedFinding(finding, PROJECT_ID);
        doThrow(new BusinessException(ErrorCode.PROJECT_FORBIDDEN))
                .when(authorization).requireRole(PROJECT_ID, REVIEWER_ID, Set.of(ProjectRole.LEADER));

        assertThatThrownBy(() -> service.transition(
                PROJECT_ID, REVIEWER_ID, FINDING_ID, "IN_PROGRESS", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROJECT_FORBIDDEN));
    }

    @Test
    void assignmentIsLeaderOnlyAndAcceptsOwnerFallback() {
        Finding finding = finding();
        stubOwnedFinding(finding, PROJECT_ID);
        when(authorization.roleOf(PROJECT_ID, LEADER_ID)).thenReturn(Optional.of(ProjectRole.LEADER));

        assertThat(service.assign(PROJECT_ID, LEADER_ID, FINDING_ID, LEADER_ID).assigneeId())
                .isEqualTo(LEADER_ID);
        verify(authorization).requireRole(PROJECT_ID, LEADER_ID, Set.of(ProjectRole.LEADER));
    }

    @Test
    void assignmentRejectsNonMemberTarget() {
        Finding finding = finding();
        stubOwnedFinding(finding, PROJECT_ID);
        when(authorization.roleOf(PROJECT_ID, 404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(PROJECT_ID, LEADER_ID, FINDING_ID, 404L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void crossProjectFindingIsHiddenAsNotFound() {
        Finding finding = finding();
        stubOwnedFinding(finding, 2L);

        assertThatThrownBy(() -> service.transition(
                PROJECT_ID, REVIEWER_ID, FINDING_ID, "CONFIRMED", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FINDING_NOT_FOUND));
    }

    @Test
    void terminalFindingCannotTransitionOrBeAssigned() {
        Finding finding = finding();
        finding.moveLifecycle(FindingLifecycle.REJECTED, REVIEWER_ID, null);
        stubOwnedFinding(finding, PROJECT_ID);

        assertThatThrownBy(() -> service.transition(
                PROJECT_ID, REVIEWER_ID, FINDING_ID, "CONFIRMED", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FINDING_TRANSITION_ILLEGAL));
        assertThatThrownBy(() -> service.assign(PROJECT_ID, LEADER_ID, FINDING_ID, ASSIGNEE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FINDING_TRANSITION_ILLEGAL));
        verify(authorization, never()).roleOf(PROJECT_ID, ASSIGNEE_ID);
    }

    @Test
    void illegalNonTerminalTransitionReturnsConflictCode() {
        Finding finding = finding();
        stubOwnedFinding(finding, PROJECT_ID);

        assertThatThrownBy(() -> service.transition(
                PROJECT_ID, REVIEWER_ID, FINDING_ID, "FIXED", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FINDING_TRANSITION_ILLEGAL));
    }

    private void stubOwnedFinding(Finding finding, Long runProjectId) {
        Long runId = finding.getAgentRunId();
        AgentRun run = mock(AgentRun.class);
        lenient().when(run.getId()).thenReturn(runId);
        lenient().when(run.getProjectId()).thenReturn(runProjectId);
        when(findings.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        lenient().when(scmContexts.findByAgentRunId(runId)).thenReturn(Optional.of(scmContext));
        lenient().when(evidences.findByFindingIdOrderByIdAsc(finding.getId())).thenReturn(List.of());
        lenient().when(decisions.findByFindingIdOrderByIdAsc(finding.getId())).thenReturn(List.of());
    }

    private Finding finding() {
        return new Finding(7L, FindingSeverity.HIGH, "security", "title", "description",
                "src/App.java", 10, 12, "App.run", "verified");
    }
}
