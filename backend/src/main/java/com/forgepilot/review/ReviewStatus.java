package com.forgepilot.review;

/**
 * 一次 Review 的**执行**状态，与它的 {@link ReviewDecision} 相互正交
 * （ARCHITECTURE.md 3.2）。
 *
 * <p>这里刻意没有 {@code INVALIDATED}：执行状态与语义有效性是两个不同的维度
 * （3.5）。一次 Review 是否仍适用于该 PR 的当前输入，是通过把它的四个身份列
 * 与 PR 比对**推导**出来的，从不存储。
 */
public enum ReviewStatus {

    /** 在 SCM 事务内部落库；尚无任何 worker 抢占它。 */
    PENDING,

    /** 已被抢占。抢占者持有为它设围栏的 attempt、token 与 lease。 */
    RUNNING,

    /** 终态。永不重跑，也永不被覆盖。 */
    COMPLETED,

    /**
     * AI 调用失败，或其结构无法修复。人工重试会让这一行带着新的 attempt
     * 回到 {@link #PENDING}——是**复用**这一行而不是换一行，
     * 因此各次尝试的历史都留在同一个身份上。
     */
    FAILED
}
