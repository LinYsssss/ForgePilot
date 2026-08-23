package com.forgepilot.review;

/**
 * 相对于同一个 PR 上的上一轮，这条 Finding 是从哪来的（D009、ARCHITECTURE.md 3.6）。
 *
 * <p>与 {@link FindingStatus} 正交。被重开的抑制项在这里仍然保持
 * {@code SUPPRESSED}——血缘是关于历史的事实，不会因为有人改了当前状态
 * 就不再成立（PRD.md 5）。
 *
 * <p>计算优先级固定为 {@code SUPPRESSED > PERSISTING > NEW}，
 * 且连续性只在**单个** PR 内部计算。
 */
public enum FindingContinuity {

    /** 在本 PR 上一次 COMPLETED 的审查中没有匹配项。 */
    NEW,

    /**
     * 通过 {@code finding_key} 与上一轮匹配上了。它会重新从
     * {@link FindingStatus#OPEN} 开始——持续存在的问题不等于已被裁定的问题。
     */
    PERSISTING,

    /**
     * 在本 PR 中针对这个 {@code finding_key} 的最近一次人工判断是驳回，
     * 且 {@code evidence_hash} 与 {@code basis_hash} 两者都没有变化。
     * 只有在这种情况下驳回才可被继承，因此一个抑制项无法在代码或需求
     * 在它脚下发生变动之后继续存活。
     */
    SUPPRESSED
}
