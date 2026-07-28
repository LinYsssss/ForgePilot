package com.example.codereview.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits knowledge document status transitions in their own transactions.
 *
 * <p>This exists because of a specific bug: upload marked a document FAILED and then rethrew, all
 * inside one transaction. The rollback discarded the status change along with the document row, so
 * a failed upload left no trace at all — the caller got a 500 and the document list stayed empty.
 *
 * <p>{@code REQUIRES_NEW} is what makes the outcome survive the caller unwinding.
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
