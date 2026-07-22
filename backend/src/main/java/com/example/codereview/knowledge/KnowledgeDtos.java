package com.example.codereview.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record DocumentResponse(
            Long documentId,
            String fileName,
            String docType,
            String status,
            Instant createdAt
    ) {
        public static DocumentResponse from(KnowledgeDocument document) {
            return new DocumentResponse(
                    document.getId(),
                    document.getFileName(),
                    document.getDocType(),
                    document.getStatus(),
                    document.getCreatedAt()
            );
        }
    }

    public record SearchRequest(
            @jakarta.validation.constraints.Size(max = 500) String query,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(50) Integer topK
    ) {
    }

    public record SearchMatch(
            Long chunkId,
            String sourceName,
            String docType,
            int chunkIndex,
            double score,
            String content
    ) {
    }

    public record SearchResponse(List<SearchMatch> matches) {
    }

    public record ReindexResponse(
            int totalDocuments,
            int indexedDocuments,
            int skippedDocuments,
            int failedDocuments
    ) {
    }
}
