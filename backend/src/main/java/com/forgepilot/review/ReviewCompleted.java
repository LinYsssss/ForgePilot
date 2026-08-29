package com.forgepilot.review;

/**
 * 一次 Review 跑完并<strong>已经提交</strong>之后发布。
 *
 * <p>它<strong>在事务之外</strong>发布，这与 {@code PullRequestChanged} 恰好相反，
 * 而这个相反是有意的：那个事件必须能够连带回滚它的写入，这个不能。
 * 通知是尽力而为的旁路——让一个聊天机器人的可用性决定一次代码审查算不算数，
 * 是把附属品变成了前置条件。
 *
 * <p>因为它在提交返回之后才发布，所以它<em>无法</em>为一次没有提交的 Review 发出去，
 * 监听器也就不需要 {@code @TransactionalEventListener} 那套阶段机制。
 * 一个普通的 {@code @EventListener} 已经拿到了「确已完成」这个保证。
 *
 * <p>类型定义在 {@code review}，由监听方去 import，与 {@code PullRequestChanged}
 * 同款：这让 {@code review} 对监听它的模块没有任何编译期依赖。
 */
public record ReviewCompleted(long projectId, long reviewId) {
}
