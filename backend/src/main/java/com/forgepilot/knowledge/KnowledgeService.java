package com.forgepilot.knowledge;

import java.util.ArrayList;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.DeletedResourceType;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectDeletionLog;
import com.forgepilot.project.ProjectRole;
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
     * 而那要用实测来定，不能靠猜。
     */
    static final int MAX_CHUNK_CHARS = 1_200;

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeReadRepository reads;
    private final KnowledgeChunkRepository chunks;
    private final ChunkSearchRepository vectors;
    private final KnowledgeUploadValidator validator;
    private final ProjectAccessService access;
    private final ProjectDeletionLog deletions;

    KnowledgeService(KnowledgeDocumentRepository documents, KnowledgeReadRepository reads,
            KnowledgeChunkRepository chunks, ChunkSearchRepository vectors, KnowledgeUploadValidator validator,
            ProjectAccessService access, ProjectDeletionLog deletions) {
        this.documents = documents;
        this.reads = reads;
        this.chunks = chunks;
        this.vectors = vectors;
        this.validator = validator;
        this.access = access;
        this.deletions = deletions;
    }

    @Transactional
    public long createProjectKnowledge(long projectId, long actorId, String title, String text) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        validator.validate(title, text);
        return documents.save(KnowledgeDocument.projectKnowledge(projectId, title, text)).getId();
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
    public DocumentContent content(long projectId, long actorId, long documentId) {
        access.requireMember(projectId, actorId);
        KnowledgeDocument document = documents.findByProjectIdAndId(projectId, documentId)
                .orElseThrow(ApiException::notFound);
        return new DocumentContent(document.getId(), document.getTitle(), document.getText());
    }

    @Transactional(readOnly = true)
    public DocumentContent publicContent(long projectId, long actorId, long documentId) {
        access.requireMember(projectId, actorId);
        KnowledgeDocument document = documents.findByProjectIdAndId(projectId, documentId)
                .filter(row -> row.getSourceType() == KnowledgeSourceType.PROJECT_KNOWLEDGE)
                .orElseThrow(ApiException::notFound);
        return new DocumentContent(document.getId(), document.getTitle(), document.getText());
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
        return documents.save(KnowledgeDocument.attachment(projectId, requirementId, title, text)).getId();
    }

    /**
     * 提升为公共知识采用**复制**而非改写。原附件保留自己的归属与历史；
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
        return documents.save(original.copyAsProjectKnowledge()).getId();
    }

    /**
     * 硬删一份项目知识文档。
     *
     * <p>级联是**应用层显式删除**，不是数据库 `ON DELETE`：全库只有
     * {@code pull_request.author_user_id} 一条 `ON DELETE`，加第二条会把
     * 「删除语义由服务显式表达」这条纪律打开一个口子；而 chunk 是纯派生数据，
     * 显式删掉就够。删掉 chunk 也就是 AC4——检索只读 {@code knowledge_chunk}，
     * 因此 Guidance 与 Review 的附件检索此后都召不回它，不需要另加代码。
     *
     * <p>需求附件文档被**拒绝**：附件关系是需求侧的事实，删知识文档不该
     * 顺手改变某条需求的附件构成。判定只看本表的 {@code source_type}，不查
     * {@code requirement_attachment}——那张表归 {@code requirement} 所有，而
     * {@code ck_knowledge_document_scope_matches_type} 加上附件侧 NOT NULL 的
     * {@code requirement_id} 已经让「公共知识永远进不了附件表」成为结构事实，
     * 于是 {@code source_type} 恰好就是「可能被附件引用」的那个集合。
     */
    @Transactional
    public void deleteProjectKnowledge(long projectId, long actorId, long documentId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        KnowledgeDocument document = documents.lockByProjectIdAndId(projectId, documentId)
                .orElseThrow(ApiException::notFound);
        if (document.getSourceType() == KnowledgeSourceType.REQUIREMENT_ATTACHMENT) {
            throw ApiException.conflict("This document is an attachment of requirement "
                    + document.getSourceRequirementId() + "; detach it there first.");
        }
        int removed = chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(projectId, documentId).size();
        chunks.deleteByProjectIdAndDocumentId(projectId, documentId);
        chunks.flush();
        documents.delete(document);
        documents.flush();
        deletions.record(projectId, DeletedResourceType.KNOWLEDGE_DOCUMENT, documentId, actorId,
                "chunks: " + removed);
    }

    @Transactional(readOnly = true)
    public List<ChunkSearchRepository.ChunkMatch> search(long projectId, long actorId,
            Long requirementId, float[] query, int limit) {
        access.requireMember(projectId, actorId);
        return vectors.search(projectId, requirementId, query, limit);
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

    public record DocumentContent(long documentId, String title, String text) {
    }
}
