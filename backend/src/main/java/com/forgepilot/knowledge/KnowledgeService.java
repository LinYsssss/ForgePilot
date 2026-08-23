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
 * 项目知识的入库与检索。本模块只把需求 id 当作不透明的作用域取值接收，
 * 从不去查询任何需求（ARCHITECTURE.md 1.3）；附件关系本身归
 * {@code requirement} 所有。
 *
 * <p>一份文档的流转是 PENDING → 分块 → 向量化 → READY。只有当它的分块都带上
 * 向量之后才会变成 READY，因此半入库的文档永远不可被检索到，也永远不会
 * 静默返回空结果。
 */
@Service
public class KnowledgeService {

    /**
     * 分块刻意做得又笨又确定：固定的字符预算，在放得下的最后一个换行处切开，
     * 以便尽可能保住段落完整。任何更聪明的做法都属于检索质量决策，
     * 而那要到 Phase 6 用实测来定，不能靠猜。
     */
    static final int MAX_CHUNK_CHARS = 1_200;

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeReadRepository reads;
    private final KnowledgeChunkRepository chunks;
    private final ChunkSearchRepository vectors;
    private final KnowledgeUploadValidator validator;
    private final ProjectAccessService access;
    private final AiGateway ai;

    /**
     * embedding 档案住在这里而不是 {@code ai}：记录它的那些列属于
     * {@code knowledge_chunk}，且 D015.3 把维度检查——本项目唯一的防线——
     * 的责任交给了本模块。
     */
    private final String provider;
    private final String model;
    private final String version;

    KnowledgeService(KnowledgeDocumentRepository documents, KnowledgeReadRepository reads,
            KnowledgeChunkRepository chunks,
            ChunkSearchRepository vectors, KnowledgeUploadValidator validator,
            ProjectAccessService access, AiGateway ai,
            @Value("${forgepilot.knowledge.embedding.provider:}") String provider,
            @Value("${forgepilot.knowledge.embedding.model:}") String model,
            @Value("${forgepilot.knowledge.embedding.version:}") String version) {
        this.documents = documents;
        this.reads = reads;
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

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> listProjectKnowledge(long projectId, long actorId) {
        access.requireMember(projectId, actorId);
        return reads.findProjectKnowledge(projectId);
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentView document(long projectId, long actorId, long documentId) {
        access.requireMember(projectId, actorId);
        KnowledgeDocumentView view = reads.findByProjectIdAndId(projectId, documentId);
        if (view == null) {
            throw ApiException.notFound();
        }
        return view;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> documents(long projectId, long actorId,
            List<Long> documentIds) {
        access.requireMember(projectId, actorId);
        return reads.findByProjectIdAndIds(projectId, documentIds);
    }

    /** 这里的 {@code requirementId} 只是不透明作用域；它是否存在由数据库负责检查。 */
    @Transactional
    public long createRequirementAttachment(long projectId, long actorId, long requirementId,
            String title, String text) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        validator.validate(title, text);
        return ingest(KnowledgeDocument.attachment(projectId, requirementId, title, text));
    }

    /**
     * 提升为公共知识采用**复制**而非改写（D005）。原附件保留自己的归属与历史；
     * 副本是一份新的公共文档，会开始它自己的入库流程，因此任何原本引用了
     * 原文档的东西都不会在脚下被改变含义。
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
            Long requirementId, float[] query, int limit) {
        access.requireMember(projectId, actorId);
        return vectors.search(projectId, requirementId, query, limit);
    }

    /**
     * 存下文档、切分、逐块向量化，只有全部完成之后才标记为 READY。
     * 这一切都在**同一个**事务里，因此绝不会出现「看起来可检索、实际没有向量」
     * 的文档——那种文档会返回空结果，看上去像语料为空，而不像一次失败。
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
            // 若 provider 返回的数量不对，向量就会与错误的分块配对，
            // 而这是任何约束都检测不出来的。
            throw ApiException.unprocessable("The provider returned " + embeddings.size()
                    + " embeddings for " + rows.size() + " chunks.");
        }
        for (int index = 0; index < rows.size(); index++) {
            KnowledgeChunk row = rows.get(index);
            row.recordEmbeddingProfile(provider, model, version);
            // 同时写入 embedding 与 dimension，并拒绝与该项目其余部分
            // 不一致的维度（D015.3）。
            vectors.writeEmbedding(saved.getProjectId(), row.getId(), embeddings.get(index));
        }

        saved.markReady();
        chunks.flush();
        documents.flush();
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
