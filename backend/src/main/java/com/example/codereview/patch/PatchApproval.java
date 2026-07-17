package com.example.codereview.patch;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "approval_request", uniqueConstraints = @UniqueConstraint(
        name = "uq_approval_patch_approver", columnNames = {"patch_candidate_id", "approver_id"}))
public class PatchApproval {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "patch_candidate_id", nullable = false) private Long patchCandidateId;
    @Column(name = "approver_id", nullable = false) private Long approverId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private PatchApprovalDecision decision;
    @Column(name = "patch_hash", nullable = false, length = 64, updatable = false) private String patchHash;
    @Column(name = "head_sha", nullable = false, length = 80, updatable = false) private String headSha;
    @Column(columnDefinition = "text") private String comment;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected PatchApproval() {}
    public PatchApproval(Long patchCandidateId, Long approverId, PatchApprovalDecision decision,
                         String patchHash, String headSha, String comment) {
        this.patchCandidateId = patchCandidateId; this.approverId = approverId; this.decision = decision;
        this.patchHash = patchHash; this.headSha = headSha;
        this.comment = comment == null ? "" : comment.strip(); this.decidedAt = Instant.now(); this.createdAt = decidedAt;
    }
    public Long getId() { return id; } public Long getPatchCandidateId() { return patchCandidateId; }
    public Long getApproverId() { return approverId; } public PatchApprovalDecision getDecision() { return decision; }
    public String getPatchHash() { return patchHash; } public String getHeadSha() { return headSha; }
    public String getComment() { return comment; } public Instant getDecidedAt() { return decidedAt; }
}
