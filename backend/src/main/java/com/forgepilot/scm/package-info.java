/**
 * 仓库凭据、webhook 接入、权威的 pull request 快照，以及 {@code REQ-<n>} 解析。
 *
 * <p>本功能模块不对 Review 作任何决定。它在更新 pull request 的那个事务内部
 * 发布 {@link com.forgepilot.scm.PullRequestChanged}，
 * 并且对 {@code review} 没有任何编译期依赖。
 */
package com.forgepilot.scm;
