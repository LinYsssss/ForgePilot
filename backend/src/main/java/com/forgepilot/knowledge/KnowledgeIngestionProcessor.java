package com.forgepilot.knowledge;

import java.util.List;

import com.forgepilot.ai.AiCallContext;
import com.forgepilot.ai.AiGateway;
import com.forgepilot.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 串行消费 PENDING 知识文档的唯一后台处理器（ARCHITECTURE.md 5）。
 *
 * <p>每轮只领一条：读取后立即释放事务，在不占数据库连接的情况下调 Embedding provider，
 * 再用一个事务写入全部 chunk、Profile 与向量并标记 READY。任一步失败都回滚派生行，
 * 并在新事务里标记 FAILED（带受控原因）；清理本身失败则保留 PENDING 等下一轮。
 * 单后端部署靠 fixed-delay 调度天然串行，不引入租约或任务表。
 */
@Component
public class KnowledgeIngestionProcessor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionProcessor.class);

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final ChunkSearchRepository vectors;
    private final AiGateway ai;
    private final TransactionTemplate transaction;
    private final String provider;
    private final String model;
    private final String version;

    KnowledgeIngestionProcessor(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks,
            ChunkSearchRepository vectors, AiGateway ai, PlatformTransactionManager transactions,
            @Value("${forgepilot.knowledge.embedding.provider:}") String provider,
            @Value("${forgepilot.knowledge.embedding.model:}") String model,
            @Value("${forgepilot.knowledge.embedding.version:}") String version) {
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.ai = ai;
        this.transaction = new TransactionTemplate(transactions);
        this.provider = provider;
        this.model = model;
        this.version = version;
    }

    @Scheduled(fixedDelayString = "${forgepilot.knowledge.ingestion-interval-ms:1000}",
            initialDelayString = "${forgepilot.knowledge.ingestion-interval-ms:1000}")
    public void processNext() {
        KnowledgeDocument pending = transaction.execute(status ->
                documents.findFirstByStatusOrderByIdAsc(KnowledgeStatus.PENDING).orElse(null));
        if (pending == null) return;

        try {
            List<String> pieces = KnowledgeService.split(pending.getText());
            // No transaction or connection is held while waiting for the provider.
            List<float[]> embeddings = ai.embed(pieces, model, AiCallContext.ofProject(pending.getProjectId()));
            if (embeddings.size() != pieces.size()) {
                throw ApiException.unprocessable("服务返回的向量数量与输入不符。");
            }
            transaction.executeWithoutResult(status -> documents
                    .lockByProjectIdAndId(pending.getProjectId(), pending.getId())
                    .filter(document -> document.getStatus() == KnowledgeStatus.PENDING)
                    .ifPresent(document -> {
                        for (int index = 0; index < pieces.size(); index++) {
                            KnowledgeChunk chunk = new KnowledgeChunk(document.getProjectId(), document.getId(),
                                    index + 1, pieces.get(index), null);
                            chunk.recordEmbeddingProfile(provider, model, version);
                            chunks.save(chunk);
                            vectors.writeEmbedding(document.getProjectId(), chunk.getId(), embeddings.get(index));
                        }
                        document.markReady();
                    }));
        } catch (RuntimeException failure) {
            // The result transaction has ended; partial chunks have already rolled back.
            String reason = "文档处理失败（" + (failure instanceof ApiException api
                    ? api.getCode() : failure.getClass().getSimpleName()) + "）。";
            log.warn("Knowledge document {} in project {}: {}", pending.getId(), pending.getProjectId(), reason);
            try {
                transaction.executeWithoutResult(status -> documents
                        .lockByProjectIdAndId(pending.getProjectId(), pending.getId())
                        .filter(document -> document.getStatus() == KnowledgeStatus.PENDING)
                        .ifPresent(document -> document.markFailed(reason)));
            } catch (RuntimeException cleanupFailure) {
                log.warn("Knowledge document {} failure cleanup unavailable ({}); remains pending for recovery",
                        pending.getId(), cleanupFailure.getClass().getSimpleName());
            }
        }
    }
}
