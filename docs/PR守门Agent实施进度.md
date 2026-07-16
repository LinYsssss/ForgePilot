# PR 守门 Agent 实施进度

> 本文是长任务的权威续开发入口。继续开发前先读取设计、Roadmap、当前阶段计划、本文、Git 状态和最近提交。

## 1. 工作位置

- 功能分支：`feat/pr-gatekeeper-agent`
- 隔离工作树：`F:\202605New\.worktrees\pr-gatekeeper-agent`
- 基线分支提交：`fad60d2 chore: ignore isolated worktrees`
- 当前阶段：Phase 3 Task 9（依赖缓存准备）尚未开始
- 最新完成任务：Phase 3 Task 8（容器执行策略）

## 2. 已完成范围

### Phase 1：工程基线

已全部完成：可复现前端构建、Java/Node/Maven 版本基线、Flyway、Testcontainers 测试骨架、核心回归测试、CI 和统一质量门。

### Phase 2：Agent 控制面

已全部完成：

- `agent_run`、`agent_step`、`tool_invocation` 持久化和状态机。
- 预算、类型化工具、Review Plan 和结构化模型输出校验。
- 事务 Outbox、Agent 专用 MQ、幂等消费和失败重试。
- 服务重启恢复、Timeline/SSE、旧报告兼容投影和 Micrometer 指标。

对应后续提交：

```text
5bab09d feat: publish agent steps through transactional outbox
6ca9676 feat: schedule idempotent agent steps
b9bff2a feat: recover interrupted agent runs
78e5bbb feat: expose agent run timeline
bf9446f feat: project agent results to legacy reports
426aa48 feat: instrument agent control plane
```

### Phase 3：SCM 与 Sandbox

已完成 Task 1 至 Task 8：

1. SCM installation 和 webhook delivery 持久化。
2. GitHub/GitLab 中立契约。
3. GitHub PR Webhook 验签、归一化和幂等。
4. GitLab MR Webhook 验证、归一化和幂等。
5. Webhook 事件持久化创建 Agent Run。
6. 后端与 Runner 字节兼容的签名 Sandbox Job 协议。
7. 独立 Spring Boot Sandbox Runner：专用 RabbitMQ 队列、签名/过期/重放校验、可替换执行器、无 HTTP 端口、Dockerfile 和 Compose 服务。
8. 固定命令 ID 白名单、Pinned image 校验、`--network none`、只读根文件系统、非 root、CPU/内存/PID 限制、临时工作区、路径/符号链接逃逸防护、超时/取消 kill 和幂等清理。

Task 8 的 Docker CLI 调用通过参数列表执行，不经过 Shell；Runner 镜像内包含固定版本 Docker CLI。真实 Docker daemon 和恶意容器联调仍需在有 Docker 的环境补跑。

对应提交：

```text
24e03c0 feat: persist scm installations and deliveries
1b8423d feat: define scm provider contracts
c22b393 feat: receive github pull request webhooks
e098562 feat: receive gitlab merge request webhooks
944bbf1 feat: start agent runs from scm events
c9ee713 feat: define signed sandbox job protocol
484ee1f feat: add isolated sandbox runner service
<本次提交> feat: enforce sandbox container policy
```

## 3. 最新验证证据

```text
backend: mvn -s .mvn/settings.xml test
结果: 134 tests, 0 failures, 0 errors, 2 skipped

frontend: npm test
结果: 3 passed

frontend: npm run build
结果: PASS

sandbox-runner: mvn package
结果: 12 tests, 0 failures, 0 errors, 0 skipped；Spring Boot 可执行 JAR 打包成功

git diff --check
结果: PASS（当前 Task）
```

后端跳过项仍为：

- `InfrastructureIntegrationTest`
- `LegacySchemaMigrationIntegrationTest`

本机没有 `docker` 命令，因此以下项目尚未验证，不能记录为通过：

- `docker compose config` 和镜像构建。
- RabbitMQ 到 Runner 的真实消息联调。
- Docker Socket、容器网络和宿主路径隔离。
- PostgreSQL/RabbitMQ Testcontainers 集成测试。

## 4. 安全边界与已知限制

- V1 至 V4 Flyway 迁移已冻结，不得修改。
- 后端不得运行仓库控制的命令，也不得挂载 Docker Socket。
- Sandbox Runner 是受信任的单机演示编排组件；Compose 不是恶意多租户隔离边界。
- 分析容器不得继承 Docker Socket、SCM Token、LLM Key 或数据库凭据。
- 当前 nonce 重放保护是 Runner 进程内存级；持久化/分布式防重需在后续安全集成阶段补强。
- 当前默认白名单只包含 `sandbox.health`；仓库读取和语言工具命令必须在后续任务逐项注册，不得绕过白名单或直接调用 Shell。

## 5. 下一步严格顺序

从 Phase 3 Task 9 开始：

1. 为 Maven/Gradle、pip、npm/pnpm/yarn 锁文件定义确定性缓存键。
2. 仅允许独立的依赖准备任务写入缓存；分析任务只读挂载且保持 `--network none`。
3. 缺少依赖时返回 `ENVIRONMENT_INCOMPLETE`，不能生成代码 Finding。
4. 在有 Docker 的环境补跑 Task 8 容器安全测试并记录证据。
5. 独立提交 `feat: prepare isolated dependency caches` 后进入 Task 10。

## 6. 继续开发提示词

```text
请在 F:\202605New\.worktrees\pr-gatekeeper-agent 的 feat/pr-gatekeeper-agent 分支继续 PR 守门 Agent。

先读取 docs/PR守门Agent实施进度.md、Phase 3/4 计划、git status 和最近 20 个提交。
Phase 1、Phase 2 和 Phase 3 Task 1-8 已完成，不要重复实现，不要修改冻结的 V1-V4。

从 Phase 3 Task 9 依赖缓存准备开始，严格 TDD、每个 Task 独立提交。不得在宿主机直接执行仓库命令，不得接受消息中的任意 Shell 命令。完成前运行后端全量测试、前端测试与构建、Runner 测试和 git diff --check。Docker/Testcontainers 不可用导致的未验证项目必须明确记录。
```
