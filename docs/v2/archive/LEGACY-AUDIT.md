# ForgePilot Legacy 实库审计

审计日期：2026-08-18  
远端：`LinYsssss/ForgePilot`  
main：`ff63adc5b78eded62ba4f6db258d9cf417b4b402`

## 1. 扫描范围与规模

GitHub recursive tree 返回 `truncated=false`，共 2547 个条目、1642 个 blob。主要目录文件数：

| 范围 | blob 数 | 关键事实 |
|---|---:|---|
| `backend` | 635 | main Java 411，test Java 155，资源 56 |
| `.trellis` | 434 | 大量已归档迭代、设计与审计证据，不属于 V2 运行时 |
| `evaluation` | 259 | 38 例 dev/holdout 语料、manifest、判分器与基线 |
| `frontend` | 114 | Vue 3，47 个 `.vue`，当前 8 个一级路由 |
| `demo-repos` | 59 | 3 个演示仓库、知识/噪声文档、可复现 SHA |
| `sandbox-runner` | 56 | 独立 Spring Boot + RabbitMQ + Docker socket 执行器 |
| `docs` | 33 | 多代产品架构与 Agent 路线资料并存 |
| `deploy` | 22 | 11 服务 Compose，包含 MQ、Sandbox、OTel、Prometheus、Grafana |
| `model-service` | 8 | 旧轻量模型路线，已不属于目标产品 |

后端 main 顶层包规模前列：`agent 115`、`finding 37`、`scm 28`、`language 23`、`ai 20`、`requirement 20`、`review 17`、`patch 13`、`assistant 10`、`auth 10`、`rag 9`、`knowledge 9`、`mq 9`。旧库依赖扫描显示 22 个业务包构成一个强连通依赖团，因此不能把旧 package 整体搬入 V2。

## 2. 领域审计结论

### 2.1 Auth / Project / Member

- `AuthService` 已实现 BCrypt 假哈希、防用户名枚举、登录失败节流、安全审计和 `sessionVersion` 会话失效，业务与安全经验成熟。
- `TokenService` 是自定义 HMAC Token，不是标准 JWT；可参考字段设计，但 V2 不应原样继承协议。
- `LoginAttemptGuard` 是单实例内存限流，适合单机演示但不应被描述为分布式安全能力。
- `ProjectService`、`ProjectMemberService` 已验证 OWNER/LEADER/DEVELOPER/REVIEWER、唯一负责人、所有权转移等规则。
- `ProjectCleanupService` 同时依赖 repo、knowledge、vector、review、report、feedback、PR、AI、MQ、git、member、requirement，是旧架构失控的直接证据。V2 应依靠领域内删除策略和外键，而非中央清理服务。

结论：业务规则保留，实体与应用服务重写；安全纯函数可低成本迁移；中央清理服务丢弃。

### 2.2 Requirement

- `RequirementStatus` 已形成 `DRAFT → NEEDS_IMPROVEMENT/READY → IN_DEVELOPMENT → IN_REVIEW → DONE` 的显式合法边，并包含撤销/取消与内容锁定规则。
- `AcceptanceCriterionEntity` 的 `seq + text` 是足够简单的验收条件模型。
- `RequirementRuleChecker` 先做零 Token 的确定性完整性/模糊词/可测试性检查，值得保留。
- `RequirementCheckService` 已验证“规则检查 → 项目知识上下文 → 结构化 LLM → 持久化报告”的产品价值；但把外部 LLM 调用放在长事务边界附近，需要重写。
- `RequirementCheckParser` 对维度、严重度和缺失维度采用严格校验，是可复用的结构化输出防线。
- `RequirementLinkService` 的 `REQUIRES_NEW` 幂等写入、唯一键冲突隔离和 `REQ-N` 提取值得参考；V2 应把 Requirement 与 PullRequest 建成明确关系，而不是继续扩展通用字符串 Link。
- 当前 Requirement AI 客户端反向依赖 `agent.prompt`，说明 Prompt 基础设施已越界。

结论：需求业务主线是 V2 必须救的核心；状态机、规则与严格解析接近 KEEP，其余围绕新数据模型重写。

### 2.3 Knowledge / RAG

- `KnowledgeUploadValidator` 有扩展名白名单、流式大小上限、严格 UTF-8、NUL 拒绝、文件名清理、字符上限，是高质量隔离代码。
- `EmbeddingClient` 的 descriptor 包含 provider/model/version/dimension，并校验 finite/dimension，模型兼容性设计成熟。
- `KnowledgeDocumentStateService` 用独立短事务保存 PENDING/INDEXED/FAILED，解决上传失败随主事务回滚的问题，事务经验值得参考。
- `KnowledgeService` 已验证上传、切片、Embedding、重建索引和模型版本匹配，但同时依赖授权、AI 日志、RAG、VectorIndex，职责过宽。
- `knowledge_chunk.embeddingJson` 与 `knowledge_chunk_vector` 双存同一向量；`PgVectorIndexService` 还在运行时动态建 extension/table。V2 必须改为 Flyway 建表、`knowledge_chunk.embedding vector(n)` 单一事实源。
- `RagService` 同时承担检索算法、存储分支、Embedding 调用和日志。V2 不保留 `rag` 顶层包，收敛为 `knowledge.KnowledgeRetrievalService`。

结论：上传安全校验可以 KEEP；Embedding 契约、状态事务与检索算法 REFERENCE/REWRITE；双写存储与运行时 DDL DROP。

### 2.4 SCM / Git / Repository / Webhook / PullRequest

- `ScmProvider` 已建立 provider-neutral webhook 身份解析、原始字节验签和事件归一化边界。
- `GitHubScmProvider`、`GitLabScmProvider` 只接受可审查事件，并将 PR/MR 归一化；GitLab 还提供确定性 delivery key。
- `GitHubWebhookVerifier` 使用 HMAC-SHA256 + 常量时间比较；GitLab Token 同样常量时间比较。
- Webhook 控制器严格遵循“从 payload 取 installation identity → 从库取密钥 → 对 raw bytes 验签 → 验签后落库 → 唯一键幂等”的安全顺序。
- `WebhookDeliveryRecorder` 不记录未验签流量，使用数据库唯一键而不是先查后写实现并发幂等。
- `OutboundUrlPolicy` 覆盖私网、loopback、link-local、保留地址、非常规数字 IP、多 A/AAAA 记录，明确承认 DNS rebinding 边界，是高价值安全资产。
- `ScmHttpSupport` 禁止重定向并复用 SSRF 策略；但 HTTP 能力仍较薄，应在 V2 统一重写客户端层。
- `ScmInstallationAdminService` 已实现凭据加密、轮换不回显与审计。
- 当前 Webhook 控制器最终启动 `WebhookAgentRunService`，必须在 V2 改为单一 Review 入口。
- `PullRequestEntity` 已有 head SHA 变化后重置 reviewState 的正确业务直觉，但大量 String 状态以及 `WAIVE` 等旧语义需要重建。

结论：这是 Legacy 最值得救的一组工程资产。适配器接口、安全校验和幂等策略接近 KEEP；持久化模型、客户端和 Webhook→Review 编排 REWRITE。

### 2.5 AI / Context / Assistant

- `PromptSanitizer` 提供私钥、Authorization、Secret、GitHub Token 脱敏，以及 UTF-8 byte/code-point 双上限截断，可直接迁移。
- `AiTransientFailureClassifier` 明确区分 429/5xx/网络失败和永久错误，思想成熟，但实现背负两套运行时与历史错误码兼容，应简化重写。
- `OpenAiCompatibleReviewClient` 具备超时、重试/熔断、Token usage、结构化 JSON 解析；但反向依赖 `agent.prompt.AgentPromptAssembler`，证明 AI 边界被 Agent 污染。
- `ContextBuilder` 已把 Requirement、AC、Knowledge、PR/Commit Diff 收敛成有 source id、预算和降级警告的上下文，这是 V2 `DevelopmentContextBuilder` 的直接参考。
- `AssistantPromptAssembler` 将需求/AC 作为必选预算、知识/代码作为可选预算，建立来源白名单并把 context/history/question 标记为不可信数据，设计价值很高；但依赖 Agent 模板注册器。
- 当前 assistant 是独立通用 SSE 对话实现。V2 只保留 requirement-scoped `RequirementAssistantService.answer(...)`，不保留通用会话编排。

结论：Prompt 安全纯函数 KEEP；上下文预算、来源引用和失败分类 REFERENCE/REWRITE；Agent Prompt Registry 与通用 Assistant 架构 DROP。

### 2.6 Review / Finding / Agent / Patch

- `ReviewService` 已被 MQ、旧报告表、旧反馈、PR、Repo、AI 日志等多域耦合；`ReviewProcessor` 又与 Agent 路线并存，不能作为 V2 主干。
- `DiffSplitter` 是无副作用纯函数，按文件分片、批次预算、单文件局部截断并显式记录 skipped files，属于可直接救出的成熟代码。
- `CoverageJudgeService` 已验证 Requirement/AC 与 Diff 一致性判定以及伪造证据降级，但当前通过通用字符串 Link 解析需求，且作为第二次 LLM 增强挂在旧 Review 上。V2 应把 AC 覆盖并入唯一 Review Engine 的结构化输出或受控二阶段。
- 当前 `Finding` 同时存在 pipeline `status` 与正交 `lifecycleStatus`，并依赖 `AgentRun`；这是双轨兼容造成的数据模型债务。
- `FindingLifecycle` 的人工闭环状态边完整，值得保留。
- `FindingCandidate`、`FindingEvidence`、`FindingDeduplicator` 对行号、证据 hash、excerpt 上限、fingerprint 和去重有较强代码质量。
- `FindingVerifier` 只用“是否有证据/低分 verifier”判断，过于简化，只能参考。
- `FindingConfidenceService` 的固定人工权重是实验资产，不能直接当生产可信度模型。
- `agent` 115 个 main 类、AgentRun/Step/Orchestrator/Outbox/Queue/Tool/Sandbox/Patch 构成第二产品；无论是否仍有调用，均不服务 V2 主线。

结论：Diff 分片、Finding 纯模型/证据/生命周期可救；Review 和 Finding 持久化重写；整个 Agent/Patch/MQ 控制面 DROP。

## 3. 前端、部署、评测与文档

### Frontend

- 当前路由把 dashboard、projects、requirements、repository、agent、quality、knowledge、metrics 全部做成一级菜单，并保留旧 reviews/pull-requests/ai-logs 兼容跳转。
- `App.vue` 在根组件同时启动旧 Review polling 与 Agent polling，说明双轨已经进入全局状态层。
- Vue 3 + Vue Router + Element Plus 技术栈可继续使用；认证 API client、CSRF、toast 等低层工具可迁移。
- Agent、Patch、Workspace、Metrics 页面与 composable 丢弃；项目、需求、仓库、审查页面按新 IA 重写；知识空间下沉项目详情。

### Deploy / Sandbox / Observability

- 当前 Compose 有 11 个服务：Postgres、RabbitMQ、backend、sandbox-runner、frontend、nginx、OTel Collector、Prometheus、Alertmanager、Grafana 等。
- `sandbox-runner` 是独立应用，经 RabbitMQ 消费并持有 Docker socket。其文档明确它不是 hostile multi-tenant isolation boundary。
- V2 默认只需 Postgres(pgvector)、backend、frontend/nginx。演示期可直接在单体进程内执行受控异步任务，MQ/Sandbox/完整观测栈不进入基线。
- 日志轮转、容器 healthcheck、secret 必填、内网端口绑定等部署细节值得参考。

### Evaluation / Demo repositories

- `evaluation` 已形成 38 例 development/holdout 语料、预期 Finding、nonFinding、Requirement/AC、consistencyTruth、误报/漏报独立统计和 Prompt Injection 用例。
- 评测 README 明确 mock provider 不能产生有效质量数字，并要求固定 temperature/model/prompt，方法论成熟。
- Patch 期望、Agent tool call 限额、五臂 Agent 消融字段不属于 V2；语料、知识噪声、Requirement/AC truth、score.py 可迁移/改写为 V2 Review 评测资产。
- `demo-repos` 的 3 个仓库、43 个刻意缺陷、知识噪声与固定 SHA 是优秀答辩素材；需诚实说明并非真实企业缺陷。

### Docs / Trellis

- 活跃 docs 同时描述原始 MQ+RAG+轻量模型路线和后续 PR Gatekeeper Agent 路线，不能作为 V2 规范整体搬迁。
- `.trellis` 的历史任务是重要决策证据库，但 434 个文件不应复制成 V2 的产品结构。只提取 V2 ADR、评测契约、安全契约与设计结论。

## 4. 最终审计判断

### 值得救的代码

`KnowledgeUploadValidator`、`PromptSanitizer`、`DiffSplitter`、`FindingLifecycle`、Finding Evidence/去重纯模型、Webhook 验签器、`OutboundUrlPolicy`、部分 Git 输入校验与结构化输出 Parser。

### 值得救的业务

项目成员与角色、需求/AC/指派/状态、需求质量检查、需求上下文 AI 指导、项目知识空间、GitHub/GitLab 统一接入、需求增强审查、Finding 人工闭环、PR Review Decision、可复现评测。

### 一定不能救的架构

旧 Review + Agent 双轨、AgentRun/AgentStep/Planner/Orchestrator、Legacy Projection、Patch 自动生成/审批/提交、RabbitMQ/Outbox 控制面、向量双写、运行时 DDL、中央跨域清理服务、独立 model-service、Sandbox 作为审查必经路径、8 个一级菜单和全局双 polling。
