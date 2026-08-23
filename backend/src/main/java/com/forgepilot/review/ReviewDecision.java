package com.forgepilot.review;

/**
 * 对一次 Review 的一次性人工裁定（ARCHITECTURE.md 3.1）。
 *
 * <p>只允许 {@code PENDING -> APPROVE | REQUEST_CHANGES}，只写一次，
 * 绝不覆盖、撤销或改写。写入时会锁住 PR 行、检查六项前置条件，
 * 然后以 {@code decision = 'PENDING'} 为条件做更新；
 * 影响行数不是 1 就意味着冲突。
 *
 * <p>一旦某个 head SHA 上带了 {@code REQUEST_CHANGES}，这个 head 就再也
 * 无法被通过。改 base、改需求关联、改需求修订或重新同步 diff 都解不开它——
 * 只有一个**新的** head SHA 才能。
 */
public enum ReviewDecision {

    PENDING,
    APPROVE,
    REQUEST_CHANGES
}
