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

/** One serial consumer of durable PENDING documents in the current single-backend deployment. */
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
                throw ApiException.unprocessable("The provider returned an unexpected embedding count.");
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
            String reason = "Document processing failed (" + (failure instanceof ApiException api
                    ? api.getCode() : failure.getClass().getSimpleName()) + ").";
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
