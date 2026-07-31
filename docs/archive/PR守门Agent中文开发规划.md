# RepoSage 企业级多语言 PR 守门 Agent 中文开发规划

## 一、项目定位

RepoSage 当前已经具备代码仓库管理、Commit Diff、PR 工作流、RabbitMQ 异步任务、RAG、模型服务、AI 调用日志、反馈闭环和 Docker Compose 部署等能力。

后续改造目标不是继续堆叠普通管理页面，而是将项目升级为：

> 一个面向 GitHub 与 GitLab、支持 Java/Python/JavaScript/TypeScript、能够自动审查 PR、调用工具收集证据、生成候选 Patch、在隔离环境验证并等待人工审批的企业级代码审查 Agent。

该项目需要同时体现两类求职竞争力：

- Java 后端工程能力：状态机、消息队列、事务一致性、幂等、数据库迁移、隔离执行、可观测性和自动化测试。
- AI Agent 工程能力：规划、类型化工具调用、预算控制、结构化输出、证据融合、RAG、修复生成和 Agent 评测。

## 二、最终演示闭环

```text
GitHub/GitLab 创建或更新 PR
        ↓
Webhook 验签、去重和持久化
        ↓
创建 Agent Run
        ↓
分析变更语言和影响范围
        ↓
生成受约束的审查计划
        ↓
调用 Git、代码搜索、静态分析、测试、RAG 等工具
        ↓
融合代码证据、工具证据、知识库证据和测试证据
        ↓
生成可信审查结论
        ↓
生成候选 Patch
        ↓
在隔离沙箱应用 Patch、编译、扫描和测试
        ↓
等待人工批准或拒绝
        ↓
将结构化审查结果回写到 PR/MR
```

系统只生成、验证和展示 Patch，不自动提交、推送或合并远程代码。

## 三、总体架构

### 3.1 控制面

Spring Boot 后端作为控制面，负责：

- Webhook 接收、验签和幂等。
- Agent 状态机与步骤调度。
- 工具注册、权限检查和预算控制。
- 模型调用和结构化输出校验。
- Finding、证据、Patch 和审批持久化。
- GitHub/GitLab 结果回写。
- SSE 进度通知和监控指标。

### 3.2 执行面

独立的 `sandbox-runner` 作为执行面，负责：

- 解压经过校验的仓库归档。
- 运行固定白名单中的静态分析和测试命令。
- 应用并验证候选 Patch。
- 限制网络、CPU、内存、进程数、磁盘和执行时间。
- 清理临时容器和工作目录。

### 3.3 数据与消息

- PostgreSQL：唯一事实来源。
- RabbitMQ：提供至少一次消息投递，不保存权威业务状态。
- 事务 Outbox：保证数据库状态变更与 MQ 事件最终一致。
- SSE：只负责通知，客户端断线后通过数据库查询恢复。
- pgvector：作为混合检索的一部分，不单独决定审查结论。

## 四、不可突破的架构边界

### 4.1 状态与消息

- 状态变更和 Outbox 事件必须在同一个数据库事务中提交。
- 所有 MQ 消费者必须支持重复投递。
- 每个 Agent Step 和 Tool Invocation 都必须具有唯一幂等键。
- 不允许在数据库提交后直接调用 RabbitMQ，并假设消息一定成功。
- PostgreSQL 是权威状态，RabbitMQ 和 SSE 不能替代数据库。

### 4.2 模型与 Agent

- 模型输出属于不可信输入。
- 模型不能直接选择 Java 类、Spring Bean、消息队列、Docker 镜像或 Shell 命令。
- 模型只能从已注册的工具名称中选择工具。
- 工具参数必须经过 Schema、权限、大小、路径和预算校验。
- 仓库代码、Diff、构建日志和知识库文档均可能包含 Prompt Injection，必须作为不可信数据隔离。
- 不保存模型内部思维过程，只保存面向用户的简短判断依据。

### 4.3 沙箱

- 后端服务不能直接运行被审查仓库的命令。
- 后端容器不能挂载 Docker Socket。
- Docker Socket 只能提供给可信的 `sandbox-runner`。
- 被审查代码所在容器不能获得 Docker Socket、SCM Token、模型 Key 或生产数据库凭据。
- 测试任务默认禁止网络访问。
- 依赖下载必须作为独立、受限、可审计的准备任务。
- 单机 Docker Compose 只声明为受控演示环境，不宣称具备恶意多租户隔离能力。

### 4.4 SCM 凭据

- GitHub 使用 GitHub App 安装令牌，不保存长期用户 PAT。
- GitLab 首版使用加密的 Project Access Token 和 Webhook Secret。
- SCM 凭据只保存在后端，并通过现有加密服务加密。
- Runner 只能收到去除凭据后的代码归档或临时对象引用。
- API 不返回凭据明文。

### 4.5 Patch

- Patch 必须绑定 Agent Run、Finding、生成模型、Prompt 版本和 PR Head SHA。
- PR Head SHA 变化后，旧 Patch 自动失效。
- 禁止修改凭据、CI 密钥、权限配置和其他保护文件。
- 禁止绝对路径、路径穿越、二进制 Patch 和超范围修改。
- 测试通过不能单独证明问题已修复；目标 Finding 或复现用例必须消失。
- Patch 不能自动提交、推送或合并。

## 五、Agent 状态机

主状态：

```text
RECEIVED
PREPARING_REPOSITORY
ANALYZING_CHANGE
PLANNING
EXECUTING_TOOLS
RETRIEVING_CONTEXT
VERIFYING_FINDINGS
GENERATING_PATCH
VALIDATING_PATCH
WAITING_APPROVAL
PUBLISHING_RESULT
COMPLETED
```

异常状态：

```text
RETRY_WAIT
FAILED
CANCELED
TIMED_OUT
```

必须支持以下分支：

- 没有发现问题：`VERIFYING_FINDINGS → PUBLISHING_RESULT`。
- 问题无法安全生成 Patch：跳过审批，直接发布审查结论。
- Patch 无法应用或验证失败：展示失败原因，但不能进入可批准状态。
- 只有成功应用且满足审批条件的 Patch 才进入 `WAITING_APPROVAL`。
- 取消操作只阻止新步骤，并通知正在运行的沙箱任务终止。

## 六、核心模块

### 6.1 Agent 控制面

建议包结构：

```text
com.example.codereview.agent
├── run
├── plan
├── tool
├── model
├── budget
├── queue
├── outbox
├── api
├── compat
└── observability
```

核心数据表：

- `agent_run`
- `agent_step`
- `review_plan`
- `tool_invocation`
- `agent_outbox`
- `finding`
- `finding_evidence`
- `patch_candidate`
- `approval_request`
- `webhook_delivery`
- `scm_installation`

### 6.2 工具协议

```java
interface AgentTool<I, O> {
    String name();
    ToolSchema schema();
    ToolRiskLevel riskLevel();
    ToolResult<O> execute(ToolContext context, I input);
}
```

首批工具：

- `git.diff`
- `git.file`
- `code.search`
- `static.scan`
- `test.run`
- `knowledge.search`
- `patch.generate`
- `patch.apply`
- `patch.validate`
- `scm.comment`

### 6.3 多语言插件

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

首版支持：

- Java：JavaParser、PMD、SpotBugs、Checkstyle、Maven/Gradle、JUnit。
- Python：AST、Ruff、Bandit、Pytest。
- JavaScript/TypeScript：ESLint、Semgrep、TypeScript Compiler、Jest/Vitest。

Java 插件应做得最深；Python 和 JavaScript/TypeScript 首版以静态工具、测试和基础上下文为主。

## 七、证据和门禁规则

每个 Finding 必须尽可能包含：

- 代码证据：Commit SHA、文件、行号、代码片段。
- 工具证据：工具名、规则 ID、工具版本、结果摘要。
- 知识库证据：文档、章节、版本和检索得分。
- 验证证据：测试或最小复现结果。
- 判断摘要：面向用户的简短说明。

建议基础权重：

```text
静态工具命中          +0.35
精确位置可复现        +0.20
知识库规则支持        +0.20
独立复核模型同意      +0.15
测试成功复现          +0.10
```

规则：

- 分数限制在 `[0,1]`。
- 保存每项分数贡献，保证可解释。
- HIGH 严重级别、置信度不低于 `0.75`、且具备有效代码位置时，才允许阻止 PR。
- 纯模型判断不能阻止 PR。
- 证据冲突、代码位置失效或重复证据需要扣分。

## 八、RAG 与上下文

检索 Query 应来自：

- 变更文件路径。
- 类、方法、函数和变量。
- import、注解、字符串和配置键。
- 静态分析规则 ID。
- 关联调用关系。

检索排序综合：

- 关键词匹配。
- 向量相似度。
- 变更符号关系。
- 文档类型权重。
- 项目和文档范围。

切片优先按照 Markdown 标题、代码符号和段落边界，保留现有字符切片作为兼容回退。

所有上下文都必须保留来源和版本，并被标记为不可信参考资料。

## 九、八周开发排期

| 周次 | 目标 | 退出条件 |
| --- | --- | --- |
| 第 1 周 | 整理现有改动、稳定构建、建立 CI | 工作区干净，本地和 CI 通过 |
| 第 2 周 | Flyway、Testcontainers、核心回归测试 | 旧库升级、PG 和 RabbitMQ 测试通过 |
| 第 3 周 | Agent 持久化、状态机、预算、工具协议 | 状态转换和预算测试通过 |
| 第 4 周 | 结构化输出、Outbox、MQ 幂等、恢复、时间线 | 重启恢复和重复消息测试通过 |
| 第 5 周 | GitHub/GitLab Webhook 和统一 SCM 接口 | 验签事件只创建一个 Agent Run |
| 第 6 周 | Sandbox Runner、仓库工具和依赖策略 | 网络、命令和路径逃逸测试通过 |
| 第 7 周 | 三语言插件、证据融合和混合检索 | 三语言样例生成统一 Finding |
| 第 8 周 | Patch、审批、评测、可观测性和演示 | 质量门槛与端到端演示通过 |

每周至少保留 20% 时间处理集成问题、部署差异、文档和演示排练。

## 十、阶段准入规则

进入下一阶段前必须满足：

- 当前阶段形成独立提交。
- 自动化测试和构建全部通过。
- 没有未处理的 Critical 或 Important 代码审查问题。
- 数据库、配置和部署文档同步更新。
- 当前阶段可以独立演示。
- 阶段二至四在真正开发前，继续拆成阶段一同等粒度的 TDD 执行任务。

## 十一、延期时的裁剪顺序

按以下顺序裁剪：

1. GitLab 结果发布页面的细节，保留 GitLab Webhook。
2. 自动依赖下载，改为预置演示缓存。
3. Patch 审批页面细节，保留审批 API 和 Patch 下载。
4. 完整 OpenTelemetry 看板，保留关联 ID 和 Micrometer 指标。
5. Python/TypeScript 高级语义分析，保留静态工具和测试。

不能裁剪：

- Webhook 验签和幂等。
- 事务 Outbox。
- 沙箱命令、路径和网络限制。
- Agent 状态恢复。
- 阻断问题的证据要求。
- Patch 的 Head SHA 过期检查。
- 固定评测集和核心质量指标。

## 十二、首版质量标准

- 高风险问题召回率不低于 `0.80`。
- Finding 精确率不低于 `0.70`。
- 有效代码位置准确率不低于 `0.90`。
- 相同 Delivery ID 不得创建重复 Agent Run。
- Runner 环境、日志和持久化预览不得泄露密钥。
- 对可修复评测案例，Patch 应用成功率不低于 `0.70`。

## 十三、开始开发前的第一步

当前工作区存在较多未提交修改。禁止直接开始 Agent 功能。

必须先执行：

```powershell
git status --short
git diff --stat
git diff --check

cd backend
mvn -s .mvn/settings.xml test

cd ..\frontend
npm run build
```

随后：

1. 审查当前修改。
2. 排除 `.m2home/`、日志、构建产物和凭据。
3. 明确暂存需要提交的文件。
4. 提交当前项目检查点。
5. 记录检查点 Commit SHA。
6. 创建独立 Git Worktree。
7. 从阶段一开始实施。

## 十四、关联文档

- `docs/superpowers/specs/2026-06-18-pr-gatekeeper-agent-design.md`
- `docs/superpowers/plans/2026-06-18-pr-gatekeeper-roadmap.md`
- `docs/superpowers/plans/2026-06-18-pr-gatekeeper-phase-1-engineering-baseline.md`
- `docs/superpowers/plans/2026-06-18-pr-gatekeeper-phase-2-agent-control-plane.md`
- `docs/superpowers/plans/2026-06-18-pr-gatekeeper-phase-3-scm-sandbox.md`
- `docs/superpowers/plans/2026-06-18-pr-gatekeeper-phase-4-plugins-patch-evaluation.md`
