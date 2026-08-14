package com.example.codereview.finding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 一条人工反馈:对某次 Agent Run 审查结果的裁定(误报/确认/漏报),r8 回灌语料的原始输入。
 *
 * <p>幂等契约:同 reporter 对同一 finding 的同类反馈只保留一条,重复提交覆盖旧内容
 * (upsert 取最新,由 {@link ReviewFeedbackService} 判定);MISS_REPORT 没有 finding,
 * 幂等键退化为 run+reporter+path+line。两种键在生产库各有唯一索引兜底并发竞态——
 * MISS_REPORT 的键含可空 line,JPA 注解表达不了 coalesce 表达式索引,
 * 见 V28 迁移的 {@code uq_review_feedback_miss_location}。
 *
 * <p>{@code createdAt} 的语义是「当前内容的提交时间」:upsert 覆盖旧内容时同步刷新,
 * 这样 {@code /api/feedback/export?since=} 的增量导出不会漏掉后来被更新过的旧记录。
 */
@Entity
@Table(name = "review_feedback",
        indexes = @Index(name = "idx_review_feedback_run", columnList = "agent_run_id,id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uq_review_feedback_finding_reporter_type",
                columnNames = {"finding_id", "reporter_id", "feedback_type"}))
public class ReviewFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_run_id", nullable = false, updatable = false)
    private Long agentRunId;

    /** 误报/确认挂靠的 finding;MISS_REPORT 恒为 null(组合校验在 service,库层另有 check 兜底)。 */
    @Column(name = "finding_id", updatable = false)
    private Long findingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 32, updatable = false)
    private ReviewFeedbackType type;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(length = 160)
    private String category;

    /** 上限 2000 与请求校验(@Size)一致:超限在入口即 400,不会走到这里被数据库拒掉变成 500。 */
    @Column(nullable = false, length = 2000)
    private String note;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewFeedback() {
    }

    public ReviewFeedback(Long agentRunId, Long findingId, ReviewFeedbackType type,
                          String filePath, Integer lineNumber, String category, String note, Long reporterId) {
        this.agentRunId = agentRunId;
        this.findingId = findingId;
        this.type = type;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.category = category;
        this.note = normalizeNote(note);
        this.reporterId = reporterId;
        this.createdAt = Instant.now();
    }

    /** upsert 覆盖:身份字段(run/finding/type/reporter)不变,内容整体换新并刷新提交时间。 */
    public void replaceContent(String filePath, Integer lineNumber, String category, String note) {
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.category = category;
        this.note = normalizeNote(note);
        this.createdAt = Instant.now();
    }

    /** note 列 not null:空反馈按空串落库,导出侧无需区分 null 与空。 */
    private static String normalizeNote(String note) {
        return note == null ? "" : note.strip();
    }

    public Long getId() {
        return id;
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public Long getFindingId() {
        return findingId;
    }

    public ReviewFeedbackType getType() {
        return type;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public String getCategory() {
        return category;
    }

    public String getNote() {
        return note;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
