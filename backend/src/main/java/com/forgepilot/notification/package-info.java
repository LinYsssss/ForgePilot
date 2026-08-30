/**
 * 审查结果的对外送达：项目级的通知渠道配置，以及审查完成或失败后的摘要通知。
 *
 * <p>它<strong>不是</strong>业务闭环的一环，而是一条尽力而为的旁路。推送失败只记日志——
 * 不重试、不改审查状态、不向上抛。ARCHITECTURE.md 7.1 的「不引入」清单里没有消息队列，
 * 因此这里没有任何投递保证，也不该假装有：把通知失败变成审查失败，会让一个聊天机器人
 * 的可用性成为代码审查的前置条件。
 *
 * <p>它没有放进 {@code review}，因为那个包的契约写的是「唯一的 Review Engine 及其人工
 * 决策闭环」，一个出站 HTTP 集成与这句话矛盾。它通过监听 {@code ReviewCompleted} 与
 * {@code ReviewFailed} 事件
 * 取得触发，因此 {@code review} 不认识本包。
 *
 * <p>它依赖 {@code scm} 只为一件事：{@code ScmSecretCipher}。本部署只有一把静态加密密钥
 * （{@code FORGEPILOT_SCM_SECRET_KEY}），而它保护着线上已存的仓库凭据。为了一张更好看的
 * 依赖图去重命名那个变量，或者在这里另起一套加密，都比这条边更糟。若将来出现第三个使用者，
 * 那才是把它提升到 {@code common} 的时机。
 */
package com.forgepilot.notification;
