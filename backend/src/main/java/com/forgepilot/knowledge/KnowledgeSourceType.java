package com.forgepilot.knowledge;

/** Whether a document belongs to one requirement or to the project as a whole (D005). */
public enum KnowledgeSourceType {
    /** Scoped to exactly one requirement; visible only to that requirement's AI scenarios. */
    REQUIREMENT_ATTACHMENT,
    /** Public project knowledge; carries no requirement scope. */
    PROJECT_KNOWLEDGE
}
