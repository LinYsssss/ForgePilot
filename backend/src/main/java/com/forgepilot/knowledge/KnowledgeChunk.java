package com.forgepilot.knowledge;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One retrievable slice of a document, plus the audit of which embedding profile
 * produced its vector.
 *
 * <p>The {@code embedding} column is deliberately <strong>not mapped</strong>
 * (D015.4). Measured: mapping it as a String passes {@code ddl-auto=validate} and
 * then fails every write at runtime with 42804, which is the worst of the four
 * options; mapping it properly would cost a new dependency; and TopK retrieval
 * needs native SQL either way. {@code validate} only checks columns an entity
 * maps, so leaving it out is safe at startup. Every read and write of the vector
 * lives in {@link ChunkSearchRepository}, the only place allowed to name
 * {@code ::vector}.
 *
 * <p>{@code dimension} is unmapped for the same reason — see
 * {@link #recordEmbeddingProfile}.
 */
@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "content", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "version", length = 64)
    private String version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(Long projectId, Long documentId, int seq, String content, String metadata) {
        this.projectId = projectId;
        this.documentId = documentId;
        this.seq = seq;
        this.content = content;
        this.metadata = metadata;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public int getSeq() {
        return seq;
    }

    public String getContent() {
        return content;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Records which profile produced the vector. The {@code dimension} column is
     * deliberately absent from this entity, like {@code embedding}: the two are one
     * unit and {@link ChunkSearchRepository} writes them together. Mapping
     * dimension here would be a trap — Hibernate updates every mapped column, so
     * flushing this entity would null the dimension out from under a stored vector
     * and the CHECK would reject the row.
     */
    public void recordEmbeddingProfile(String provider, String model, String version) {
        this.provider = provider;
        this.model = model;
        this.version = version;
    }
}
