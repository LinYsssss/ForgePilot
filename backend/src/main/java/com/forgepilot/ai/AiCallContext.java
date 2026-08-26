package com.forgepilot.ai;

/**
 * 标明一次调用属于哪些业务行，以不透明 id 的形式携带。
 *
 * <p>ARCHITECTURE.md 1.3 只允许 {@code ai} 依赖 {@code common}，4.1 明确网关
 * “不认识 Requirement/Finding/Review”。但 4.1 给出的两个方法签名无法提供
 * {@code ai_call_log} 所需的 id，于是由本记录补上——用 {@code Long}，正是 1.3
 * 已经为 knowledge 认可的形态（“只收不透明 scope id”）。这些列本来
 * 就以标量写入，因此并无信息损失。
 *
 * <p>{@code review_id} 是**故意缺席**的。它的外键由后来的迁移补上，
 * 而那次迁移的前提是既有行必须全为 NULL；让这一列在 Java 侧根本不可达，
 * 才是真正的保证，光靠“约定没人会写”是靠不住的。
 */
public record AiCallContext(long projectId, Long requirementId, Long requirementRevisionId) {

    public AiCallContext {
        // ai_call_log 的三列复合键是 MATCH SIMPLE：只要有一列为 NULL，
        // PostgreSQL 就会整体跳过校验——于是“有 revision id 却没有 requirement id”
        // 会完全不受检查地落库。这一条数据库拦不住，只能在这里拦。
        if (requirementRevisionId != null && requirementId == null) {
            throw new IllegalArgumentException("A revision id must come with its requirement id.");
        }
    }

    /** 用于不属于任何需求的调用，例如为项目知识做向量化。 */
    public static AiCallContext ofProject(long projectId) {
        return new AiCallContext(projectId, null, null);
    }

    /** 用于针对某个修订文本的调用，例如需求质量检查或实现建议。 */
    public static AiCallContext ofRevision(long projectId, long requirementId, long requirementRevisionId) {
        return new AiCallContext(projectId, requirementId, requirementRevisionId);
    }
}
