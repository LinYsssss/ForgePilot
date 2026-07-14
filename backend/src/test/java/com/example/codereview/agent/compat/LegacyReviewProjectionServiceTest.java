package com.example.codereview.agent.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.report.ReviewReport;
import com.example.codereview.report.ReviewReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("dev")
@Import(LegacyReviewProjectionService.class)
class LegacyReviewProjectionServiceTest {

    @Autowired
    private LegacyReviewProjectionService projection;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private ReviewReportRepository reports;

    @Test
    void projectsCompletedRunIntoOneLegacyReport() {
        Long runId = seedRun(42L, AgentRunStatus.COMPLETED, "abc123def");

        ReviewReport report = projection.project(runId);

        assertThat(report.getId()).isNotNull();
        assertThat(report.getAgentRunId()).isEqualTo(runId);
        assertThat(report.getTaskId()).isNull();
        assertThat(report.getProjectId()).isEqualTo(42L);
        assertThat(report.getCommitId()).isEqualTo("abc123def");
        assertThat(report.getOverallRisk()).isEqualTo("NONE");
        assertThat(report.getIssueCount()).isZero();
        assertThat(reports.findByProjectIdOrderByCreatedAtDesc(42L)).hasSize(1);
    }

    @Test
    void reProjectionIsIdempotent() {
        Long runId = seedRun(7L, AgentRunStatus.COMPLETED, "sha-1");

        ReviewReport first = projection.project(runId);
        ReviewReport second = projection.project(runId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(reports.findByProjectIdOrderByCreatedAtDesc(7L)).hasSize(1);
    }

    @Test
    void rejectsRunThatIsNotCompleted() {
        Long runId = seedRun(9L, AgentRunStatus.FAILED, "sha-2");

        assertThatThrownBy(() -> projection.project(runId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已完成");
        assertThat(reports.findByAgentRunId(runId)).isEmpty();
    }

    @Test
    void rejectsUnknownRun() {
        assertThatThrownBy(() -> projection.project(999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void uniqueProjectionKeyIsEnforcedAtDatabaseLevel() {
        reports.saveAndFlush(ReviewReport.forAgentRun(555L, 1L, "sha", "NONE", 0, "first", null));

        assertThatThrownBy(() ->
                reports.saveAndFlush(ReviewReport.forAgentRun(555L, 1L, "sha", "NONE", 0, "dup", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long seedRun(Long projectId, AgentRunStatus status, String headSha) {
        AgentRun run = new AgentRun(projectId, 1L, null, "trigger-" + projectId + "-" + status, headSha);
        if (status != AgentRunStatus.RECEIVED) {
            run.advanceTo(status, 1);
        }
        return runs.saveAndFlush(run).getId();
    }
}
