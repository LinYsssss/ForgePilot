# ForgePilot AI 工作规则

本仓库是 ForgePilot V2 的干净实现仓库。开始任何工作前必须完整阅读：

1. `docs/v2/AI-HANDOFF.md`
2. `docs/v2/PRD.md`
3. `docs/v2/ARCHITECTURE.md`
4. `docs/v2/IMPLEMENTATION-PLAN.md`
5. 当前涉及的 ADR 与 `LEGACY-MIGRATION-MATRIX.md`
6. `.trellis/workflow.md`

## 单一事实源

- 产品范围、角色、流程和验收：`docs/v2/PRD.md`
- 模块、数据模型、依赖和运行边界：`docs/v2/ARCHITECTURE.md`
- 实施顺序：`docs/v2/IMPLEMENTATION-PLAN.md`
- 决策理由：`docs/v2/adr/`

发生冲突时先修文档，不允许在代码中自行发明第三种规则。

## Legacy 边界

旧系统位于 `https://github.com/LinYsssss/reposage`。它只能作为只读参考：

- 写代码前先查迁移矩阵。
- 不整包复制旧模块，不继承旧 Flyway 历史。
- KEEP 也必须先迁测试，再迁最小实现。
- 禁止把 Agent、Patch、MQ/Outbox、Risk Model、Sandbox、旧双 Review 流程带回 V2。

## 开发纪律

- 一次只推进实施蓝图中的一个 Phase 或一个可验收纵向切片。
- 先建立任务说明和验收标准，再写代码；完成后记录验证证据。
- 只允许 `common/auth/project/requirement/scm/knowledge/ai/review` 八个后端顶层包。
- 新增模块、中间件、数据表或顶层菜单必须先有已发生的业务事实和 ADR。
- 不为未来可能性提前抽象，不保留“以后也许有用”的兼容层。
- 默认使用 PowerShell 7（`pwsh`）。
