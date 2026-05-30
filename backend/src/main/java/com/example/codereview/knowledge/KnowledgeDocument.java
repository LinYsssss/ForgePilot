package com.example.codereview.knowledge;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long uploaderId;

    @Column(nullable = false, length = 64)
    private String docType;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(columnDefinition = "text")
    private String contentText;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(Long projectId, Long uploaderId, String docType, String fileName, String contentText) {
        this.projectId = projectId;
        this.uploaderId = uploaderId;
        this.docType = docType;
        this.fileName = fileName;
        this.contentText = contentText;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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

    public String getFileName() {
        return fileName;
    }

    public String getContentText() {
        return contentText;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markIndexed() {
        this.status = "INDEXED";
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = "FAILED";
        this.updatedAt = Instant.now();
    }
}
