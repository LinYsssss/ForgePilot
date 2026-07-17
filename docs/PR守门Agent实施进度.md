# PR 守门 Agent 实施进度

> 本文是长任务的权威续开发入口。继续开发前先读取设计、Roadmap、当前阶段计划、本文、Git 状态和最近提交。

## 1. 工作位置

- 功能分支：`feat/pr-gatekeeper-agent`
- 隔离工作树：`F:\202605New\.worktrees\pr-gatekeeper-agent`
- 基线分支提交：`fad60d2 chore: ignore isolated worktrees`
- 当前阶段：Phase 4 Task 5（确定性证据置信度；Phase 3 Docker 动态验收待补跑）
- 最新完成任务：Phase 4 Task 4（JavaScript/TypeScript 插件）

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

已完成 Task 1 至 Task 12 的代码与静态范围；Task 12 的 Docker 动态验收待补跑：

1. SCM installation 和 webhook delivery 持久化。
2. GitHub/GitLab 中立契约。
3. GitHub PR Webhook 验签、归一化和幂等。
4. GitLab MR Webhook 验证、归一化和幂等。
5. Webhook 事件持久化创建 Agent Run。
6. 后端与 Runner 字节兼容的签名 Sandbox Job 协议。
7. 独立 Spring Boot Sandbox Runner：专用 RabbitMQ 队列、签名/过期/重放校验、可替换执行器、无 HTTP 端口、Dockerfile 和 Compose 服务。
8. 固定命令 ID 白名单、Pinned image 校验、`--network none`、只读根文件系统、非 root、CPU/内存/PID 限制、临时工作区、路径/符号链接逃逸防护、超时/取消 kill 和幂等清理。
9. Maven/Gradle/Python/npm/pnpm/yarn 锁文件内容寻址缓存键、独立联网准备策略、安全环境变量白名单、分析任务只读缓存挂载和缺缓存 `ENVIRONMENT_INCOMPLETE` 结果。
10. ZIP/TAR/压缩归档安全解包、绝对路径/遍历/符号链接和私网子模块 URL 防护；Runner `repo.unpack`、`git.file`、`git.diff`、`code.search` 有界处理器；后端三个只读 Agent 工具及签名 RabbitMQ 网关。
11. GitHub Check Run/PR comment 与 GitLab Commit Status/MR Note 发布客户端；发布内容包含摘要、阻断 Finding、证据链接、Agent Run URL 和 Patch 状态；暴露 Patch 内容必须审批；WireMock 契约测试覆盖请求头、关键字段和未审批零请求拒绝。
12. 已新增 PostgreSQL/RabbitMQ Testcontainers 的 GitHub Webhook→Agent Run 集成测试，Compose 改为必填数据库/MQ 密码并将 RabbitMQ、backend、model-service 宿主端口绑定回环地址；新增 SCM/Sandbox 运维与安全验收手册。当前主机无 Docker，集成测试和容器隔离动态验收仍未通过。

依赖缓存只有存在 `.complete` 标记才视为可用；准备请求受命令、时限、大小和环境变量白名单约束。真实依赖下载、Docker 挂载和 RabbitMQ Runner 请求/响应联调仍需在有 Docker/RabbitMQ 的环境补跑。

对应提交：

```text
24e03c0 feat: persist scm installations and deliveries
1b8423d feat: define scm provider contracts
c22b393 feat: receive github pull request webhooks
e098562 feat: receive gitlab merge request webhooks
944bbf1 feat: start agent runs from scm events
c9ee713 feat: define signed sandbox job protocol
484ee1f feat: add isolated sandbox runner service
aed5f6e feat: enforce sandbox container policy
05e69c3 feat: prepare isolated dependency caches
a4a6f6f feat: execute repository read tools in sandbox
52efc6b feat: publish agent reviews to scm providers
827ebd7 docs: document scm and sandbox operations
```

### Phase 4：插件、Patch 与评测

已完成 Task 1 至 Task 4：

- `RepositoryProfile`、`ChangeSet`、`ChangeAnalysis`、`ToolCommand` 和 `LanguagePlugin` 契约。
- 纯语言、混合语言和构建文件变更的确定性插件选择。
- `FindingCandidate`、`FindingEvidence`、Finding/Evidence JPA 持久化；证据包含类型、来源版本、文件/行、2048 字符有界摘录、分数和原文 SHA-256。
- 因仓库已存在 SCM `V7__scm_webhooks.sql`，Finding/Evidence 迁移安全顺延为 `V8__findings_and_evidence.sql`；后续 Patch 迁移顺延为 V9，不修改既有迁移。
- Java 插件检测 Maven/Gradle，使用 JavaParser 提取变更类、方法、注解和调用；PMD、SpotBugs、Checkstyle XML 及 SARIF 归一化为 `FindingCandidate`。
- backend 和 Runner 同步注册 Maven/Gradle compile/test、PMD、SpotBugs、Checkstyle 固定命令 ID；Runner 使用固定可执行路径与参数，不调用 shell 解释器。
- Java Maven/Gradle 评测夹具位于 `demo-repos/evaluation/java/`。
- Python 插件检测 `pyproject.toml`、`requirements.txt` 和 `.py` 变更，注册 Ruff、Bandit、Pytest 固定命令；Ruff/Bandit JSON 归一化为 Finding，Pytest JUnit 归一化为独立验证结果而非缺陷。
- Runner 同步注册固定 Ruff/Bandit/Pytest 可执行路径；Python 评测夹具位于 `demo-repos/evaluation/python/`。
- JavaScript/TypeScript 插件检测 npm/pnpm/yarn、TypeScript、Jest/Vitest；ESLint/Semgrep/tsc/Jest/Vitest 均通过 Runner 固定二进制执行，不调用 `npm run`、`npx` 或 payload 中的 scripts。
- ESLint/Semgrep/TypeScript 输出归一化为 Finding，Jest/Vitest JSON 归一化为验证结果；恶意 `package.json` script 夹具证明其不会进入命令契约。

对应提交：

```text
92dcd30 feat: define language plugin and evidence contracts
881c066 feat: add java analysis plugin
1c2fa5b feat: add python analysis plugin
Task 4（本次提交） feat: add javascript typescript analysis plugin
```

## 3. 最新验证证据

```text
backend: mvn test
结果: 161 tests, 0 failures, 0 errors, 3 skipped

frontend: npm test
结果: 3 passed

frontend: npm run build
结果: PASS

sandbox-runner: mvn test
结果: 34 tests, 0 failures, 0 errors, 0 skipped

git diff --check
结果: PASS（当前 Task）
```

后端跳过项仍为：

- `InfrastructureIntegrationTest`
- `LegacySchemaMigrationIntegrationTest`
- `GitHubWebhookAgentRunIntegrationTest`

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
- Runner 仍只执行固定注册的命令 ID；仓库读取路由已逐项注册，未注册命令和任意 Shell 输入都会被拒绝。

## 5. 下一步严格顺序

继续记录 Phase 3 Task 12 动态验收缺口，并实施 Phase 4 Task 5：

1. 按固定权重测试 tool `0.35`、location `0.20`、knowledge `0.20`、verifier `0.15`、test `0.10`。
2. 测试冲突证据、过期行号和 `[0,1]` clamp，持久化版本与每项贡献。
3. Gate 仅允许 HIGH/CRITICAL、置信度至少 `0.75` 且有有效代码位置的 Finding 阻断。
4. 每个 Task 独立提交；Docker 动态验收未完成时不得宣称 Phase 3 最终放行。

## 6. 继续开发提示词

```text
请在 F:\202605New\.worktrees\pr-gatekeeper-agent 的 feat/pr-gatekeeper-agent 分支继续 PR 守门 Agent。

先读取 docs/PR守门Agent实施进度.md、Phase 3/4 计划、git status 和最近 20 个提交。
Phase 1、Phase 2 和 Phase 3 Task 1-11 已完成；Task 12 的代码、静态加固和运维文档已完成，但 Docker 动态验收待补跑。不要重复实现，不要修改冻结的 V1-V4。

继续记录 Phase 3 Task 12 的 Docker 阻塞，同时从 Phase 4 Task 5 确定性证据置信度开始，严格 TDD、每个 Task 独立提交。Finding/Evidence 已使用 V8，Patch 迁移必须使用 V9。不得在宿主机直接执行仓库命令，不得接受消息中的任意 Shell 命令。完成前运行后端全量测试、前端测试与构建、Runner 测试和 git diff --check。Docker/Testcontainers 不可用导致的未验证项目必须明确记录。
```
