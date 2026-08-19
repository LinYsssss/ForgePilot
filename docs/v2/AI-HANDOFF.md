# ForgePilot V2 AI 接手说明

## 当前事实

- 本仓库已经清除旧系统实现，只保留 V2 方案和空工程骨架。
- 旧版完整代码、评测和工程资产位于 [RepoSage](https://github.com/LinYsssss/reposage)，只读参考。
- Phase 0 最终执行方案已于 2026-08-19 获用户批准。
- 当前最新指令仅为提交并推送治理与方案文件；用户尚未要求开始 **Phase 1：最小绿地底座**。在收到新的开始指令前，不得创建应用代码、依赖清单、数据库迁移、前端脚手架或 CI 实现。

## 产品定义

ForgePilot 是围绕需求驱动 PR 审查建设的轻量级研发协作平台，不是 Jira、DevOps 全家桶或通用 Coding Agent。

```text
项目/成员/仓库
→ 需求、AC 与指派
→ AI 需求检查
→ 开发者获取一次性实现建议
→ 提交关联 PR
→ Requirement + AC + Project Knowledge + Diff
→ 唯一 Review Engine
→ Finding 与证据
→ Reviewer APPROVE / REQUEST_CHANGES
→ 修复、复审
→ LEADER 确认需求完成
```

AI 的三个边界明确的落点：

1. 开发前：检查需求是否完整、明确、可测试。
2. 开发中：在需求详情页生成一次性实现清单、相关项目规则和风险提示；不做聊天历史、Agent 或自动改代码。
3. 开发后：结合需求、AC、项目知识和 PR Diff 生成结构化审查结果。

## 不可改变的决定

- 模块化单体；后端只有 8 个顶层包。
- 一个 Review Engine；不引入 Agent、Patch、MQ/Outbox、Risk Model、Sandbox。
- GitHub 先完成真实闭环，GitLab 以后用同一 Provider contract 验证。
- PostgreSQL 是业务事实源；pgvector 只服务项目知识检索。
- Review 身份为 `(pull_request_id, head_sha)`，引擎版本仅作审计。
- 一个 Requirement 可关联多个 PR；单个 PR 在 MVP 最多关联一个 Requirement。
- PR 通过只结束当前 Review，不自动把 Requirement 置为 DONE。
- Review 必须保存当时使用的 Requirement/AC/Knowledge 上下文快照。
- AI 不得直接改变 Requirement、Finding 或 Review Decision。

## 工作方式

1. 先完整阅读 `FINAL-EXECUTION-PLAN.md`，再读 PRD、Architecture 和当前 Phase。
2. 在 `.trellis/tasks/` 新建一个小任务，明确非目标。
3. 查迁移矩阵后再访问 RepoSage；优先迁测试和安全策略，不复制旧边界。
4. 使用测试驱动完成一个纵向切片。
5. 验证后更新任务结果；不得顺手扩展下一个 Phase。

## 用户批准后的 Phase 1 只允许完成

- 初始化 Spring Boot 与 Vue 工程。
- 配置 PostgreSQL/pgvector、Flyway、测试运行环境。
- 建立 ArchUnit 边界测试和基础 CI。
- 只创建当前底座需要的最小 schema；业务表随对应 Phase 增加。
- 不实现登录、项目、需求、知识、SCM 或 Review 业务。

如果需求与以上边界冲突，先停止写代码并请求人工确认。
