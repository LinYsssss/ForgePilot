# ForgePilot

ForgePilot 是一个面向软件研发流程的轻量级 AI 研发协作与代码审查平台。

它围绕一条主线建设：

```text
项目与成员
→ 需求与验收条件
→ 指派开发
→ AI 生成实现建议
→ Pull Request
→ 需求/知识上下文增强审查
→ 人工通过或打回
→ 修复与复审
```

**ForgePilot V2 的 Phase 0–8 已于 2026-08-22 全部完成**：后端 8 个业务包、16 张业务表、307 个测试全绿；前端 3 个一级导航、7 条产品路由、32 个测试全绿；三臂对照评测已完成正式运行，holdout 按约定只跑一次。旧版完整源码与历史工程能力保存在 [RepoSage](https://github.com/LinYsssss/reposage)，只作为 Legacy Reference，不直接复制回本仓库。

## 从这里开始

AI 或开发者进入仓库后，按以下顺序阅读：

1. [V2 文档入口与当前状态](docs/v2/README.md)
2. [产品需求](docs/v2/PRD.md)
3. [架构规范](docs/v2/ARCHITECTURE.md)
4. [实施计划与阶段结论](docs/v2/IMPLEMENTATION-PLAN.md)
5. [决策记录](docs/v2/DECISIONS.md)
6. [答辩复现指南](docs/v2/DEFENSE-GUIDE.md)
7. [Legacy 迁移矩阵](docs/v2/LEGACY-MIGRATION-MATRIX.md)

## 仓库结构

```text
backend/       Spring Boot 模块化单体，8 个业务包 / 16 张表 / 7 个 Flyway 迁移
frontend/      Vue 3 + TypeScript + Vite 前端，3 个一级导航 / 7 条产品路由
evaluation/    论文评测与可复现实验入口，含正式三臂实验工具链与冻结配置
docs/v2/       产品与架构的唯一事实源
.trellis/      任务计划、验收证据与交接规则
```

## 核心边界

- 采用模块化单体，不以“企业级”为理由增加微服务和中间件。
- 只有一条 Review Engine；PR 自动触发和人工重试共用同一入口。
- AI 只提供需求检查、一次性实现建议和 PR 审查，不自动修改代码或改变业务状态。
- 项目知识使用 PostgreSQL + pgvector，严格按 `project_id` 隔离。
- Agent、Patch、RabbitMQ、Outbox、Risk Model、Sandbox 不进入 V2 主线。
- 旧代码只能按 `KEEP / REWRITE / REFERENCE / DROP` 决策逐项提取。

## 当前状态

Phase 0–8 全部完成并通过退出闸门，验收证据在 `.trellis/tasks/archive/2026-08/` 下按任务归档。仓库进入**答辩准备期**：功能开发已收口，后续改动仍需按 Trellis 流程立任务，且不得触碰已封存的评测证据。

正式三臂评测结论（全量 38 例，模型 `gpt-5.6-luna`、温度 `0.0`）：

| 对照臂 | 精确率 | 召回率 | 需求违规召回 |
|---|---:|---:|---:|
| 仅 Diff | 12.12% | 12.90% | 0% |
| Diff + 需求/AC | 21.62% | 25.81% | 80% |
| Diff + 需求/AC + 项目知识 | **32.26%** | **32.26%** | **90%** |

holdout 仅 12 例，样本偏小，结论为描述性而非总体推断。复现步骤见 [答辩复现指南](docs/v2/DEFENSE-GUIDE.md)。

## License

[MIT](LICENSE)
