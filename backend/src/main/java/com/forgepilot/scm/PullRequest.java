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
 * The authoritative snapshot of one pull request, as the provider reported it.
 *
 * <p>The webhook is only a signal: every column here comes from a fresh read of
 * the provider API, which is what makes a replay harmless. {@code source_revision}
 * and {@code source_updated_at} exist for ordering alone — an older delivery must
 * never roll base, head or the patches backwards — and neither may take part in
 * {@code review_input_fingerprint}, or event ordering would mint Review identities.
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

    /** Provider-reported current title; prompt input, never Review identity. */
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
     * The changed-file manifest with every patch, as JSONB (D015.7). Stored rather
     * than re-fetched at review time, because the fingerprint's inputs have to stay
     * reproducible from the database for the row to be a snapshot at all.
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

    /**
     * The recomputable mapping onto a project member. It stays null in batch 2:
     * computing it needs a lookup by {@code scm_external_user_id}, which {@code project}
     * exposes no facade for, and ArchUnit rule 4 forbids reaching into
     * {@code ProjectMemberRepository} from here. Nothing authorizes against it yet;
     * "is this my pull request" is decided by the external id (D010), and the
     * immutable author snapshot above is already stored.
     */
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

    /** Base, head, the manifest and the fingerprint move together or not at all. */
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
}
