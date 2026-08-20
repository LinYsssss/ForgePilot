# ADR-008：Review 上下文快照与进程内任务恢复

状态：已接受
日期：2026-08-19（R2.1 修订：旧 Review 永不失效；R2.2 修订：reconciliation 只恢复停滞任务）

## 背景

Review 结果同时依赖 PR head、Requirement、AC 与召回的项目知识。PR 的 Requirement 关联或知识文档后续可能变化；若历史 Review 只通过当前关系反查，会失去审计真实性。

V2 使用有界进程内执行器而不引入 MQ。应用可能在 PR 已保存、AI 任务尚未执行时崩溃，因此必须有不依赖消息队列的恢复路径。

## 决策

1. Review 记录审查时的 `requirement_id` 与 `requirement_revision_id`，并保存 Requirement、AC、Knowledge evidence 与 truncation manifest 的不可变 `context_snapshot_json`。
2. 历史 Review 页面只读取自身快照，不通过 PullRequest 当前关联反推审查语义。
3. Requirement 进入 READY 后正文与 AC 锁定，修改须由 LEADER 创建新的不可变 Revision（[ADR-011](./ADR-011-requirement-revision-and-state.md)）。**旧 Review 永不失效、永不覆盖**：它是对当时上下文的忠实记录；上下文变更后构成新的 Review 身份（[ADR-003](./ADR-003-review-identity.md) §1），页面显示"审查已过期"，由人工触发重新审查。`review` 不设 `INVALIDATED` 之类的状态——执行状态与语义有效性是两个维度，不得混入同一列。
4. **Webhook 的 PR head 更新与幂等 Review(PENDING) 的持久化必须在同一事务链内完成**，返回 202 前两者均已落库。二者不得分处两个事务——否则会出现"head 已更新但无 Review"的空窗。
5. AI 执行由有界进程内执行器完成；轻量 reconciliation **只恢复已经存在的超时 `PENDING` / `RUNNING`**，回到同一个 `ReviewService.requestReview` 路径。
6. **禁止**基于"当前 head + 当前 Requirement Revision 没有 Review"自动创建 Review。Requirement 关联或 Revision 变化后的重审一律由人工触发（[ADR-011](./ADR-011-requirement-revision-and-state.md) §14）。
7. reconciliation 不是第二条 Review Pipeline，不允许包含独立 Prompt、状态机或输出模型。

## 结果

- 历史审查可以证明"当时依据什么作出结论"。
- 保持零 MQ 的同时，缩小进程崩溃导致任务永久丢失的窗口。
- 数据模型不增加新表，只增加 Review 快照字段和一个恢复调度器。

## R2.2 修订说明

原第 5 条包含"发现当前 head 无 Review 并恢复"。该规则在 Review 身份为 `(pull_request_id, head_sha)` 时只在 webhook 崩溃时触发；但 [ADR-003](./ADR-003-review-identity.md) 将 `requirement_revision_id` 纳入身份后，**每一次需求版本或关联变更都会满足该条件**，reconciliation 会自动创建 Review，与 ADR-011 §14「不自动重审」直接冲突。

因此把"补建缺失 Review"的职责从 reconciliation 移除，改由第 4 条的事务链从源头消除空窗：既然 head 更新与 PENDING 创建原子完成，就不存在"有 head 无 Review"的中间态需要补偿。reconciliation 退化为纯粹的**停滞任务恢复器**，职责更窄、更不可能长成第二条 Pipeline。
