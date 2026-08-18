package com.example.codereview.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.dashboard.DashboardDtos.MetricsResponse;
import com.example.codereview.dashboard.DashboardDtos.WorkbenchPullRequest;
import com.example.codereview.dashboard.DashboardQueryRepository.AiRow;
import com.example.codereview.dashboard.DashboardQueryRepository.FindingRow;
import com.example.codereview.dashboard.DashboardQueryRepository.RequirementAggregate;
import com.example.codereview.member.ProjectRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private DashboardQueryRepository queries;
    private ProjectAuthorization authorization;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        queries = mock(DashboardQueryRepository.class);
        authorization = mock(ProjectAuthorization.class);
        service = new DashboardService(queries, authorization, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), 100);
        when(authorization.roleOf(10L, 20L)).thenReturn(java.util.Optional.of(ProjectRole.DEVELOPER));
        when(queries.recentGateCounts(10L)).thenReturn(Map.of());
        when(queries.recentCoverageJson(10L)).thenReturn(List.of());
        when(queries.recentActivities(10L, 6)).thenReturn(List.of());
    }

    @Test
    void developerWorkbenchDoesNotClaimPerPullRequestAssignment() {
        var response = service.workbench(10L, 20L, null);

        verify(authorization).requireRead(10L, 20L);
        assertThat(response.role()).isEqualTo("DEVELOPER");
        assertThat(response.pullRequests()).isEmpty();
        assertThat(response.pullRequestQueueNote()).contains("未配置逐条审查人");
    }

    @Test
    void reviewerGetsBoundedPendingPullRequests() {
        when(authorization.roleOf(10L, 20L)).thenReturn(java.util.Optional.of(ProjectRole.REVIEWER));
        when(queries.pendingPullRequests(10L, 12)).thenReturn(List.of(
                new WorkbenchPullRequest(1L, 7, "PR", "PENDING", "abc", NOW)));
        when(queries.recentActivities(10L, 12)).thenReturn(List.of());

        var response = service.workbench(10L, 20L, 99);

        verify(queries).pendingPullRequests(10L, 12);
        assertThat(response.pullRequests()).hasSize(1);
        assertThat(response.pullRequestQueueNote()).contains("并非逐 PR 指派");
    }

    @Test
    void metricsFailSoftOnMalformedJsonAndReportTruncation() {
        Instant from = NOW.minusSeconds(30L * 86400);
        when(queries.gateCounts(10L, from, NOW)).thenReturn(Map.of("PASS", 2L, "BLOCK", 1L));
        when(queries.findings(10L, from, NOW)).thenReturn(List.of(
                new FindingRow("HIGH", "OPEN"), new FindingRow("LOW", "CLOSED")));
        when(queries.coverageJson(10L, from, NOW)).thenReturn(List.of(
                "{\"coverage\":[{\"verdict\":\"COVERED\"},{\"verdict\":\"AT_RISK\"}]}", "not-json"));
        when(queries.requirementStatusCounts(10L, from, NOW)).thenReturn(Map.of("READY", 2L));
        when(queries.requirementAggregate(10L, from, NOW)).thenReturn(new RequirementAggregate(2, 5));
        when(queries.latestRequirementReports(10L, from, NOW)).thenReturn(List.of(
                "{\"dimensions\":[{\"dimension\":\"CLARITY\",\"items\":[{\"severity\":\"HIGH\"}]}]}", "{}"));
        ArrayList<Long> durations = new ArrayList<>();
        for (long i = 1; i <= 101; i++) durations.add(i);
        when(queries.reviewDurations(10L, from, NOW, 101)).thenReturn(durations);
        when(queries.agentDurations(10L, from, NOW, 101)).thenReturn(List.of(10L, 20L, 30L));
        when(queries.findingVerificationDurations(10L, from, NOW, 101)).thenReturn(List.of());
        when(queries.aiRows(10L, from, NOW, 101)).thenReturn(List.of(
                new AiRow("CHAT_REVIEW", 50, 100, "SUCCESS"),
                new AiRow("ASSISTANT", 20, 300, "FAILED")));

        MetricsResponse response = service.metrics(10L, 20L, "30d");

        assertThat(response.from()).isEqualTo(from);
        assertThat(response.developmentQuality().coverage().excludedRecords()).isEqualTo(1);
        assertThat(response.requirementQuality().checks().excludedRecords()).isEqualTo(1);
        assertThat(response.excludedRecords()).isEqualTo(2);
        assertThat(response.efficiency().interactiveReview().sampleCount()).isEqualTo(100);
        assertThat(response.efficiency().interactiveReview().truncated()).isTrue();
        assertThat(response.efficiency().interactiveReview().p95Ms()).isEqualTo(95);
        assertThat(response.ai().successRate()).isEqualTo(0.5);
        assertThat(response.ai().p95LatencyMs()).isEqualTo(300);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void rejectsUnknownMetricsWindow() {
        assertThatThrownBy(() -> service.metrics(10L, 20L, "365d"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }
}
