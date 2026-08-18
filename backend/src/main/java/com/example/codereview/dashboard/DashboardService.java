package com.example.codereview.dashboard;

import static com.example.codereview.dashboard.DashboardDtos.*;

import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.dashboard.DashboardQueryRepository.AiRow;
import com.example.codereview.dashboard.DashboardQueryRepository.FindingRow;
import com.example.codereview.dashboard.DashboardQueryRepository.RequirementAggregate;
import com.example.codereview.member.ProjectRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final DashboardQueryRepository queries;
    private final ProjectAuthorization authorization;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxSamples;

    @Autowired
    public DashboardService(DashboardQueryRepository queries, ProjectAuthorization authorization,
                            ObjectMapper objectMapper,
                            @Value("${app.metrics.max-samples:5000}") int maxSamples) {
        this(queries, authorization, objectMapper, Clock.systemUTC(), maxSamples);
    }

    DashboardService(DashboardQueryRepository queries, ProjectAuthorization authorization,
                     ObjectMapper objectMapper, Clock clock, int maxSamples) {
        this.queries = queries;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxSamples = Math.max(100, maxSamples);
    }

    @Transactional(readOnly = true)
    public WorkbenchResponse workbench(Long projectId, Long userId, Integer requestedLimit) {
        authorization.requireRead(projectId, userId);
        int limit = sanitizeLimit(requestedLimit);
        ProjectRole role = authorization.roleOf(projectId, userId).orElseThrow();
        List<WorkbenchPullRequest> pullRequests = role == ProjectRole.DEVELOPER
                ? List.of() : queries.pendingPullRequests(projectId, limit);
        String prNote = role == ProjectRole.DEVELOPER
                ? "Pull Request 未配置逐条审查人；待审队列仅向 LEADER/REVIEWER 展示。"
                : "按项目角色与 PR reviewState 汇总，并非逐 PR 指派。";

        Map<String, Long> gates = queries.recentGateCounts(projectId);
        RiskSummary risk = new RiskSummary(
                count(gates, "PASS"), count(gates, "WARN"), count(gates, "BLOCK"), count(gates, "UNKNOWN"),
                queries.activeHighCriticalFindings(projectId),
                coverageWarnings(queries.recentCoverageJson(projectId)),
                queries.highRiskReports(projectId));

        List<RecentActivity> activity = queries.recentActivities(projectId, limit).stream()
                .sorted(Comparator.comparing(RecentActivity::occurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
        return new WorkbenchResponse(Instant.now(clock), role.name(),
                queries.assignedRequirements(projectId, userId, limit),
                queries.assignedFindings(projectId, userId, limit), pullRequests, prNote, risk, activity);
    }

    @Transactional(readOnly = true)
    public MetricsResponse metrics(Long projectId, Long userId, String rawWindow) {
        authorization.requireRead(projectId, userId);
        MetricsWindow window = MetricsWindow.parse(rawWindow);
        Instant to = Instant.now(clock);
        Instant from = to.minus(window.duration());

        Map<String, Long> gates = queries.gateCounts(projectId, from, to);
        List<FindingRow> findingRows = queries.findings(projectId, from, to);
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        Map<String, Long> byLifecycle = new LinkedHashMap<>();
        long active = 0;
        long activeHighCritical = 0;
        long terminal = 0;
        for (FindingRow row : findingRows) {
            bySeverity.merge(row.severity(), 1L, Long::sum);
            byLifecycle.merge(row.lifecycle(), 1L, Long::sum);
            boolean isTerminal = "CLOSED".equals(row.lifecycle()) || "REJECTED".equals(row.lifecycle());
            if (isTerminal) terminal++;
            else {
                active++;
                if ("HIGH".equals(row.severity()) || "CRITICAL".equals(row.severity())) activeHighCritical++;
            }
        }
        FindingMetric findingMetric = new FindingMetric(findingRows.size(), active, activeHighCritical,
                terminal, rate(terminal, findingRows.size()), Map.copyOf(bySeverity), Map.copyOf(byLifecycle));

        CoverageAccumulator coverage = parseCoverage(queries.coverageJson(projectId, from, to));
        GateMetric gateMetric = new GateMetric(count(gates, "PASS"), count(gates, "WARN"),
                count(gates, "BLOCK"), count(gates, "UNKNOWN"));
        DevelopmentQuality development = new DevelopmentQuality(
                gates.values().stream().mapToLong(Long::longValue).sum(), gateMetric, findingMetric,
                new CoverageMetric(coverage.covered, coverage.notFound, coverage.atRisk, coverage.excluded));

        Map<String, Long> statuses = queries.requirementStatusCounts(projectId, from, to);
        RequirementAggregate aggregate = queries.requirementAggregate(projectId, from, to);
        RequirementReportAccumulator checks = parseRequirementReports(
                queries.latestRequirementReports(projectId, from, to));
        RequirementMetric requirementMetric = new RequirementMetric(aggregate.requirements(), Map.copyOf(statuses),
                aggregate.acs(), average(aggregate.acs(), aggregate.requirements()), checks.reports,
                rate(checks.reports, aggregate.requirements()));
        RequirementQuality requirementQuality = new RequirementQuality(aggregate.requirements(), requirementMetric,
                new RequirementCheckMetric(checks.reports, Map.copyOf(checks.byDimension),
                        Map.copyOf(checks.bySeverity), checks.excluded));

        SampledDurations reviews = sampled(queries.reviewDurations(projectId, from, to, maxSamples + 1));
        SampledDurations agents = sampled(queries.agentDurations(projectId, from, to, maxSamples + 1));
        SampledDurations verifications = sampled(queries.findingVerificationDurations(
                projectId, from, to, maxSamples + 1));
        EfficiencyMetric efficiency = new EfficiencyMetric(
                reviews.values.size() + agents.values.size() + verifications.values.size(),
                duration(reviews), duration(agents), duration(verifications));

        List<AiRow> aiRaw = queries.aiRows(projectId, from, to, maxSamples + 1);
        boolean aiTruncated = aiRaw.size() > maxSamples;
        List<AiRow> aiRows = aiTruncated ? aiRaw.subList(0, maxSamples) : aiRaw;
        long successes = aiRows.stream().filter(row -> "SUCCESS".equals(row.status())).count();
        long calls = aiRows.size();
        long totalTokens = aiRows.stream().mapToLong(AiRow::totalTokens).sum();
        List<Long> latencies = aiRows.stream().map(AiRow::latencyMs).sorted().toList();
        Map<String, Long> requestTypes = new LinkedHashMap<>();
        aiRows.forEach(row -> requestTypes.merge(row.requestType(), 1L, Long::sum));
        List<RequestTypeMetric> byRequestType = requestTypes.entrySet().stream()
                .map(entry -> new RequestTypeMetric(entry.getKey(), entry.getValue())).toList();
        AiMetric ai = new AiMetric(calls, calls, successes, calls - successes, rate(successes, calls),
                totalTokens, calls == 0 ? null : Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0)),
                percentile(latencies, .95), byRequestType, aiTruncated);

        long excluded = coverage.excluded + checks.excluded;
        boolean truncated = reviews.truncated || agents.truncated || verifications.truncated || aiTruncated;
        return new MetricsResponse(window.value(), from, to, to, development, requirementQuality,
                efficiency, ai, excluded, truncated);
    }

    static int sanitizeLimit(Integer requested) {
        if (requested == null) return 6;
        return Math.max(1, Math.min(12, requested));
    }

    private long coverageWarnings(List<String> jsonRows) {
        CoverageAccumulator accumulator = parseCoverage(jsonRows);
        return accumulator.notFound + accumulator.atRisk;
    }

    private CoverageAccumulator parseCoverage(List<String> jsonRows) {
        CoverageAccumulator result = new CoverageAccumulator();
        for (String json : jsonRows) {
            try {
                JsonNode coverage = objectMapper.readTree(json).path("coverage");
                if (!coverage.isArray()) throw new IllegalArgumentException("coverage missing");
                for (JsonNode item : coverage) {
                    switch (item.path("verdict").asText()) {
                        case "COVERED" -> result.covered++;
                        case "NOT_FOUND" -> result.notFound++;
                        case "AT_RISK" -> result.atRisk++;
                        default -> throw new IllegalArgumentException("unknown verdict");
                    }
                }
            } catch (Exception ex) {
                result.excluded++;
            }
        }
        return result;
    }

    private RequirementReportAccumulator parseRequirementReports(List<String> jsonRows) {
        RequirementReportAccumulator result = new RequirementReportAccumulator();
        for (String json : jsonRows) {
            try {
                JsonNode dimensions = objectMapper.readTree(json).path("dimensions");
                if (!dimensions.isArray()) throw new IllegalArgumentException("dimensions missing");
                for (JsonNode dimension : dimensions) {
                    String name = dimension.path("dimension").asText("");
                    if (name.isBlank() || !dimension.path("items").isArray()) {
                        throw new IllegalArgumentException("invalid dimension");
                    }
                    for (JsonNode item : dimension.path("items")) {
                        String severity = item.path("severity").asText("");
                        if (severity.isBlank()) throw new IllegalArgumentException("invalid severity");
                        result.byDimension.merge(name, 1L, Long::sum);
                        result.bySeverity.merge(severity, 1L, Long::sum);
                    }
                }
                result.reports++;
            } catch (Exception ex) {
                result.excluded++;
            }
        }
        return result;
    }

    private SampledDurations sampled(List<Long> raw) {
        boolean truncated = raw.size() > maxSamples;
        List<Long> values = new ArrayList<>(truncated ? raw.subList(0, maxSamples) : raw);
        values.sort(Long::compareTo);
        return new SampledDurations(List.copyOf(values), truncated);
    }

    private DurationMetric duration(SampledDurations sample) {
        if (sample.values.isEmpty()) return new DurationMetric(0, null, null, null, null, null, sample.truncated);
        long sum = sample.values.stream().mapToLong(Long::longValue).sum();
        return new DurationMetric(sample.values.size(), Math.round((double) sum / sample.values.size()),
                percentile(sample.values, .50), percentile(sample.values, .95), sample.values.get(0),
                sample.values.get(sample.values.size() - 1), sample.truncated);
    }

    private static Long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return null;
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static long count(Map<String, Long> values, String key) {
        return values.getOrDefault(key, 0L);
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round((numerator * 10000.0) / denominator) / 10000.0;
    }

    private static Double average(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round((numerator * 100.0) / denominator) / 100.0;
    }

    private static final class CoverageAccumulator {
        long covered;
        long notFound;
        long atRisk;
        long excluded;
    }

    private static final class RequirementReportAccumulator {
        long reports;
        long excluded;
        final Map<String, Long> byDimension = new LinkedHashMap<>();
        final Map<String, Long> bySeverity = new LinkedHashMap<>();
    }

    private record SampledDurations(List<Long> values, boolean truncated) {
    }
}
