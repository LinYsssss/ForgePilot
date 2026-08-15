package com.example.codereview.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用独立事务提交知识文档的状态流转。
 *
 * <p>它的存在源于一个具体的 bug:上传流程把文档标成 FAILED 之后又重新抛出,而这两件事在同一个
 * 事务里。回滚把状态变更连同文档行一起丢掉了,于是一次失败的上传**没留下任何痕迹**——
 * 调用方拿到 500,文档列表却依然是空的。
 *
 * <p>{@code REQUIRES_NEW} 正是让这个结果能在调用方栈解开时幸存下来的东西。
 */
@Service
public class KnowledgeDocumentStateService {

    private final KnowledgeDocumentRepository documents;

    public KnowledgeDocumentStateService(KnowledgeDocumentRepository documents) {
        this.documents = documents;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexed(Long documentId) {
        documents.findById(documentId).ifPresent(document -> {
            document.markIndexed();
            documents.save(document);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long documentId, String reason) {
        documents.findById(documentId).ifPresent(document -> {
            document.markFailed(reason);
            documents.save(document);
        });
    }
}
