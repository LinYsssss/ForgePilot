package com.forgepilot.scm;

/**
 * 「这个 PR 的当前 head 上，是否已经有人做过终局裁定？」——PRD P1 授权 PR 作者
 * 纠正关联所需要的唯一一件 {@code scm} 自己答不出来的事实。
 *
 * <p>接口声明在 {@code scm}、实现落在 {@code review}，方向是刻意的：
 * ARCHITECTURE.md 1.3 与 ArchUnit 规则 3 禁止 {@code scm} 在编译期依赖
 * {@code review}，而依赖倒置让唯一的编译期边保持为 {@code review → scm}
 * ——那条边本来就存在。{@code scm} 只认识自己包里的这个接口，
 * Spring 在运行时把 {@code review} 的实现注进来。
 *
 * <p>这不是把 Review 编排搬进了 {@code scm}：它是一个布尔查询，
 * 既不创建 Review、不改 Decision，也不触发任何执行。
 */
public interface PullRequestDecisionGate {

    /**
     * 该 PR 在 {@code headSha} 上是否已存在任何非 PENDING 的 Decision
     * （{@code APPROVE} 或 {@code REQUEST_CHANGES}）。
     *
     * <p>按 head 而不是按 PR 提问，与 ARCHITECTURE.md 3.1 的 Decision Gate 同口径：
     * 推一个新 commit 会开启一个新的 head，作者在新 head 上重新获得纠正权。
     * 自动投递建出的 PENDING Review **不**算终局裁定——D007 明确要求
     * 「即使自动 PENDING 已存在」作者仍可纠正。
     */
    boolean hasFinalDecisionOnHead(long projectId, long pullRequestId, String headSha);
}
