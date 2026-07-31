# RepoSage 企业级多语言 PR 守门 Agent 设计

## 1. 目标

将 RepoSage 从“调用大模型生成代码审查报告的平台”升级为可恢复、可验证、可审计的 PR 守门 Agent，同时展示 Java 后端工程能力与 AI Agent 工程能力。

首版目标：

- 同时接入 GitHub 和 GitLab。
- 支持 Java、Python、JavaScript/TypeScript。
- 通过 Webhook 自动触发 PR 审查。
- 自研 Agent 状态机和工具调用协议。
- 结合静态分析、代码上下文、RAG 与模型完成证据化审查。
- 生成候选 Patch，并在隔离环境中完成编译、扫描和测试验证。
- Patch 仅供人工审批、查看和下载，不自动提交、推送或合并。
- 使用单机 Docker Compose 完成部署和演示。

## 2. 设计原则

- 模型负责制定计划、归纳证据和生成候选修复；Java 服务负责权限、状态、预算、校验和流程控制。
- Agent 只能调用已注册的类型化工具，不能直接执行模型生成的任意 Shell 命令。
- 每个状态转换和工具调用均持久化，服务重启后可以恢复。
- 只有达到证据阈值的问题才能阻止 PR，纯模型推测只能作为提示。
- 所有远程写操作均受人工审批约束。
- 多语言能力通过插件扩展，Agent 内核不包含语言特有逻辑。

## 3. 总体架构

```text
GitHub / GitLab Webhook
          |
          v
事件验签、去重、持久化
          |
          v
Agent Run 状态机
          |
          v
仓库准备 -> 变更分类 -> 审查计划
          |
          v
语言插件并行执行
  |- Java: JavaParser / PMD / SpotBugs / Maven / Gradle
  |- Python: AST / Ruff / Bandit / Pytest
  `- JS/TS: ESLint / Semgrep / TypeScript / Jest / Vitest
          |
          v
RAG + 代码依赖上下文 + 工具证据
          |
          v
问题复核与证据融合
          |
          v
候选 Patch 生成
          |
          v
Sandbox Runner 应用 Patch、编译、扫描和测试
          |
          v
人工审批
          |
          v
回写 GitHub / GitLab PR 评论
```

现有 Spring Boot 服务继续作为控制平面，RabbitMQ 负责任务和步骤调度。新增独立 `sandbox-runner` 执行不可信仓库代码。PostgreSQL 保存 Agent 状态、证据、调用记录与审批记录。

## 4. Agent 状态机

主流程：

```text
RECEIVED
-> PREPARING_REPOSITORY
-> ANALYZING_CHANGE
-> PLANNING
-> EXECUTING_TOOLS
-> RETRIEVING_CONTEXT
-> VERIFYING_FINDINGS
-> GENERATING_PATCH
-> VALIDATING_PATCH
-> WAITING_APPROVAL
-> PUBLISHING_RESULT
-> COMPLETED
```

异常状态：

```text
RETRY_WAIT
FAILED
CANCELED
TIMED_OUT
```

状态约束：

- 每一步开始前创建 `agent_step`，结束时保存输出摘要、耗时和状态。
- 只有前一步成功，状态机才能进入下一步。
- 可重试异常进入 `RETRY_WAIT`，达到最大次数后进入 `FAILED`。
- 服务启动时扫描非终态运行，根据最后一个成功步骤恢复。
- 取消和超时是终态，后续消息必须幂等忽略。
- `WAITING_APPROVAL` 不占用 Worker，审批后重新投递继续执行。

## 5. 工具调用协议

统一接口：

```java
interface AgentTool<I, O> {
    String name();
    ToolSchema schema();
    ToolRiskLevel riskLevel();
    ToolResult<O> execute(ToolContext context, I input);
}
```

`ToolContext` 至少包含：

- `agentRunId`
- `stepId`
- `projectId`
- `repositoryId`
- `commitSha`
- `workspaceId`
- 剩余时间、Token、费用和调用次数预算

首批工具：

| 工具 | 作用 | 风险 |
| --- | --- | --- |
| `git.diff` | 读取 PR 变更 | READ_ONLY |
| `git.file` | 读取指定版本文件 | READ_ONLY |
| `code.search` | 搜索符号、引用和调用关系 | READ_ONLY |
| `static.scan` | 调用语言插件静态分析 | SANDBOXED |
| `test.run` | 在沙箱执行白名单测试命令 | SANDBOXED |
| `knowledge.search` | 检索规范和历史缺陷 | READ_ONLY |
| `patch.generate` | 生成统一 Diff | GENERATIVE |
| `patch.apply` | 在临时副本应用 Patch | SANDBOXED |
| `patch.validate` | 编译、扫描和测试候选 Patch | SANDBOXED |
| `scm.comment` | 回写 PR 评论 | APPROVAL_REQUIRED |

模型输出的计划必须经过 Schema 校验、工具白名单校验、参数校验和预算校验。Agent 内核拒绝未知工具、越权工具和任意命令字符串。

## 6. 多语言插件

统一接口：

```java
interface LanguagePlugin {
    boolean supports(RepositoryProfile profile);
    ChangeAnalysis analyze(ChangeSet changes);
    List<ToolCommand> staticAnalysis(ChangeSet changes);
    List<ToolCommand> testCommands(RepositoryProfile profile);
    List<CodeContext> resolveContext(FindingCandidate candidate);
}
```

### 6.1 Java

- 识别 Maven、Gradle 和 Spring Boot 项目。
- 使用 JavaParser 提取类、方法、注解、调用关系和变更符号。
- 接入 PMD、SpotBugs、Checkstyle。
- 支持 Maven/Gradle 编译与 JUnit 测试。
- 重点识别权限、事务、空指针、资源泄漏、SQL、并发和 Spring 配置问题。

### 6.2 Python

- 使用 Python AST 提取符号和导入关系。
- 接入 Ruff、Bandit。
- 支持 Pytest。
- 重点识别注入、危险反序列化、异常吞噬、异步误用和依赖安全问题。

### 6.3 JavaScript/TypeScript

- 识别 npm、pnpm、yarn 项目。
- 接入 ESLint、Semgrep、TypeScript Compiler。
- 支持 Jest、Vitest。
- 重点识别类型逃逸、异步错误、注入、原型污染和前后端权限假设问题。

一个 PR 包含多种语言时，各插件并行执行，结果转换为统一 `FindingCandidate`，再由证据融合模块去重和复核。

## 7. 沙箱执行

新增独立 `sandbox-runner`：

```text
Backend -> RabbitMQ -> Sandbox Runner -> 临时 Docker 容器
```

安全约束：

- 默认禁止容器网络。
- 原始仓库只读挂载，Patch 应用到临时副本。
- 使用非 root 用户。
- 限制 CPU、内存、进程数、磁盘和执行时间。
- 工具命令必须来自语言插件白名单。
- 不向容器传递 SCM Token、模型 Key 和生产数据库凭据。
- 日志自动脱敏环境变量、Token、密码和私钥。
- 执行结束后删除容器和临时工作区。
- 镜像使用固定版本或 digest，保证评测可复现。

依赖缺失或需要联网下载时，任务返回 `ENVIRONMENT_INCOMPLETE`，不能把环境问题误判为代码问题。

## 8. 证据与可信度

每个问题保存：

- `codeEvidence`：commit SHA、文件、行号、代码片段。
- `toolEvidence`：工具、规则 ID、版本、原始结果摘要。
- `knowledgeEvidence`：文档、章节、版本、检索分数。
- `validationEvidence`：测试或最小复现结果。
- `reasoningSummary`：面向用户的简短判断依据，不保存模型内部思维过程。
- `confidence`：由后端规则计算。

基础评分：

```text
静态工具命中          +0.35
精确代码位置可复现    +0.20
知识库规则支持        +0.20
独立复核模型同意      +0.15
测试成功复现          +0.10
证据冲突或位置失效    按规则扣分
```

分级策略：

- `confidence >= 0.75` 且严重级别为 HIGH：允许阻止 PR。
- `0.50 <= confidence < 0.75`：标记为需要人工复核。
- `confidence < 0.50`：仅作为提示，不影响 PR 门禁。
- 没有有效代码位置的问题不得阻止 PR。

阈值必须可配置，并通过固定评测集校准，而不是长期依赖人工拍值。

## 9. Patch 闭环

```text
生成 Patch
-> 校验统一 Diff 格式
-> 校验修改文件白名单
-> 限制文件数、行数和总大小
-> 临时工作区应用
-> 编译
-> 静态扫描
-> 测试
-> 比较修复前后结果
-> 创建人工审批请求
```

Patch 限制：

- 不允许修改 CI 密钥、部署凭据和仓库权限配置。
- 不允许新增二进制文件。
- 不允许修改超出问题范围的大量文件。
- 验证失败的 Patch 可以展示，但必须标记失败原因，不能进入可批准状态。
- 审批只表示允许系统发布评论和提供 Patch，不表示允许自动推送或合并。

## 10. SCM 接入

定义统一接口：

```java
interface ScmProvider {
    WebhookEvent verifyAndParse(WebhookRequest request);
    PullRequestSnapshot getPullRequest(ScmContext context);
    RepositoryArchive fetchRepository(ScmContext context);
    PublishResult publishReview(ScmContext context, ReviewPublication publication);
}
```

GitHub 首版支持：

- Webhook HMAC 验签。
- `pull_request` 和 `synchronize` 事件。
- PR 评论或 Check Run 发布。

GitLab 首版支持：

- Secret Token 验证。
- Merge Request Hook。
- MR Note 或 Commit Status 发布。

`webhook_delivery` 以 provider、installation/project 和 delivery ID 建立唯一约束，防止重复事件创建多个 Agent Run。

## 11. 数据模型

新增核心表：

- `agent_run`：一次 PR 审查运行、当前状态、预算和最终结论。
- `agent_step`：步骤状态、输入输出摘要、重试次数和时间。
- `tool_invocation`：工具输入、输出、耗时、错误和预算消耗。
- `review_plan`：模型计划、Schema 版本和校验结果。
- `finding`：标准化问题、风险级别、置信度和门禁决策。
- `finding_evidence`：代码、工具、知识库和验证证据。
- `patch_candidate`：Patch 内容、范围、验证状态和风险。
- `approval_request`：审批人、审批结果、意见和时间。
- `webhook_delivery`：事件原文摘要、验签结果和幂等状态。
- `scm_installation`：加密后的 GitHub/GitLab 连接配置。

现有 `review_task`、`review_report`、`review_issue` 保留兼容。迁移期由适配层将 `agent_run` 结果投影为旧报告结构，前端完成迁移后再决定是否删除旧模型。

数据库版本管理改用 Flyway，禁止继续依赖手写生产初始化脚本修改已有表。

## 12. API

Webhook：

- `POST /api/webhooks/github`
- `POST /api/webhooks/gitlab`

Agent：

- `GET /api/agent-runs/{id}`
- `GET /api/agent-runs/{id}/timeline`
- `GET /api/agent-runs/{id}/findings`
- `POST /api/agent-runs/{id}/retry`
- `POST /api/agent-runs/{id}/cancel`
- `GET /api/agent-runs/{id}/events`

Patch 与审批：

- `GET /api/agent-runs/{id}/patches`
- `GET /api/patches/{id}`
- `POST /api/patches/{id}/approve`
- `POST /api/patches/{id}/reject`
- `GET /api/patches/{id}/download`

进度使用 SSE 推送。数据库状态是事实来源，SSE 断线后客户端通过查询接口恢复，不依赖内存事件。

## 13. 可观测性

使用以下关联关系贯穿日志、指标和链路：

```text
traceId -> agentRunId -> stepId -> toolInvocationId
```

指标：

- Webhook 验签失败率和重复率。
- Agent 成功率、失败率、恢复率和总耗时。
- 各步骤 P50/P95/P99 耗时。
- RabbitMQ 队列深度、重试和死信数量。
- 各工具成功率、超时率和耗时。
- 模型 Token、费用、超时和结构化输出失败率。
- Finding 人工确认率、误报率。
- Patch 应用成功率、构建成功率和测试成功率。

技术选择：

- Micrometer + Prometheus 提供指标。
- OpenTelemetry 追踪 Webhook、RabbitMQ、数据库、模型和沙箱调用。
- AI 日志只保存脱敏摘要、哈希和指标，不保存密钥及完整私有代码。

## 14. 预算与错误处理

每个 Agent Run 配置：

- 最大执行时间。
- 最大工具调用次数。
- 最大模型调用次数。
- 最大输入和输出 Token。
- 最大费用。
- 最大重试次数。

错误分类：

- `RETRYABLE_EXTERNAL_ERROR`：模型超时、临时网络错误。
- `INVALID_MODEL_OUTPUT`：模型输出无法通过 Schema。
- `TOOL_EXECUTION_FAILED`：工具返回非预期错误。
- `ENVIRONMENT_INCOMPLETE`：缺少依赖或构建环境。
- `SECURITY_POLICY_VIOLATION`：命令、路径或权限越界。
- `BUDGET_EXCEEDED`：时间、Token、费用或调用次数超限。

错误分类决定是否重试、降级或终止。禁止对所有异常统一重试。

## 15. 测试与评测

### 15.1 自动化测试

- 单元测试：状态迁移、计划校验、预算控制、置信度、Webhook 验签。
- 集成测试：Testcontainers 启动 PostgreSQL、RabbitMQ。
- 契约测试：GitHub/GitLab Webhook 样例和发布 API。
- 插件测试：每种语言维护正常、缺陷、构建失败样例仓库。
- 恢复测试：MQ 重复消息、服务重启、步骤超时和死信恢复。
- 安全测试：Prompt Injection、命令注入、路径穿越、符号链接逃逸和恶意构建脚本。

### 15.2 Agent 评测

维护版本化评测集，每条样例包含：

- PR Diff。
- 相关上下文。
- 预期问题、严重级别和证据位置。
- 不应报告的问题。
- 可选参考 Patch。

核心指标：

- Finding Precision、Recall、F1。
- 高风险问题 Recall。
- 误报率。
- 证据位置准确率。
- Patch 应用率、构建通过率、测试通过率。
- 单次 Agent Run 平均时间和成本。

每次修改 Prompt、模型、工具或置信度规则后运行回归评测。

## 16. 实施阶段

### 阶段一：工程基线

- 修复当前前端生产构建。
- 引入 Flyway。
- 增加 Testcontainers 和 CI。
- 补齐 AI、RAG、MQ、Git 核心链路测试。
- 拆分过大的前端单文件和后端职责。

### 阶段二：Agent 内核

- 建立 `agent_run`、`agent_step` 和 `tool_invocation`。
- 实现状态机、预算、工具注册表和持久化恢复。
- 使用 RabbitMQ 调度步骤。
- 增加 SSE 时间线。

### 阶段三：PR 与多语言闭环

- 接入 GitHub/GitLab Webhook。
- 实现三种语言插件。
- 实现隔离 Sandbox Runner。
- 完成证据统一模型和 PR 报告发布。

### 阶段四：智能修复与评测

- 增加 Finding 复核和置信度计算。
- 实现 Patch 生成、应用和验证。
- 增加人工审批。
- 建立评测集和质量看板。

## 17. 明确不做

首版不实现：

- Kubernetes 部署。
- 自动提交、推送或合并 Patch。
- 多 Agent 自由对话。
- 大模型微调。
- 超过 Java、Python、JavaScript/TypeScript 的语言插件。
- 复杂企业组织和权限系统。

## 18. 验收标准

- GitHub 和 GitLab 的 PR/MR 事件均能通过验签、幂等创建 Agent Run。
- Agent Run 可展示完整步骤时间线，并能在服务重启后恢复。
- 三种语言的样例仓库均能执行静态分析和测试。
- 所有仓库代码均在隔离 Runner 中执行，后端不直接运行仓库命令。
- 每个阻断问题都有有效代码位置和至少一种额外证据。
- 候选 Patch 能在临时工作区应用并产生明确验证结果。
- 用户能够批准、拒绝和下载 Patch，但系统不会自动修改远程仓库。
- GitHub/GitLab PR 页面能够收到结构化审查结果。
- 自动化测试覆盖状态机、Webhook、幂等、恢复、预算和安全边界。
- 固定评测集能够输出准确率、召回率、误报率和 Patch 验证率。

## 19. 简历表达

> 设计并实现企业级多语言 PR 守门 Agent，自研可恢复状态机与类型化工具调用协议，集成 GitHub/GitLab Webhook、RabbitMQ、RAG、静态分析和隔离沙箱，实现证据化代码审查、候选 Patch 生成、自动验证及人工审批闭环；通过幂等调度、预算控制、OpenTelemetry 和 Agent 评测集保障系统可靠性与可观测性。
