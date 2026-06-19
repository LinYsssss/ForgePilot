# PR 守门 Agent 实施进度

> 本文是长任务的权威续开发入口。继续开发前先读取五份计划、本文、当前 Git 状态和最近提交，不要从头重复 Phase 1。

## 1. 工作位置

- 功能分支：`feat/pr-gatekeeper-agent`
- 隔离工作树：`F:\202605New\.worktrees\pr-gatekeeper-agent`
- 基线分支提交：`fad60d2 chore: ignore isolated worktrees`
- 当前阶段：Phase 2 Task 7（事务 Outbox）尚未开始

## 2. 已完成范围

### Phase 1：工程基线

已全部完成：

- Node 20、Java 17、Maven 3.9+ 基线。
- 前端测试、确定性生产构建和锁文件安装。
- Flyway V1/V2、生产 SQL Init 关闭、旧结构升级测试。
- PostgreSQL/pgvector 与 RabbitMQ Testcontainers 测试骨架。
- Review 重试、取消、RAG 隔离回归测试。
- GitHub Actions 和 `scripts/verify-local.ps1` 统一质量门。
- README 与本地开发手册中的兼容矩阵和迁移规则。

对应提交：

```text
2cb4591 fix: make frontend production build reproducible
ad7da9c build: standardize backend quality baseline
36a70af feat: manage production schema with Flyway
6401912 test: add containerized infrastructure migration tests
419c838 test: cover review retry and RAG isolation
48ba320 ci: enforce reproducible project verification
b09f829 docs: define engineering baseline and migration workflow
```

### Phase 2：Agent 控制面

已完成 Task 1 至 Task 6：

1. `agent_run`、`agent_step` 持久化、步骤顺序唯一约束和乐观锁。
2. 不可变状态迁移表，覆盖主路径、无问题分支、无安全 Patch 分支和审批边界。
3. 时间、工具调用、模型调用、输入/输出 Token 和成本预算守卫。
4. 类型化工具注册表、工具名白名单、严格输入反序列化、审批要求、敏感字段脱敏、输入输出大小限制、`invocation_key` 幂等。
5. Review Plan 持久化和验证：非空、工具存在、单工具次数、审批时序、参数大小。
6. Prompt 信任分区、结构化模型输出、Markdown JSON 剥离、未知字段拒绝、一次 JSON 修复、模型调用元数据审计。

对应提交：

```text
82462e3 feat: persist agent runs and steps
6189206 feat: enforce agent state transitions
7efcd4a feat: enforce agent execution budgets
390731f feat: add typed agent tool registry
61cfbb8 feat: validate model-generated review plans
e11f803 feat: validate structured agent model output
```

数据库迁移：

- `V3__agent_control_plane.sql`
- `V4__review_plan_and_tool_invocation.sql`

V3/V4 现在视为冻结。后续结构变化必须从 V5 起新增迁移，不要再编辑 V1 至 V4。

## 3. 最新验证证据

阶段性回归结果：

```text
backend: mvn -s .mvn/settings.xml test
结果: 52 tests, 0 failures, 0 errors, 2 skipped

frontend: npm test
结果: 3 passed

frontend: npm run build
结果: PASS
```

两个跳过项是：

- `InfrastructureIntegrationTest`
- `LegacySchemaMigrationIntegrationTest`

原因是本机没有可用 Docker。它们不能记作“已通过”，必须由有 Docker 的环境或 GitHub Actions 执行。

## 4. 已知边界

- V3/V4 已通过 H2/JPA 映射测试和 Java 全量测试，但尚未在本机 PostgreSQL 16 + pgvector 上执行，因为 Docker 不可用。
- `AgentToolRegistry` 编译时存在 Jackson 旧 API 警告，不影响测试；后续可把 `ObjectNode.fields()` 迁移到新版遍历 API。
- 当前控制面只完成基础模型和验证边界，尚未形成可运行的端到端 Agent Worker。
- 不要让业务服务直接调用 `RabbitTemplate`；后续消息必须从 Outbox 发布。
- 不要让模型输出决定 Java 类、Spring Bean、队列名、容器镜像、文件路径或 Shell 命令。

## 5. 下一步严格顺序

从 Phase 2 Task 7 继续：

1. 新增 `V5__agent_outbox.sql`。
2. 实现 `AgentOutboxEvent`、Repository、Publisher。
3. 用测试证明状态迁移和 Outbox 插入同事务提交/回滚。
4. MQ 失败保留 `PENDING`，增加 `attempt_count`、`next_attempt_at` 和截断错误。
5. 使用数据库锁或原子 claim，避免多实例重复发布。
6. 然后依次完成 Phase 2 Task 8 至 Task 12：
   - Agent 专用 RabbitMQ 队列与幂等消费；
   - 重启恢复；
   - Timeline API 与 SSE；
   - 旧报告兼容投影；
   - 指标、关联 ID 和 Phase 2 全量验证。
7. Phase 2 完成后再进入 Phase 3 SCM/Webhook/沙箱，不要提前混入。
8. Phase 4 最后实现插件、Patch、审批、评测和 OpenTelemetry。

## 6. 继续开发时的提示词

```text
请在 F:\202605New\.worktrees\pr-gatekeeper-agent 的 feat/pr-gatekeeper-agent 分支继续执行 PR 守门 Agent 长任务。

先读取：
1. docs/PR守门Agent实施进度.md
2. docs/superpowers/plans/2026-06-18-pr-gatekeeper-roadmap.md
3. Phase 2/3/4 三份计划
4. git status 和最近 15 个提交

Phase 1 已完成；Phase 2 Task 1-6 已完成并提交。不要重复实现，也不要修改已经冻结的 V1-V4 Flyway 迁移。

从 Phase 2 Task 7 事务 Outbox 开始，严格测试驱动、每个 Task 独立提交。完成前运行后端全量测试、前端测试与构建。Docker 不可用导致的 Testcontainers SKIP 必须如实记录，不能宣称通过。

如果本轮仍未完成全部 Phase 2-4，更新 docs/PR守门Agent实施进度.md，记录最后完成的提交、测试证据、阻塞和下一步。
```
