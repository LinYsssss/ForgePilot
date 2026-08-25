package com.forgepilot.requirement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.forgepilot.common.ApiException;
import com.forgepilot.knowledge.KnowledgeDocumentView;
import com.forgepilot.knowledge.KnowledgeService;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 需求附件的唯一拥有者。{@code requirement_attachment} 是归属事实源；Document 上的
 * scope 只是被数据库约束的检索投影，不能代替此处的关系读取。
 */
@Service
public class RequirementAttachmentService {

    private final RequirementAttachmentRepository attachments;
    private final RequirementRepository requirements;
    private final KnowledgeService knowledge;
    private final ProjectAccessService access;

    RequirementAttachmentService(RequirementAttachmentRepository attachments,
            RequirementRepository requirements, KnowledgeService knowledge, ProjectAccessService access) {
        this.attachments = attachments;
        this.requirements = requirements;
        this.knowledge = knowledge;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> list(long projectId, long actorId, long requirementId) {
        access.requireMember(projectId, actorId);
        require(projectId, requirementId);
        List<Long> documentIds = attachments.findByProjectIdAndRequirementIdOrderByIdAsc(projectId, requirementId)
                .stream().map(RequirementAttachment::getDocumentId).toList();
        Map<Long, KnowledgeDocumentView> documents = new LinkedHashMap<>();
        knowledge.documents(projectId, actorId, documentIds)
                .forEach(document -> documents.put(document.id(), document));
        return documentIds.stream().map(documents::get).toList();
    }

    /**
     * Document 入库和附件关系写入共享此处的外层事务。嵌套调用的 KnowledgeService
     * 因传播规则加入同一事务，任何一侧失败都会让两行一起回滚。
     */
    @Transactional
    public KnowledgeDocumentView create(long projectId, long actorId, long requirementId,
            String title, String text) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        require(projectId, requirementId);
        requireSupportedFileName(title);
        long documentId = knowledge.createRequirementAttachment(projectId, actorId, requirementId, title, text);
        attachments.save(new RequirementAttachment(projectId, requirementId, documentId));
        return knowledge.document(projectId, actorId, documentId);
    }

    @Transactional(readOnly = true)
    public AttachmentContent content(long projectId, long actorId, long requirementId, long documentId) {
        require(projectId, requirementId);
        requireAttached(projectId, requirementId, documentId);
        KnowledgeService.DocumentContent document = knowledge.content(projectId, actorId, documentId);
        return new AttachmentContent(document.documentId(), document.title(), mediaType(document.title()),
                document.text());
    }

    @Transactional
    public KnowledgeDocumentView promote(long projectId, long actorId, long requirementId, long documentId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        requireAttached(projectId, requirementId, documentId);
        long promotedId = knowledge.promoteToProjectKnowledge(projectId, actorId, documentId);
        return knowledge.document(projectId, actorId, promotedId);
    }

    private void requireAttached(long projectId, long requirementId, long documentId) {
        if (attachments.findByProjectIdAndRequirementIdOrderByIdAsc(projectId, requirementId).stream()
                .noneMatch(attachment -> attachment.getDocumentId() == documentId)) {
            throw ApiException.notFound();
        }
    }

    private static void requireSupportedFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".md")) {
            throw ApiException.unprocessable("Requirement documents must use .txt or .md.");
        }
    }

    private static String mediaType(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".md") ? "text/markdown" : "text/plain";
    }

    private void require(long projectId, long requirementId) {
        if (requirements.findByProjectIdAndIdAndDeletedAtIsNull(projectId, requirementId).isEmpty()) {
            throw ApiException.notFound();
        }
    }

    public record AttachmentContent(long documentId, String fileName, String mediaType, String text) {
    }
}
