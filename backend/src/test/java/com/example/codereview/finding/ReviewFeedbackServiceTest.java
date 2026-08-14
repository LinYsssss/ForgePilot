package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.api.AgentRunService;
import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.common.security.SecurityAuditLogger.Outcome;
import com.example.codereview.finding.ReviewFeedbackDtos.FeedbackRecord;
import com.example.codereview.finding.ReviewFeedbackDtos.FeedbackRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 反馈提交的校验矩阵与幂等语义。矩阵约定(design D3):run 不存在 404、无项目权限 403、
 * type 与字段组合非法 400、重复提交 upsert 取最新而不是 409。
 * run 访问守卫复用 AgentRunService.requireOwnedRun(单一实现),这里只钉「守卫拒绝必须原样
 * 冒泡」;404/403 的真实 HTTP 状态由 ReviewFeedbackControllerTest 与授权矩阵端到端背书。
 */
class ReviewFeedbackServiceTest {

    private static final Long PROJECT_ID = 5L;
    private static final Long RUN_ID = 7L;
    private static final Long USER_ID = 11L;
    private static final Long FINDING_ID = 21L;

    private final ReviewFeedbackRepository feedbacks = mock(ReviewFeedbackRepository.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final SecurityAuditLogger audit = mock(SecurityAuditLogger.class);
    private ReviewFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new ReviewFeedbackService(feedbacks, agentRuns, findings, audit);
        when(agentRuns.requireOwnedRun(RUN_ID, USER_ID))
                .thenReturn(new AgentRun(PROJECT_ID, 2L, 3L, "trigger", "head"));
        when(feedbacks.save(any(ReviewFeedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 实体字段由 JPA 管理,单测里只能反射注入。 */
    private static void inject(Object entity, String field, Object value) {
        try {
            Field declared = entity.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(entity, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Finding findingOfRun(Long runId) {
        return new Finding(runId, FindingSeverity.HIGH, "security", "标题", "描述",
                "src/OrderService.java", 21, 28, "forceShip", "verified");
    }

    private static FeedbackRequest confirm(Long findingId, String note) {
        return new FeedbackRequest(ReviewFeedbackType.FINDING_CONFIRMED, findingId, null, null, null, note);
    }

    private static FeedbackRequest missReport(String path, Integer line, String note) {
        return new FeedbackRequest(ReviewFeedbackType.MISS_REPORT, null, path, line, "security", note);
    }

    private static void assertStatus(Throwable thrown, int httpStatus) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getHttpStatus()).isEqualTo(httpStatus);
    }

    // ------------------------------------------------------------ 矩阵:404 / 403(守卫拒绝原样冒泡)

    @Test
    void unknownRunIs404() {
        when(agentRuns.requireOwnedRun(RUN_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent 运行不存在"));
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, missReport("a.java", 1, "漏了"))), 404);
    }

    @Test
    void foreignProjectDecisionFromTheGuardPropagatesAs403() {
        when(agentRuns.requireOwnedRun(RUN_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.PROJECT_FORBIDDEN, "无权访问该项目"));
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, missReport("a.java", 1, "漏了"))), 403);
    }

    // ------------------------------------------------------------ 矩阵:组合非法 400

    @Test
    void findingFeedbackWithoutFindingIdIs400() {
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, confirm(null, "确认"))), 400);
    }

    @Test
    void missReportCarryingAFindingIdIs400() {
        FeedbackRequest request = new FeedbackRequest(
                ReviewFeedbackType.MISS_REPORT, FINDING_ID, "a.java", 1, null, "漏了");
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, request)), 400);
    }

    @Test
    void missReportWithoutPathIs400AndBlankPathCountsAsMissing() {
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, missReport(null, 1, "漏了"))), 400);
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, missReport("   ", 1, "漏了"))), 400);
    }

    /** 挂靠型反馈的 finding 必须真实属于本次 run;跨 run 的 id 按不存在处理,不泄露存在性。 */
    @Test
    void unknownOrForeignFindingIs404() {
        when(findings.findById(FINDING_ID)).thenReturn(Optional.empty());
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, confirm(FINDING_ID, "确认"))), 404);

        when(findings.findById(FINDING_ID)).thenReturn(Optional.of(findingOfRun(99L)));
        assertStatus(catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, confirm(FINDING_ID, "确认"))), 404);
    }

    /** 校验被拒时不得留下 SUCCESS 审计——审计流是回放依据,不能混入未发生的写入。 */
    @Test
    void rejectedSubmissionLeavesNoAuditRecord() {
        catchThrowable(
                () -> service.submit(RUN_ID, USER_ID, confirm(null, "确认")));
        verify(audit, never()).record(anyString(), any(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------ 写入与审计

    @Test
    void firstSubmissionPersistsAllFieldsAndAudits() {
        when(findings.findById(FINDING_ID)).thenReturn(Optional.of(findingOfRun(RUN_ID)));
        when(feedbacks.findByReporterIdAndFindingIdAndType(USER_ID, FINDING_ID, ReviewFeedbackType.FINDING_CONFIRMED))
                .thenReturn(Optional.empty());

        FeedbackRecord record = service.submit(RUN_ID, USER_ID, confirm(FINDING_ID, "确属越权"));

        ArgumentCaptor<ReviewFeedback> saved = ArgumentCaptor.forClass(ReviewFeedback.class);
        verify(feedbacks).save(saved.capture());
        assertThat(saved.getValue().getAgentRunId()).isEqualTo(RUN_ID);
        assertThat(saved.getValue().getFindingId()).isEqualTo(FINDING_ID);
        assertThat(saved.getValue().getType()).isEqualTo(ReviewFeedbackType.FINDING_CONFIRMED);
        assertThat(saved.getValue().getReporterId()).isEqualTo(USER_ID);
        assertThat(record.type()).isEqualTo("FINDING_CONFIRMED");
        assertThat(record.runId()).isEqualTo(RUN_ID);
        verify(audit).record(eq("REVIEW_FEEDBACK_SUBMIT"), eq(Outcome.SUCCESS),
                eq("reviewFeedback"), anyString(), eq("FINDING_CONFIRMED"));
    }

    // ------------------------------------------------------------ 矩阵:upsert 幂等

    @Test
    void resubmittingSameReporterFindingTypeOverwritesInsteadOfDuplicating() {
        when(findings.findById(FINDING_ID)).thenReturn(Optional.of(findingOfRun(RUN_ID)));
        ReviewFeedback existing = new ReviewFeedback(RUN_ID, FINDING_ID,
                ReviewFeedbackType.FINDING_CONFIRMED, null, null, null, "旧结论", USER_ID);
        inject(existing, "id", 31L);
        Instant staleTime = Instant.parse("2026-01-01T00:00:00Z");
        inject(existing, "createdAt", staleTime);
        when(feedbacks.findByReporterIdAndFindingIdAndType(USER_ID, FINDING_ID, ReviewFeedbackType.FINDING_CONFIRMED))
                .thenReturn(Optional.of(existing));

        FeedbackRecord record = service.submit(RUN_ID, USER_ID, confirm(FINDING_ID, "新结论"));

        // 覆盖同一条记录而不是新建;createdAt 刷新,增量导出(?since=)才不会漏掉这次更新
        verify(feedbacks).save(existing);
        assertThat(record.id()).isEqualTo(31L);
        assertThat(record.note()).isEqualTo("新结论");
        assertThat(record.createdAt()).isAfter(staleTime);
    }

    @Test
    void missReportUpsertsOnSamePathAndLineButNotAcrossLocations() {
        ReviewFeedback existing = new ReviewFeedback(RUN_ID, null, ReviewFeedbackType.MISS_REPORT,
                "src/A.java", 10, "security", "旧描述", USER_ID);
        inject(existing, "id", 41L);
        when(feedbacks.findByAgentRunIdAndReporterIdAndType(RUN_ID, USER_ID, ReviewFeedbackType.MISS_REPORT))
                .thenReturn(List.of(existing));

        // 同一位置(path+line 均相等):覆盖
        FeedbackRecord same = service.submit(RUN_ID, USER_ID, missReport("src/A.java", 10, "新描述"));
        assertThat(same.id()).isEqualTo(41L);
        assertThat(same.note()).isEqualTo("新描述");

        // 位置不同(行号变了):是另一处漏报,必须新建而不是覆盖
        FeedbackRecord other = service.submit(RUN_ID, USER_ID, missReport("src/A.java", 11, "另一处"));
        assertThat(other.id()).isNull();
        assertThat(other.line()).isEqualTo(11);
    }

    /** path 首尾空白归一:同一路径不因复制粘贴的空格生成两条记录。 */
    @Test
    void pathIsStrippedBeforeMatchingAndStoring() {
        ReviewFeedback existing = new ReviewFeedback(RUN_ID, null, ReviewFeedbackType.MISS_REPORT,
                "src/A.java", 10, null, "旧描述", USER_ID);
        inject(existing, "id", 51L);
        when(feedbacks.findByAgentRunIdAndReporterIdAndType(RUN_ID, USER_ID, ReviewFeedbackType.MISS_REPORT))
                .thenReturn(List.of(existing));

        FeedbackRecord record = service.submit(RUN_ID, USER_ID, missReport("  src/A.java  ", 10, "更新"));

        assertThat(record.id()).isEqualTo(51L);
        assertThat(record.path()).isEqualTo("src/A.java");
    }

    // ------------------------------------------------------------ 导出

    @Test
    void exportWithoutSinceIsFullAndWithSinceIsIncremental() {
        ReviewFeedback row = new ReviewFeedback(RUN_ID, null, ReviewFeedbackType.MISS_REPORT,
                "src/A.java", 10, "security", "漏了", USER_ID);
        inject(row, "id", 61L);
        when(feedbacks.findAllByOrderByIdAsc()).thenReturn(List.of(row));

        List<FeedbackRecord> full = service.export(null);
        assertThat(full).hasSize(1);
        assertThat(full.get(0).id()).isEqualTo(61L);
        assertThat(full.get(0).reporter()).isEqualTo(USER_ID);

        Instant since = Instant.parse("2026-08-01T00:00:00Z");
        when(feedbacks.findByCreatedAtGreaterThanEqualOrderByIdAsc(since)).thenReturn(List.of());
        assertThat(service.export(since)).isEmpty();
        verify(feedbacks).findByCreatedAtGreaterThanEqualOrderByIdAsc(since);
    }
}
