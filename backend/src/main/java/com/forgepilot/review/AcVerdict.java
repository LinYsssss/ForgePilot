package com.forgepilot.review;

/**
 * 一次 Review 对某条验收条件得出的结论（ARCHITECTURE.md 3.5）。
 *
 * <p>被审查修订的每一条 AC 最终都恰好落在其中一个取值上。这里刻意没有
 * “无裁定”这个值：模型只字未提的 AC 会被 {@link ReviewOutputValidator}
 * 填成 {@link #NOT_FOUND}——因为在一个供人判断「这个 PR 是否满足需求」的
 * 页面上，“模型没说”和“没有任何东西实现它”绝不能是同一件事。
 *
 * <p>分批过程从不产生这些取值。一个批次只看到 diff 的一部分，
 * 因此某个批次给出的裁定会与另一个批次矛盾；只有能看到所有批次证据的
 * 最终综合阶段才有资格下结论。
 */
public enum AcVerdict {

    /** diff 实现了这条验收条件，且证据就在变更文件之内。 */
    COVERED,

    /** 被审查的那部分 diff 里没有任何东西实现它。模型沉默时也填这个值。 */
    NOT_FOUND,

    /** 有东西涉及了它，但证据不完整或自相矛盾。 */
    AT_RISK
}
