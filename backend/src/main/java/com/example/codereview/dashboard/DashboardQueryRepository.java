package com.example.codereview.dashboard;

import static com.example.codereview.dashboard.DashboardDtos.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardQueryRepository {

    private final JdbcTemplate jdbc;

    public DashboardQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkbenchRequirement> assignedRequirements(Long projectId, Long userId, int limit) {
        return jdbc.query("""
                select id, seq, title, priority, status, updated_at
                from requirement
                where project_id = ? and assignee_id = ? and status not in ('DONE', 'CANCELED')
                order by updated_at desc, id desc limit ?
                """, (rs, row) -> new WorkbenchRequirement(
                rs.getLong("id"), "REQ-" + rs.getLong("seq"), rs.getString("title"),
                rs.getString("priority"), rs.getString("status"), instant(rs, "updated_at")),
                projectId, userId, limit);
    }

    public List<WorkbenchFinding> assignedFindings(Long projectId, Long userId, int limit) {
        return jdbc.query("""
                select f.id, f.agent_run_id, f.severity, f.title, f.lifecycle_status, f.created_at
                from agent_finding f join agent_run r on r.id = f.agent_run_id
                where r.project_id = ? and f.assignee_id = ?
                  and f.lifecycle_status not in ('CLOSED', 'REJECTED')
                order by case f.severity when 'CRITICAL' then 0 when 'HIGH' then 1
                         when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end,
                         f.created_at desc, f.id desc limit ?
                """, (rs, row) -> new WorkbenchFinding(
                rs.getLong("id"), rs.getLong("agent_run_id"), rs.getString("severity"),
                rs.getString("title"), rs.getString("lifecycle_status"), instant(rs, "created_at")),
                projectId, userId, limit);
    }

    public List<WorkbenchPullRequest> pendingPullRequests(Long projectId, int limit) {
        return jdbc.query("""
                select id, pr_number, title, review_state, head_sha, updated_at
                from pull_request
                where project_id = ? and status = 'OPEN'
                  and review_state in ('PENDING', 'CHANGES_REQUESTED')
                order by updated_at desc, id desc limit ?
                """, (rs, row) -> new WorkbenchPullRequest(
                rs.getLong("id"), (Integer) rs.getObject("pr_number"), rs.getString("title"),
                rs.getString("review_state"), rs.getString("head_sha"), instant(rs, "updated_at")),
                projectId, limit);
    }

    public Map<String, Long> recentGateCounts(Long projectId) {
        return countMap("""
                select coalesce(cast(gate_verdict as varchar), 'UNKNOWN') metric_key, count(*) metric_count
                from (select gate_verdict from agent_run where project_id = ?
                      order by created_at desc limit 100) recent
                group by coalesce(cast(gate_verdict as varchar), 'UNKNOWN')
                """, projectId);
    }

    public long activeHighCriticalFindings(Long projectId) {
        return scalar("""
                select count(*) from agent_finding f join agent_run r on r.id = f.agent_run_id
                where r.project_id = ? and f.status = 'verified'
                  and f.severity in ('CRITICAL', 'HIGH')
                  and f.lifecycle_status not in ('CLOSED', 'REJECTED')
                """, projectId);
    }

    public List<String> recentCoverageJson(Long projectId) {
        return jdbc.queryForList("""
                select coverage_json from agent_run
                where project_id = ? and coverage_json is not null
                order by created_at desc limit 100
                """, String.class, projectId);
    }

    public long highRiskReports(Long projectId) {
        return scalar("""
                select count(*) from (select overall_risk from review_report
                  where project_id = ? order by created_at desc limit 100) recent
                where overall_risk = 'HIGH'
                """, projectId);
    }

    public List<RecentActivity> recentActivities(Long projectId, int sourceLimit) {
        return jdbc.query("""
                select target_type, object_id, label, state, occurred_at from (
                  select 'REQUIREMENT' target_type, id object_id, title label, cast(status as varchar) state, updated_at occurred_at
                    from requirement where project_id = ? order by updated_at desc limit ?
                ) r
                union all
                select target_type, object_id, label, state, occurred_at from (
                  select 'FINDING' target_type, f.id object_id, f.title label, cast(f.lifecycle_status as varchar) state,
                         coalesce(f.verified_at, f.created_at) occurred_at
                    from agent_finding f join agent_run ar on ar.id = f.agent_run_id
                    where ar.project_id = ? order by coalesce(f.verified_at, f.created_at) desc limit ?
                ) f
                union all
                select target_type, object_id, label, state, occurred_at from (
                  select 'PULL_REQUEST' target_type, id object_id, title label, cast(review_state as varchar) state, updated_at occurred_at
                    from pull_request where project_id = ? order by updated_at desc limit ?
                ) pr
                union all
                select target_type, object_id, label, state, occurred_at from (
                  select 'AGENT_RUN' target_type, id object_id, concat('Agent Run #', id) label,
                         cast(status as varchar) state, updated_at occurred_at
                    from agent_run where project_id = ? and status in ('COMPLETED','FAILED','CANCELED','TIMED_OUT')
                    order by updated_at desc limit ?
                ) a
                union all
                select target_type, object_id, label, state, occurred_at from (
                  select 'REVIEW_REPORT' target_type, id object_id, concat('审查报告 #', id) label,
                         cast(overall_risk as varchar) state, created_at occurred_at
                    from review_report where project_id = ? order by created_at desc limit ?
                ) p
                """, (rs, row) -> new RecentActivity(
                rs.getString("target_type"), rs.getLong("object_id"), rs.getString("label"),
                rs.getString("state"), instant(rs, "occurred_at")),
                projectId, sourceLimit, projectId, sourceLimit, projectId, sourceLimit, projectId, sourceLimit, projectId, sourceLimit);
    }

    public Map<String, Long> gateCounts(Long projectId, Instant from, Instant to) {
        return countMap("""
                select coalesce(cast(gate_verdict as varchar), 'UNKNOWN') metric_key, count(*) metric_count
                from agent_run where project_id = ? and created_at >= ? and created_at < ?
                group by coalesce(cast(gate_verdict as varchar), 'UNKNOWN')
                """, projectId, from, to);
    }

    public List<FindingRow> findings(Long projectId, Instant from, Instant to) {
        return jdbc.query("""
                select f.severity, f.lifecycle_status from agent_finding f
                join agent_run r on r.id = f.agent_run_id
                where r.project_id = ? and f.status = 'verified'
                  and f.created_at >= ? and f.created_at < ?
                """, (rs, row) -> new FindingRow(rs.getString(1), rs.getString(2)), projectId, from, to);
    }

    public List<String> coverageJson(Long projectId, Instant from, Instant to) {
        return jdbc.queryForList("""
                select coverage_json from agent_run where project_id = ? and coverage_json is not null
                  and created_at >= ? and created_at < ? order by created_at desc
                """, String.class, projectId, from, to);
    }

    public Map<String, Long> requirementStatusCounts(Long projectId, Instant from, Instant to) {
        return countMap("""
                select status metric_key, count(*) metric_count from requirement
                where project_id = ? and created_at >= ? and created_at < ? group by status
                """, projectId, from, to);
    }

    public RequirementAggregate requirementAggregate(Long projectId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                select count(distinct r.id) requirement_count, count(ac.id) ac_count
                from requirement r left join acceptance_criterion ac on ac.requirement_id = r.id
                where r.project_id = ? and r.created_at >= ? and r.created_at < ?
                """, (rs, row) -> new RequirementAggregate(rs.getLong(1), rs.getLong(2)), projectId, from, to);
    }

    public List<String> latestRequirementReports(Long projectId, Instant from, Instant to) {
        return jdbc.queryForList("""
                select q.report_json from requirement_quality_report q
                join requirement r on r.id = q.requirement_id
                where r.project_id = ? and q.created_at >= ? and q.created_at < ?
                  and q.round = (select max(q2.round) from requirement_quality_report q2
                                 where q2.requirement_id = q.requirement_id)
                """, String.class, projectId, from, to);
    }

    public List<Long> reviewDurations(Long projectId, Instant from, Instant to, int limit) {
        return durationQuery("""
                select started_at, finished_at from review_task
                where project_id = ? and started_at is not null and finished_at is not null
                  and finished_at >= ? and finished_at < ? order by finished_at desc limit ?
                """, from, to, limit, projectId);
    }

    public List<Long> agentDurations(Long projectId, Instant from, Instant to, int limit) {
        return durationQuery("""
                select created_at, updated_at from agent_run
                where project_id = ? and status in ('COMPLETED','FAILED','CANCELED','TIMED_OUT')
                  and updated_at >= ? and updated_at < ? order by updated_at desc limit ?
                """, from, to, limit, projectId);
    }

    public List<Long> findingVerificationDurations(Long projectId, Instant from, Instant to, int limit) {
        return durationQuery("""
                select f.created_at, f.verified_at from agent_finding f join agent_run r on r.id = f.agent_run_id
                where r.project_id = ? and f.verified_at is not null
                  and f.verified_at >= ? and f.verified_at < ? order by f.verified_at desc limit ?
                """, from, to, limit, projectId);
    }

    public List<AiRow> aiRows(Long projectId, Instant from, Instant to, int limit) {
        return jdbc.query("""
                select request_type, total_tokens, latency_ms, status from ai_call_log
                where project_id = ? and created_at >= ? and created_at < ?
                order by created_at desc limit ?
                """, (rs, row) -> new AiRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                projectId, from, to, limit);
    }

    private List<Long> durationQuery(String sql, Instant from, Instant to, int limit, Long projectId) {
        return jdbc.query(sql, (rs, row) -> Math.max(0L,
                instant(rs, 2).toEpochMilli() - instant(rs, 1).toEpochMilli()),
                projectId, from, to, limit);
    }

    private Map<String, Long> countMap(String sql, Object... args) {
        return jdbc.query(sql, rs -> {
            java.util.LinkedHashMap<String, Long> result = new java.util.LinkedHashMap<>();
            while (rs.next()) result.put(rs.getString("metric_key"), rs.getLong("metric_count"));
            return result;
        }, args);
    }

    private long scalar(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record FindingRow(String severity, String lifecycle) {
    }

    public record RequirementAggregate(long requirements, long acs) {
    }

    public record AiRow(String requestType, long totalTokens, long latencyMs, String status) {
    }
}
