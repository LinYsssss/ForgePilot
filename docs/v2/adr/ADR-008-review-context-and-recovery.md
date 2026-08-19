# ADR-008：Review 上下文快照与进程内任务恢复

状态：已接受
日期：2026-08-19

## 背景

Review 结果同时依赖 PR head、Requirement、AC 与召回的项目知识。PR 的 Requirement 关联或知识文档后续可能变化；若历史 Review 只通过当前关系反查，会失去审计真实性。

V2 使用有界进程内执行器而不引入 MQ。应用可能在 PR 已保存、AI 任务尚未执行时崩溃，因此必须有不依赖消息队列的恢复路径。

## 决策

1. Review 记录审查时的 `requirement_id`，并保存 Requirement、AC、Knowledge evidence 与 truncation manifest 的不可变 `context_snapshot_json`。
2. 历史 Review 页面只读取自身快照，不通过 PullRequest 当前关联反推审查语义。
3. Requirement 进入 READY 后正文与 AC 默认锁定；已有 Review 时修改 PR↔Requirement 关联必须显式使旧 Review 失效。
4. Webhook 处理在返回 202 前必须已经持久化 PullRequest 与幂等的 Review(PENDING)。
5. AI 执行由有界进程内执行器完成；轻量 reconciliation 定期发现当前 head 无 Review、超时 PENDING 或 RUNNING，并回到同一个 `ReviewService.requestReview` 恢复。
6. reconciliation 不是第二条 Review Pipeline，不允许包含独立 Prompt、状态机或输出模型。

## 结果

- 历史审查可以证明“当时依据什么作出结论”。
- 保持零 MQ 的同时，缩小进程崩溃导致任务永久丢失的窗口。
- 数据模型不增加新表，只增加 Review 快照字段和一个恢复调度器。
