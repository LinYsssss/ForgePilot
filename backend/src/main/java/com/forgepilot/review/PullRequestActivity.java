package com.forgepilot.review;

/**
 * <strong>单个 pull request</strong> 的派生审查活动状态（PRD.md 5）。
 *
 * <p>是六个取值，不是八个。{@code NO_PR} 与 {@code MIXED} 属于需求层面的聚合，
 * 在这里毫无意义——需求修订的不可变身份规则划定了这个值域；
 * 把两个层面并起来数才会得到八个，而那不是任何一层的值域。
 * 这正是它与 {@link RequirementActivity} 分成两个枚举而不是共用一个的原因：
 * 单一的八值类型会允许给一个 PR 返回 {@code NO_PR}，而那不可能有任何含义。
 *
 * <p>从不存储。它是把 PR 当前的 head、指纹与需求修订，
 * 与携带同一身份的那次 Review 比对后算出来的。
 */
public enum PullRequestActivity {

    /** 没有任何 Review 匹配该 PR 当前的 head、指纹与修订。 */
    REVIEW_REQUIRED,

    /** 当前 Review 的执行失败了。 */
    FAILED,

    /** 当前 Review 的决策是 REQUEST_CHANGES。 */
    CHANGES_REQUESTED,

    /** 当前 Review 正在运行，或已完成、正等待人工处理。 */
    REVIEWING,

    /** 当前 Review 在排队中，尚未被抢占。 */
    PENDING,

    /** 当前 Review 的决策是 APPROVE。 */
    APPROVED
}
