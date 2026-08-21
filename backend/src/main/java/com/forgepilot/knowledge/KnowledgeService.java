package com.forgepilot.knowledge;

import java.util.ArrayList;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
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

    KnowledgeService(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks,
            ChunkSearchRepository vectors, KnowledgeUploadValidator validator,
            ProjectAccessService access) {
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.validator = validator;
        this.access = access;
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

    /** Stores the document and its chunks. Vectors are attached separately; until then it stays PENDING. */
    private long ingest(KnowledgeDocument document) {
        KnowledgeDocument saved = documents.save(document);
        List<String> pieces = split(saved.getText());
        for (int index = 0; index < pieces.size(); index++) {
            chunks.save(new KnowledgeChunk(saved.getProjectId(), saved.getId(),
                    index + 1, pieces.get(index), null));
        }
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
