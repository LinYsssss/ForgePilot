package com.example.codereview.knowledge;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 64)
    private String docType;

    @Column(nullable = false, length = 255)
    private String sourceName;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Column(columnDefinition = "text")
    private String embeddingJson;

    @Column(nullable = false)
    private Instant createdAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(Long documentId, Long projectId, String docType, String sourceName, int chunkIndex, String content, String embeddingJson) {
        this.documentId = documentId;
        this.projectId = projectId;
        this.docType = docType;
        this.sourceName = sourceName;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embeddingJson = embeddingJson;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getDocType() {
        return docType;
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }
}
