package com.example.codereview.finding;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 反馈闭环的对外形状。{@link FeedbackRecord} 同时是 REST 响应体与
 * {@code GET /api/feedback/export} 的 JSON Lines 行格式——r8 回灌工具按这些字段名
 * 逐行消费,字段名改动等于破坏契约,格式由 ReviewFeedbackControllerTest 钉死。
 */
public final class ReviewFeedbackDtos {

    private ReviewFeedbackDtos() {
    }

    /**
     * 提交请求。这里只做单字段校验(长度/正数);type 与字段的组合约束
     * (FINDING_* 必须带 findingId、MISS_REPORT 必须带 path 且不得带 findingId)
     * 统一在 {@link ReviewFeedbackService} 校验,避免两层重复各自漂移。
     * type 传入未知字符串时由 Jackson 反序列化失败直接落 400。
     */
    public record FeedbackRequest(
            @NotNull ReviewFeedbackType type,
            Long findingId,
            @Size(max = 1024) String path,
            @Positive Integer line,
            @Size(max = 160) String category,
            @Size(max = 2000) String note
    ) {
    }

    /** 一条反馈的完整视图;字段名即导出行的 JSON 键,createdAt 序列化为 ISO-8601 字符串。 */
    public record FeedbackRecord(
            Long id,
            Long runId,
            Long findingId,
            String type,
            String path,
            Integer line,
            String category,
            String note,
            Long reporter,
            Instant createdAt
    ) {
        public static FeedbackRecord from(ReviewFeedback feedback) {
            return new FeedbackRecord(
                    feedback.getId(),
                    feedback.getAgentRunId(),
                    feedback.getFindingId(),
                    feedback.getType().name(),
                    feedback.getFilePath(),
                    feedback.getLineNumber(),
                    feedback.getCategory(),
                    feedback.getNote(),
                    feedback.getReporterId(),
                    feedback.getCreatedAt()
            );
        }
    }
}
