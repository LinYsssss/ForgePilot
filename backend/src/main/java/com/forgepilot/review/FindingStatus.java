package com.forgepilot.review;

/**
 * 一条 Finding 的人工处理生命周期（PRD.md 5）。
 *
 * <p>它与 {@link FindingContinuity} <strong>正交</strong>：一个说的是人做了什么
 * 判断，另一个说的是这条问题跨轮次的来源。PRD.md 明确规定二者不得合并成
 * 一个字段或一个 UI 标签，因此它们是两个枚举、两个列。
 *
 * <p>{@code NOT_REPORTED} 刻意缺席。ARCHITECTURE.md 3.6 把它定义为对上一轮的
 * 一个查询派生观察——它从不存储，而且“本轮没有再报告”绝不能被读成“已修复”。
 *
 * <p>哪些流转合法、每一步叫什么、谁可以执行，只写在
 * {@link FindingLifecycleService} 的那张表里；这里只有状态本身。
 */
public enum FindingStatus {

    OPEN,
    CONFIRMED,
    IN_PROGRESS,
    FIXED,
    VERIFIED,
    CLOSED,
    REJECTED
}
