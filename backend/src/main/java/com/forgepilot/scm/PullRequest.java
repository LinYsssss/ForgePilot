package com.forgepilot.scm;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 一个 pull request 的权威快照，内容即 provider 所报告的状态。
 *
 * <p>Webhook 只是一个信号：这里的每一列都来自对 provider API 的一次全新读取，
 * 这正是重放无害的原因。{@code source_revision} 与 {@code source_updated_at}
 * 只为定序而存在——一次较旧的投递绝不能把 base、head 或 patch 往回滚——
 * 并且二者都不得参与 {@code review_input_fingerprint}，
 * 否则事件顺序就会凭空铸造出 Review 身份。
 */
@Entity
@Table(name = "pull_request")
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "external_number", nullable = false)
    private Integer externalNumber;

    /** provider 报告的当前标题；只作 Prompt 输入，绝不参与 Review 身份。 */
    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "base_sha", nullable = false, length = 64)
    private String baseSha;

    @Column(name = "head_sha", nullable = false, length = 64)
    private String headSha;

    @Column(name = "review_input_fingerprint", nullable = false, length = 64)
    private String reviewInputFingerprint;

    @Column(name = "source_revision", length = 128)
    private String sourceRevision;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    /**
     * 带全部 patch 的变更文件清单，以 JSONB 存储（D015.7）。是**存下来**而不是
     * 审查时再取，因为指纹的输入必须能从数据库里可复现地还原——否则这一行
     * 根本算不上一个快照。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_files", nullable = false)
    private String changedFiles;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "author_external_user_id", nullable = false, length = 128)
    private String authorExternalUserId;

    @Column(name = "author_username", nullable = false, length = 128)
    private String authorUsername;

    /** 由当前活动项目 SCM 绑定重算的成员映射；远端作者快照本身保持不变。 */
    @Column(name = "author_user_id")
    private Long authorUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PullRequest() {
    }

    PullRequest(Long projectId, Long repositoryId, Integer externalNumber, String title,
            String authorExternalUserId, String authorUsername) {
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.externalNumber = externalNumber;
        this.title = title;
        this.authorExternalUserId = authorExternalUserId;
        this.authorUsername = authorUsername;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getRepositoryId() {
        return repositoryId;
    }

    public Integer getExternalNumber() {
        return externalNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getReviewInputFingerprint() {
        return reviewInputFingerprint;
    }

    public String getSourceRevision() {
        return sourceRevision;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public String getAuthorExternalUserId() {
        return authorExternalUserId;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** base、head、清单与指纹要么一起变，要么都不变。 */
    void applySnapshot(String baseSha, String headSha, String title, String reviewInputFingerprint,
            String changedFiles, String sourceRevision, Instant sourceUpdatedAt) {
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.title = title;
        this.reviewInputFingerprint = reviewInputFingerprint;
        this.changedFiles = changedFiles;
        this.sourceRevision = sourceRevision;
        this.sourceUpdatedAt = sourceUpdatedAt;
    }

    void linkRequirement(Long requirementId) {
        this.requirementId = requirementId;
    }

    void mapAuthor(Long userId) {
        this.authorUserId = userId;
    }
}
