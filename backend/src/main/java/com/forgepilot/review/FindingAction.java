package com.forgepilot.review;

/**
 * 操作者**想做什么**，与机械的 from/to 状态对一起记录。
 *
 * <p>两者都存，是因为日后回读一次流转时不该还要从状态对里反推意图——
 * {@code FIXED -> IN_PROGRESS} 是 reviewer 把活儿打回去，
 * 这件事值得直说，而不是靠推断。
 *
 * <p>每个 action 恰好映射到 {@link FindingStatus} 的一次合法流转，
 * 因此两者永远不会互相矛盾。
 */
public enum FindingAction {

    /** OPEN -> CONFIRMED。确认这条问题成立。 */
    CONFIRM,

    /** OPEN -> REJECTED，或 CONFIRMED -> REJECTED。驳回。 */
    REJECT,

    /** CONFIRMED -> IN_PROGRESS。开发者自己认领；没有人能把它指派给别人。 */
    CLAIM,

    /** IN_PROGRESS -> FIXED。开发者声明已修复。 */
    MARK_FIXED,

    /** FIXED -> VERIFIED。复核通过。 */
    VERIFY,

    /** FIXED -> IN_PROGRESS。复核未通过，打回继续处理。 */
    SEND_BACK,

    /** VERIFIED -> CLOSED。关闭。 */
    CLOSE,

    /** REJECTED -> OPEN，且仅限于被继承的抑制项。 */
    REOPEN
}
