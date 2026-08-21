package com.forgepilot.ai;

/**
 * The one retry this project allows, and the table that decides when it applies.
 *
 * <p>ARCHITECTURE.md 7.2 fixes both halves: "LLM 重试次数 | 1 | 仅瞬时错误
 * (429/5xx/网络)". Retries are absent everywhere else in this codebase and must
 * stay absent: this exception is authorized for the gateway alone
 * (IMPLEMENTATION-PLAN.md Phase 4, ARCHITECTURE.md 4.1 and 7.1). Nothing in
 * knowledge ingestion, around the database, or on a 4xx may reuse it.
 *
 * <p>The split is not a preference. A 429 or a 5xx says the provider could not
 * answer this time; a 400 says the request itself is wrong, so an identical
 * second request can only produce the identical answer while doubling the bill.
 *
 * <p>Transport failures — a refused connection, a reset, and the timeout — are
 * 7.2's "网络" class and are transient by definition, so they are decided at the
 * catch clause in {@link AiGateway} rather than by a method that could only ever
 * return {@code true}.
 */
public final class AiFailurePolicy {

    /**
     * One call plus the single retry, and never a third. There is no backoff:
     * 7.2 defines a count and no delay, and inventing one would be a second
     * unsourced runtime rule.
     */
    public static final int MAX_ATTEMPTS = 2;

    private AiFailurePolicy() {
    }

    public static boolean isTransient(int httpStatus) {
        return httpStatus == 429 || httpStatus >= 500;
    }
}
