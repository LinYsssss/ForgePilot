package com.forgepilot.review;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 一条 Finding 的人工处理生命周期（PRD.md 5）。
 *
 * <p>它与 {@link FindingContinuity} <strong>正交</strong>：一个说的是人做了什么
 * 判断，另一个说的是这条问题跨轮次的来源。PRD.md 明确规定二者不得合并成
 * 一个字段或一个 UI 标签，因此它们是两个枚举、两个列。
 *
 * <p>{@code NOT_REPORTED} 刻意缺席。ARCHITECTURE.md 3.6 把它定义为对上一轮的
 * 一个查询派生观察——它从不存储，而且“本轮没有再报告”绝不能被读成“已修复”。
 */
public enum FindingStatus {

    OPEN,
    CONFIRMED,
    IN_PROGRESS,
    FIXED,
    VERIFIED,
    CLOSED,
    REJECTED;

    /**
     * 把流转表写成数据，使测试可以逐对断言，而不必用散文再复述一遍
     * （与 {@code RequirementStatus} 同一形态）。
     *
     * <p>{@code REJECTED -> OPEN} 在表里，但仅有一次合法流转还到不了它：
     * PRD.md 5 <strong>只</strong>允许重开被继承的抑制项，
     * 因此服务层还要求 {@code continuity = SUPPRESSED}。
     * 普通的驳回是终态，而那个附加条件无法在这张表里表达。
     */
    private static final Map<FindingStatus, Set<FindingStatus>> ALLOWED_TARGETS = Map.of(
            OPEN, EnumSet.of(CONFIRMED, REJECTED),
            CONFIRMED, EnumSet.of(IN_PROGRESS, REJECTED),
            IN_PROGRESS, EnumSet.of(FIXED),
            FIXED, EnumSet.of(VERIFIED, IN_PROGRESS),
            VERIFIED, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(FindingStatus.class),
            REJECTED, EnumSet.of(OPEN));

    public boolean canMoveTo(FindingStatus target) {
        return ALLOWED_TARGETS.get(this).contains(target);
    }
}
