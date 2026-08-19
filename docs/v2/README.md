# ForgePilot V2 开发方案

**围绕需求驱动 PR 审查的轻量级 AI 研发协作平台。**

状态：**Final R2 已获用户批准（2026-08-19）**。当前尚未收到开始 Phase 1 的指令，因此仍禁止创建业务代码。

---

## 阅读顺序

| # | 文档 | 是什么 | 什么时候读 |
|---|---|---|---|
| 1 | [FINAL-EXECUTION-PLAN.md](./FINAL-EXECUTION-PLAN.md) | **执行入口**：最终范围、阶段、闸门和授权方式 | 后续恢复上下文与阶段授权 |
| 2 | [PRD.md](./PRD.md) | **产品权威**：定位、角色权限、范围、状态、验收 | 想知道"做什么、给谁用" |
| 3 | [ARCHITECTURE.md](./ARCHITECTURE.md) | **技术权威**：模块边界、依赖、16 表、流程契约、运行边界 | 想知道"怎么建、边界在哪" |
| 4 | [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) | 阶段顺序与退出条件 | 想知道"先做哪一步" |
| 5 | [adr/](./adr/README.md) | 架构决策与理由 | 想知道"为什么这么定" |
| 6 | [LEGACY-MIGRATION-MATRIX.md](./LEGACY-MIGRATION-MATRIX.md) | Legacy 资产 KEEP/REWRITE/REFERENCE/DROP | 实施某模块前查"旧代码能不能用" |
| 7 | [AI-HANDOFF.md](./AI-HANDOFF.md) | AI 接手边界与当前阶段 | 新会话或新 Agent 开始工作 |

旧版实现和过程记录已经归档到 [RepoSage](https://github.com/LinYsssss/reposage)，不再保留在本仓库。

## 单一事实源纪律

一件事只在一个地方定义，其他地方引用：

- 16 表、依赖规则、状态机、运行边界 → 只在 `ARCHITECTURE.md`
- 角色权限、MVP 范围、验收标准 → 只在 `PRD.md`
- 决策理由 → 只在 `adr/`

发现两处说同一件事，删掉其中一处改为链接。**违反此纪律的文档漂移是本项目上一版失控的直接原因。**

## 一句话主流程

> 负责人创建并指派带 AC 的需求，开发者先获得边界明确的 AI 实现建议，再提交关联 PR；ForgePilot 结合需求、项目知识与 Diff 生成可核验 Finding，Reviewer 据此退回或通过，开发者修复后复审闭环。

## 不可违反的边界

- Legacy 代码位于 RepoSage，**只读**且只作能力来源；V2 不在旧架构上堆叠。
- 只有 8 个顶层包：`common/auth/project/requirement/scm/knowledge/ai/review`。
- 禁止：Agent、Patch、MQ/Outbox、第二 Review Engine、第二 AI runtime、本地 Git/clone、向量双写、运行时 DDL。
- AI 实现建议只允许一次性结构化输出；聊天历史、SSE、Agent 和通用问答仍不进入 MVP。

## 决策速查

| ADR | 一句话 |
|---|---|
| [001](./adr/ADR-001-embedding-schema.md) | 向量列不带维度，V1 不建索引，Phase 4 再建 |
| [002](./adr/ADR-002-large-pr-review.md) | 大 PR 分批产证据，最后统一合成一份报告 |
| [003](./adr/ADR-003-review-identity.md) | Review 业务键 = (PR, head SHA)，版本只是审计字段 |
| [004](./adr/ADR-004-domain-cardinality.md) | 每项目一个 LEADER；一个需求可有多个 PR |
| [005](./adr/ADR-005-requirement-attachment-retrieval-boundary.md) | 需求附件不跨需求召回 |
| [006](./adr/ADR-006-cross-project-referential-integrity.md) | 跨项目引用由复合外键拒绝，不写运行时校验 |
| [007](./adr/ADR-007-pr-requirement-association.md) | PR 关联需求：解析 `REQ-N` 优先，页面可改，变更留痕 |
| [008](./adr/ADR-008-review-context-and-recovery.md) | Review 保存上下文快照，PENDING 先落库并支持轻量恢复 |
| [009](./adr/ADR-009-finding-continuity.md) | Finding 每轮独立快照 + 跨 Review 血缘，误报不用重复驳回 |
| [010](./adr/ADR-010-scm-identity-and-repository-immutability.md) | 用项目级 SCM 稳定外部 ID 判定"本人 PR"；有 PR 后仓库不可换 |
| [011](./adr/ADR-011-requirement-revision-and-state.md) | 删除 `IN_REVIEW`，需求正文与 AC 版本化，评审进展是派生量 |
