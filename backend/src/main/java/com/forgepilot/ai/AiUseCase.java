package com.forgepilot.ai;

/**
 * Which scenario a call belongs to, mirroring {@code ai_call_log.use_case}'s
 * CHECK one for one (ARCHITECTURE.md 2.4: varchar + CHECK in the database, an
 * enum in Java). The values froze the moment {@code V4__knowledge_ai.sql} was
 * applied, so this list is a copy, not a choice.
 *
 * <p>{@code ai} itself never picks one: the caller says why it is calling, which
 * is the only thing that lets one gateway serve four scenarios without knowing
 * any of their business types (ARCHITECTURE.md 4.1).
 */
public enum AiUseCase {

    REQUIREMENT_QUALITY,
    IMPLEMENTATION_GUIDANCE,
    EMBEDDING,
    /**
     * Reserved by the CHECK for batch 3's review engine. Nothing in batch 2
     * produces it, the same way {@code ProjectStatus.ARCHIVED} was reserved by
     * V2 with no transition behind it.
     */
    REVIEW
}
