package com.example.codereview.finding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "finding_evidence")
public class FindingEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 40)
    private EvidenceType evidenceType;

    @Column(name = "source_version", nullable = false, length = 160)
    private String sourceVersion;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "line_start")
    private Integer lineStart;

    @Column(name = "line_end")
    private Integer lineEnd;

    @Column(nullable = false, length = FindingEvidence.MAX_EXCERPT_CHARS)
    private String excerpt;

    @Column(nullable = false)
    private Double score;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FindingEvidenceEntity() {
    }

    public static FindingEvidenceEntity from(Long findingId, FindingEvidence evidence) {
        if (findingId == null || findingId <= 0) {
            throw new IllegalArgumentException("findingId must be positive");
        }
        FindingEvidenceEntity entity = new FindingEvidenceEntity();
        entity.findingId = findingId;
        entity.evidenceType = evidence.evidenceType();
        entity.sourceVersion = evidence.sourceVersion();
        entity.filePath = evidence.filePath();
        entity.lineStart = evidence.lineStart();
        entity.lineEnd = evidence.lineEnd();
        entity.excerpt = evidence.excerpt();
        entity.score = evidence.score();
        entity.contentHash = evidence.contentHash();
        return entity;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getFindingId() {
        return findingId;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLineStart() {
        return lineStart;
    }

    public Integer getLineEnd() {
        return lineEnd;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public Double getScore() {
        return score;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
