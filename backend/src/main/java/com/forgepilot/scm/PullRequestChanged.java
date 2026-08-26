package com.forgepilot.scm;

/**
 * 在更新 pull request 行的那个事务**内部**发布。
 *
 * <p>同步的 {@code @EventListener} 会加入该事务，因此监听器一旦失败，
 * PR 的更新就会随之回滚：绝不能存在「PR 已经推进、但它所隐含的 Review 却缺席」
 * 这样一种已提交状态。{@code @TransactionalEventListener} 在这里是错的工具，
 * 且被禁止使用——它的默认阶段在提交之后运行，那时任何东西都已经回滚不了了。
 *
 * <p>类型定义在 {@code scm}，由 {@code review} 去 import。这让依赖方向保持在
 * ARCHITECTURE.md 1.3 允许的那一侧，同时使 {@code scm} 对 {@code review}
 * 没有任何编译期依赖。曾经根本没有监听器，因此这次发布只有测试作用域的
 * 监听器能看到。
 */
public record PullRequestChanged(Long pullRequestId, String headSha, String reviewInputFingerprint) {
}
