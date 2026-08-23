package com.forgepilot.knowledge;

import java.time.Instant;

/**
 * 面向产品读取的知识文档摘要。正文与原始向量均不属于这个 HTTP 契约；前者只在
 * 入库和受控 Prompt 中使用，后者永远不离开 pgvector 查询。
 */
public record KnowledgeDocumentView(
        long id,
        long projectId,
        KnowledgeSourceType sourceType,
        Long sourceRequirementId,
        String title,
        KnowledgeStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        long chunkCount,
        long embeddedChunkCount,
        Integer embeddingDimension,
        String embeddingProvider,
        String embeddingModel,
        String embeddingVersion) {
}
