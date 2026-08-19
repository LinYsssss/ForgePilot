# ADR-004 Domain Cardinality：LEADER 唯一性与 Requirement 1:N PullRequest

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §2.1、[PRD.md](../PRD.md) §3 · §6 P2

## 背景

"每项目一个 LEADER"与"一个 Requirement 零或一个 PR"此前只是文字描述，14 表定义中没有
对应的数据库约束；同时"一个 Requirement 最多一个 PR"与真实研发行为不符
（PR 可能被关闭重开、拆分提交）。

## 决策

1. 一个 Project **最多一个 LEADER**，由 PostgreSQL 部分唯一索引保证：
   `CREATE UNIQUE INDEX ON project_member(project_id) WHERE role = 'LEADER'`；
   Service Transaction 必须额外保证项目**至少存在一个 LEADER**（至多 + 至少 = 恰好一个）。
2. 删除"一个 Requirement 最多一个 PullRequest"的设计。
3. 修改为 **Requirement 1:N PullRequest**。
4. MVP 中一个 PullRequest 最多关联一个 Requirement（`pull_request.requirement_id` 单列 FK）。
5. `pull_request.requirement_id` 只建**普通索引**，不建 UNIQUE。

## 后果与实施注记

- "至少一个 LEADER"在成员移除/角色变更/所有权转移的事务内校验，违反则整体回滚。
- Requirement 详情页展示关联 PR 列表；多 PR 时 Requirement 的 DONE 判定仍由人工
  （LEADER/Reviewer）决定，状态机不自动聚合多 PR 的 Review 结果。
- Review 幂等与复审语义不变：每个 PR 的每个 head SHA 独立产生 Review（ADR-003）。
- 出现真实"一个 PR 关联多个 Requirement"需求时再另行 ADR，MVP 不做多对多。
