package com.forgepilot.ai;

/**
 * Which rows a call belongs to, carried as opaque ids.
 *
 * <p>ARCHITECTURE.md 1.3 lets {@code ai} depend on {@code common} and nothing
 * else, and 4.1 says the gateway "does not know Requirement/Finding/Review".
 * The two signatures in 4.1 nevertheless cannot supply the ids
 * {@code ai_call_log} needs, so this record does — as {@code Long}s, the same
 * shape 1.3 already blesses for knowledge ("只收不透明 scope id"). D013.1
 * variant A writes these columns as scalars anyway, so nothing is lost.
 *
 * <p>{@code review_id} is deliberately absent. Its foreign key arrives with
 * batch 3 (D015.1) and that migration may only add it once every existing row
 * is NULL; leaving the column unreachable from Java is what guarantees that,
 * rather than a promise that nobody writes it.
 */
public record AiCallContext(long projectId, Long requirementId, Long requirementRevisionId) {

    public AiCallContext {
        // ai_call_log's three-column key is MATCH SIMPLE, so one NULL makes
        // PostgreSQL skip the whole check: a revision id without its requirement
        // id would land completely unverified. The database cannot catch this one.
        if (requirementRevisionId != null && requirementId == null) {
            throw new IllegalArgumentException("A revision id must come with its requirement id.");
        }
    }

    /** For a call that belongs to no requirement, such as embedding project knowledge. */
    public static AiCallContext ofProject(long projectId) {
        return new AiCallContext(projectId, null, null);
    }

    /** For a call about one revision's prose, such as quality or guidance. */
    public static AiCallContext ofRevision(long projectId, long requirementId, long requirementRevisionId) {
        return new AiCallContext(projectId, requirementId, requirementRevisionId);
    }
}
