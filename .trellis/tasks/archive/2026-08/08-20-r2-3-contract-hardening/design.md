# R2.3 契约加固设计

## 范围与边界

本任务只修改方案与治理文档。产品因果链、8 个顶层包、16 张表、单 Review Engine、PostgreSQL/pgvector 技术边界和 Phase 顺序不变；不创建业务代码、迁移脚本或新表。开发文档收敛为 README、PRD、ARCHITECTURE、IMPLEMENTATION-PLAN、DECISIONS、LEGACY-MIGRATION-MATRIX 六份，历史细节由 Git 保留。

## 契约变更

### 数据完整性

- `review` 提供 `UNIQUE(project_id, id)`。
- `finding` 使用 `(project_id, review_id) -> review(project_id, id)` 作为永久父关系。
- Finding 的 `requirement_id` / `requirement_revision_id` 仅表达审查上下文，不替代父 Review 外键；父 Review 已有关联需求时，Finding 上下文必须与其 NULL-safe 相等，未关联时必须成对为空。
- Requirement attachment 的 `source_requirement_id` 与关系表只能存在一个事实来源；若保留两列，必须用复合约束/触发器和同事务写入证明一致。

### Review 事务与恢复

```text
SCM webhook
  -> 验签并取得权威快照
  -> 同一事务更新 PR + 幂等创建 PENDING Review
  -> commit
  -> after-commit 提交执行器
  -> 原子领取(PENDING -> RUNNING, attempt/token)
  -> token 条件完成或失败
  -> reconciliation 只恢复已存在的未执行/停滞任务
```

事务提交前不得启动 Worker。after-commit 提交失败不回滚业务事务，保留 PENDING；旧 Worker 不得凭过期 token 写入结果。

### Review 身份与 Decision

- Review identity 需要表达 `pull_request + 实际输入 fingerprint + requirement revision`；fingerprint 至少覆盖 base/head 和 Provider 返回的 changed-file/patch 版本。
- 当前有效性要求 Review 输入 fingerprint 等于 PR 当前 fingerprint。
- Decision gate 仍以同一 PR/head 的 REQUEST_CHANGES 为闸门，解除必须新 head。
- Decision 只允许 `PENDING -> APPROVE | REQUEST_CHANGES` 一次；并发请求在 PR 行锁和条件更新下只有一个成功。

### 关联与活动

- LEADER 始终可修正关联；作者在当前 head 尚无人工终局 Decision 时可修正，自动 PENDING 不应制造不可达窗口。
- 关联或需求 Revision 变化不覆盖历史 Review，也不自动运行新 Review。
- 派生 activity 增加 `REVIEW_REQUIRED`/`STALE`，并为单 PR 状态和多 PR 聚合定义确定性映射。

### SCM 与 Finding

- 仓库身份包含 provider、规范化实例身份和 external id；可变 API 地址不得改变外部身份。
- Webhook 是触发信号，权威状态来自重新读取 Provider 快照；乱序事件不得回退 head。
- Finding continuity 的抑制条件为同一 PR、同一 finding_key、同一源码 evidence_hash 和同一 authoritative basis_hash；NOT_REPORTED 仍只查询推导。

## 兼容与取舍

- 不新增 `review_decision`、`webhook_delivery` 或执行任务表；审计和恢复通过现有 Review、ai_call_log、finding_event 及结构化日志表达。
- 具体数据库触发器、Provider version 字段和 token 存储方式在 Phase 5/6 设计阶段落地；本任务冻结其行为，不预埋实现抽象。
- 分散 ADR 收敛为 DECISIONS 的 D001–D011；ARCHITECTURE 承接实现所需的完整约束，避免删除 ADR 后丢失可执行细节。
- 最终执行方案中的阶段闸门、统一 result 模板和测试/评测纪律并入 IMPLEMENTATION-PLAN；AI 接手状态并入 README/AGENTS，清理说明并入迁移矩阵或由 Git 历史保存。

## 风险与回滚

- 若实施阶段发现某项约束无法由 PostgreSQL/JPA 表达，必须在对应 Phase 停止并新增决策记录；不得静默退回到运行时纪律。
- R2.3 文档修订可通过单一治理提交回滚，不影响运行时代码（当前无业务代码）；被删除文档可从 Git 历史恢复。
