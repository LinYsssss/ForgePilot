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

    @Column(length = 80)
    private String embeddingProvider;

    @Column(length = 160)
    private String embeddingModel;

    @Column(length = 160)
    private String embeddingVersion;

    private Integer embeddingDimension;

    @Column(nullable = false)
    private Instant createdAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(Long documentId, Long projectId, String docType, String sourceName, int chunkIndex, String content, String embeddingJson) {
        this(documentId, projectId, docType, sourceName, chunkIndex, content, embeddingJson,
                null, null, null, null);
    }

    public KnowledgeChunk(
            Long documentId,
            Long projectId,
            String docType,
            String sourceName,
            int chunkIndex,
            String content,
            String embeddingJson,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            Integer embeddingDimension
    ) {
        this.documentId = documentId;
        this.projectId = projectId;
        this.docType = docType;
        this.sourceName = sourceName;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embeddingJson = embeddingJson;
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
        this.embeddingVersion = embeddingVersion;
        this.embeddingDimension = embeddingDimension;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
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

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public String getEmbeddingVersion() {
        return embeddingVersion;
    }

    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }
}
