package com.forgepilot.knowledge;

/** Ingestion state of a document. A FAILED document must carry its reason (ARCHITECTURE.md 6). */
public enum KnowledgeStatus {
    PENDING,
    READY,
    FAILED
}
