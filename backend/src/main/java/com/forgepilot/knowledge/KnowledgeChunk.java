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
 * 文档的一个可检索切片，外加「哪一套 embedding 档案产生了它的向量」这份审计信息。
 *
 * <p>{@code embedding} 列**刻意不做映射**（D015.4）。实测：把它映射成 String
 * 能通过 {@code ddl-auto=validate}，却会让运行时的每一次写入都以 42804 失败，
 * 这是四个选项里最糟的一个；正经映射它则要引入新依赖；而 TopK 检索无论如何
 * 都要用原生 SQL。{@code validate} 只检查实体映射到的列，因此不映射它在启动
 * 阶段是安全的。对向量的每一次读写都住在 {@link ChunkSearchRepository}——
 * 唯一被允许写出 {@code ::vector} 的地方。
 *
 * <p>{@code dimension} 出于同样的理由也不映射，详见
 * {@link #recordEmbeddingProfile}。
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
     * 记录是哪套档案产生了该向量。{@code dimension} 列和 {@code embedding} 一样
     * 刻意不在本实体中出现：二者是一个整体，由 {@link ChunkSearchRepository}
     * 一并写入。把 dimension 映射到这里会是个陷阱——Hibernate 会更新所有被映射的
     * 列，于是 flush 本实体就会在已存向量的脚下把 dimension 置空，随后 CHECK
     * 会拒绝这一行。
     */
    public void recordEmbeddingProfile(String provider, String model, String version) {
        this.provider = provider;
        this.model = model;
        this.version = version;
    }
}
