package com.forgepilot.knowledge;

import java.util.ArrayList;
import java.util.List;

import com.forgepilot.ai.AiCallContext;
import com.forgepilot.ai.AiGateway;
import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingesting and retrieving project knowledge. This module receives a requirement
 * id only as an opaque scope value and never looks a requirement up
 * (ARCHITECTURE.md 1.3); the attachment relation itself belongs to
 * {@code requirement}.
 *
 * <p>A document moves PENDING -> chunked -> embedded -> READY. It becomes READY
 * only once its chunks carry vectors, so a half-ingested document is never
 * retrievable and never silently returns nothing.
 */
@Service
public class KnowledgeService {

    /**
     * Chunking is deliberately dumb and deterministic: a fixed character budget,
     * split at the last line break that fits so paragraphs survive where possible.
     * Anything cleverer is a retrieval-quality decision, and Phase 6 is where that
     * gets measured rather than guessed.
     */
    static final int MAX_CHUNK_CHARS = 1_200;

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final ChunkSearchRepository vectors;
    private final KnowledgeUploadValidator validator;
    private final ProjectAccessService access;
    private final AiGateway ai;

    /**
     * The embedding profile lives here rather than in {@code ai}: the columns that
     * record it belong to {@code knowledge_chunk}, and D015.3 makes this module
     * responsible for the dimension check that is the project's only defence.
     */
    private final String provider;
    private final String model;
    private final String version;

    KnowledgeService(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks,
            ChunkSearchRepository vectors, KnowledgeUploadValidator validator,
            ProjectAccessService access, AiGateway ai,
            @Value("${forgepilot.knowledge.embedding.provider:}") String provider,
            @Value("${forgepilot.knowledge.embedding.model:}") String model,
            @Value("${forgepilot.knowledge.embedding.version:}") String version) {
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.validator = validator;
        this.access = access;
        this.ai = ai;
        this.provider = provider;
        this.model = model;
        this.version = version;
    }

    @Transactional
    public long createProjectKnowledge(long projectId, long actorId, String title, String text) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        validator.validate(title, text);
        return ingest(KnowledgeDocument.projectKnowledge(projectId, title, text));
    }

    /** {@code requirementId} is an opaque scope here; the database checks that it exists. */
    @Transactional
    public long createRequirementAttachment(long projectId, long actorId, long requirementId,
            String title, String text) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        validator.validate(title, text);
        return ingest(KnowledgeDocument.attachment(projectId, requirementId, title, text));
    }

    /**
     * Promotion copies rather than rewrites (D005). The attachment keeps its
     * ownership and its history; the copy is a new public document that starts its
     * own ingestion, so nothing that already referenced the original changes
     * meaning underneath it.
     */
    @Transactional
    public long promoteToProjectKnowledge(long projectId, long actorId, long documentId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        KnowledgeDocument original = documents.findByProjectIdAndId(projectId, documentId)
                .orElseThrow(ApiException::notFound);
        if (original.getSourceType() != KnowledgeSourceType.REQUIREMENT_ATTACHMENT) {
            throw ApiException.conflict("This document is already project knowledge.");
        }
        return ingest(original.copyAsProjectKnowledge());
    }

    @Transactional(readOnly = true)
    public List<ChunkSearchRepository.ChunkMatch> search(long projectId, long actorId,
            float[] query, int limit) {
        access.requireMember(projectId, actorId);
        return vectors.search(projectId, query, limit);
    }

    /**
     * Stores the document, splits it, embeds every piece and only then marks it
     * READY. All of it is one transaction, so a document is never left looking
     * retrievable while holding no vectors — which would return nothing and look
     * like an empty corpus rather than a failure.
     */
    private long ingest(KnowledgeDocument document) {
        KnowledgeDocument saved = documents.save(document);
        List<String> pieces = split(saved.getText());

        List<KnowledgeChunk> rows = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            rows.add(chunks.save(new KnowledgeChunk(saved.getProjectId(), saved.getId(),
                    index + 1, pieces.get(index), null)));
        }

        List<float[]> embeddings = ai.embed(pieces, model, AiCallContext.ofProject(saved.getProjectId()));
        if (embeddings.size() != rows.size()) {
            // A provider that returns the wrong count would otherwise pair vectors
            // with the wrong chunks, which no constraint can detect.
            throw ApiException.unprocessable("The provider returned " + embeddings.size()
                    + " embeddings for " + rows.size() + " chunks.");
        }
        for (int index = 0; index < rows.size(); index++) {
            KnowledgeChunk row = rows.get(index);
            row.recordEmbeddingProfile(provider, model, version);
            // Writes embedding and dimension together, and refuses a dimension that
            // disagrees with the rest of the project (D015.3).
            vectors.writeEmbedding(saved.getProjectId(), row.getId(), embeddings.get(index));
        }

        saved.markReady();
        return saved.getId();
    }

    static List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int end = Math.min(cursor + MAX_CHUNK_CHARS, text.length());
            if (end < text.length()) {
                int lineBreak = text.lastIndexOf('\n', end);
                if (lineBreak > cursor) {
                    end = lineBreak + 1;
                }
            }
            String piece = text.substring(cursor, end);
            if (!piece.isBlank()) {
                pieces.add(piece.strip());
            }
            cursor = end;
        }
        return pieces;
    }
}
