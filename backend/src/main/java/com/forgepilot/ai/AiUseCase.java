package com.forgepilot.ai;

/**
 * 一次调用属于哪个场景，与 {@code ai_call_log.use_case} 的 CHECK 一一对应
 * （ARCHITECTURE.md 2.4：数据库用 varchar + CHECK，Java 侧用枚举）。这些取值
 * 在 {@code V4__knowledge_ai.sql} 生效那一刻就冻结了，因此本列表是一份副本，
 * 而不是一处可以自由发挥的选择。
 *
 * <p>{@code ai} 自己从不挑选场景：由调用方声明自己为何而来——这正是一个网关
 * 能服务四种场景却不认识其中任何业务类型的原因（ARCHITECTURE.md 4.1）。
 */
public enum AiUseCase {

    REQUIREMENT_QUALITY,
    IMPLEMENTATION_GUIDANCE,
    EMBEDDING,
    /**
     * 由 CHECK 为批次 3 的审查引擎预留。批次 2 中没有任何代码会产生它，
     * 正如 V2 预留了 {@code ProjectStatus.ARCHIVED} 却没有任何流转指向它。
     */
    REVIEW
}
