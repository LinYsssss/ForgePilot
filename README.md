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

当前仓库是 **ForgePilot V2 干净骨架**，暂时不包含业务实现。旧版完整源码、评测资产和历史工程能力保存在 [RepoSage](https://github.com/LinYsssss/reposage)，只作为 Legacy Reference，不直接复制回本仓库。

## 从这里开始

AI 或开发者进入仓库后，按以下顺序阅读：

1. [最终执行方案](docs/v2/FINAL-EXECUTION-PLAN.md)
2. [AI 接手说明](docs/v2/AI-HANDOFF.md)
3. [产品需求](docs/v2/PRD.md)
4. [架构规范](docs/v2/ARCHITECTURE.md)
5. [实施蓝图](docs/v2/IMPLEMENTATION-PLAN.md)
6. [Legacy 迁移矩阵](docs/v2/LEGACY-MIGRATION-MATRIX.md)
7. [架构决策记录](docs/v2/adr/README.md)

## 仓库结构

```text
backend/       Spring Boot 模块化单体，等待 Phase 1 初始化
frontend/      Vue 3 前端，等待对应纵向切片初始化
evaluation/    论文评测与可复现实验入口
docs/v2/       产品与架构的唯一事实源
.trellis/      最小计划、任务与交接规则
```

## 核心边界

- 采用模块化单体，不以“企业级”为理由增加微服务和中间件。
- 只有一条 Review Engine；PR 自动触发和人工重试共用同一入口。
- AI 只提供需求检查、一次性实现建议和 PR 审查，不自动修改代码或改变业务状态。
- 项目知识使用 PostgreSQL + pgvector，严格按 `project_id` 隔离。
- Agent、Patch、RabbitMQ、Outbox、Risk Model、Sandbox 不进入 V2 主线。
- 旧代码只能按 `KEEP / REWRITE / REFERENCE / DROP` 决策逐项提取。

## 当前状态

Phase 0：旧系统归档、V2 最终方案和 Trellis 三端初始化已完成，方案已于 2026-08-19 获用户批准。当前指令仅为提交并推送治理文件；在用户另行要求开始 Phase 1 前，仍禁止创建业务代码。

## License

[MIT](LICENSE)
