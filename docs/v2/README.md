# ForgePilot V2 开发方案

**基于需求与项目知识上下文增强的 AI 代码审查系统。**

状态：**Phase 0 已冻结（2026-08-19）**，可进入 Phase 1 实施。

---

## 阅读顺序

| # | 文档 | 是什么 | 什么时候读 |
|---|---|---|---|
| 1 | [PRD.md](./PRD.md) | **产品权威**：定位、角色权限、范围、状态、验收 | 想知道"做什么、给谁用" |
| 2 | [ARCHITECTURE.md](./ARCHITECTURE.md) | **技术权威**：模块边界、依赖、14 表、流程契约、运行边界 | 想知道"怎么建、边界在哪" |
| 3 | [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) | 阶段顺序与退出条件 | 想知道"先做哪一步" |
| 4 | [adr/](./adr/README.md) | 7 条争议决策的理由 | 想知道"为什么这么定" |
| 5 | [LEGACY-MIGRATION-MATRIX.md](./LEGACY-MIGRATION-MATRIX.md) | Legacy 资产 KEEP/REWRITE/REFERENCE/DROP | 实施某模块前查"旧代码能不能用" |

[archive/](./archive/) 是过程记录（Legacy 审计、交叉审查、R2 复检报告），只作证据留存，**不是规范**。

## 单一事实源纪律

一件事只在一个地方定义，其他地方引用：

- 14 表、依赖规则、状态机、运行边界 → 只在 `ARCHITECTURE.md`
- 角色权限、MVP 范围、验收标准 → 只在 `PRD.md`
- 决策理由 → 只在 `adr/`

发现两处说同一件事，删掉其中一处改为链接。**违反此纪律的文档漂移是本项目上一版失控的直接原因。**

## 一句话主流程

> 负责人创建并指派带 AC 的需求，开发者提交关联 PR 后，ForgePilot 结合需求、项目知识与 Diff 生成可核验 Finding，Reviewer 据此退回或通过，开发者修复后复审闭环。

## 不可违反的边界

- Legacy 代码**只读**，只作能力来源；V2 是独立绿地工程，不在旧架构上堆叠。
- 只有 8 个顶层包：`common/auth/project/requirement/scm/knowledge/ai/review`。
- 禁止：Agent、Patch、MQ/Outbox、第二 Review Engine、第二 AI runtime、本地 Git/clone、向量双写、运行时 DDL。
- 任何 P1 功能不得插入核心 Phase。

## 决策速查

| ADR | 一句话 |
|---|---|
| [001](./adr/ADR-001-embedding-schema.md) | 向量列不带维度，V1 不建索引，Phase 4 再建 |
| [002](./adr/ADR-002-large-pr-review.md) | 大 PR 分批产证据，最后统一合成一份报告 |
| [003](./adr/ADR-003-review-identity.md) | Review 业务键 = (PR, head SHA)，版本只是审计字段 |
| [004](./adr/ADR-004-domain-cardinality.md) | 每项目一个 LEADER；一个需求可有多个 PR |
| [005](./adr/ADR-005-requirement-attachment-retrieval-boundary.md) | 需求附件不跨需求召回 |
| [006](./adr/ADR-006-cross-project-referential-integrity.md) | 跨项目引用由复合外键拒绝，不写运行时校验 |
| [007](./adr/ADR-007-pr-requirement-association.md) | PR 关联需求：解析 `REQ-N` 优先，页面可改 |
