package com.example.codereview.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record WorkbenchRequirement(Long requirementId, String code, String title, String priority,
                                       String status, Instant updatedAt) {
    }

    public record WorkbenchFinding(Long findingId, Long agentRunId, String severity, String title,
                                   String lifecycle, Instant createdAt) {
    }

    public record WorkbenchPullRequest(Long pullRequestId, Integer prNumber, String title,
                                       String reviewState, String headSha, Instant updatedAt) {
    }

    public record RiskSummary(long gatePass, long gateWarn, long gateBlock, long gateUnknown,
                              long activeHighCriticalFindings, long unresolvedCoverageWarnings,
                              long highRiskReviewReports) {
    }

    public record RecentActivity(String targetType, Long objectId, String label, String state,
                                 Instant occurredAt) {
    }

    public record WorkbenchResponse(Instant generatedAt, String role,
                                    List<WorkbenchRequirement> requirements,
                                    List<WorkbenchFinding> findings,
                                    List<WorkbenchPullRequest> pullRequests,
                                    String pullRequestQueueNote,
                                    RiskSummary riskSummary,
                                    List<RecentActivity> recentActivity) {
    }

    public record GateMetric(long pass, long warn, long block, long unknown) {
    }

    public record FindingMetric(long totalVerified, long active, long activeHighCritical,
                                long terminal, Double closureRate,
                                Map<String, Long> bySeverity, Map<String, Long> byLifecycle) {
    }

    public record CoverageMetric(long covered, long notFound, long atRisk, long excludedRecords) {
    }

    public record DevelopmentQuality(long sampleCount, GateMetric gates,
                                     FindingMetric findings, CoverageMetric coverage) {
    }

    public record RequirementMetric(long total, Map<String, Long> byStatus, long totalAcs,
                                    Double averageAcs, long checkedRequirements,
                                    Double checkCoverageRate) {
    }

    public record RequirementCheckMetric(long latestReports, Map<String, Long> itemsByDimension,
                                         Map<String, Long> itemsBySeverity, long excludedRecords) {
    }

    public record RequirementQuality(long sampleCount, RequirementMetric requirements,
                                     RequirementCheckMetric checks) {
    }

    public record DurationMetric(long sampleCount, Long averageMs, Long p50Ms, Long p95Ms,
                                 Long minMs, Long maxMs, boolean truncated) {
    }

    public record EfficiencyMetric(long sampleCount, DurationMetric interactiveReview,
                                   DurationMetric agentTurnaround,
                                   DurationMetric findingVerification) {
    }

    public record RequestTypeMetric(String requestType, long calls) {
    }

    public record AiMetric(long sampleCount, long calls, long successes, long failures,
                           Double successRate, long totalTokens, Long averageLatencyMs,
                           Long p95LatencyMs, List<RequestTypeMetric> byRequestType,
                           boolean truncated) {
    }

    public record MetricsResponse(String window, Instant from, Instant to, Instant generatedAt,
                                  DevelopmentQuality developmentQuality,
                                  RequirementQuality requirementQuality,
                                  EfficiencyMetric efficiency,
                                  AiMetric ai,
                                  long excludedRecords,
                                  boolean truncated) {
    }
}
