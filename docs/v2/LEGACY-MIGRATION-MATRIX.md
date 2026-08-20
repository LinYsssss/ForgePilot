# Legacy → ForgePilot V2 迁移矩阵

Legacy 完整实现已于 2026-08-19 归档到只读参考仓库 [LinYsssss/reposage](https://github.com/LinYsssss/reposage)，切分基线为 `96137dd3b43e14c5e8881c99688663afd979cf4e`。ForgePilot 不依赖、不以子模块引入也不整包复制 RepoSage；本矩阵是访问和提取 Legacy 资产前的强制入口。

第二轮审查已按 `ARCHITECTURE.md` 修正过度乐观的 KEEP；当前表以修正后的判断为准。Finding 的目标模块统一为 `review`。Legacy Assistant 代码不迁；MVP 的一次性 Implementation Guidance 在 `requirement` 内重新实现。

分类口径：`KEEP` 表示源代码可低成本迁移并补测试；`REWRITE` 表示保留业务但按 V2 边界重新实现；`REFERENCE` 表示只继承算法/安全策略/Prompt/测试思想；`DROP` 表示不进入 V2。

> 绿地项目会更换根包、数据表和依赖方向，因此 KEEP 也必须先迁特征/安全测试，再迁少量边界清楚的纯代码；旧 Flyway 历史、周边架构和运行依赖一律不随 KEEP 进入 V2。

## Auth / Project / Member / Common

| Legacy 资产 | 使用状态与价值 | 判断 | V2 去向 | 理由与风险 |
|---|---|---|---|---|
| `auth/AuthService` | 在用；登录、防枚举、审计、sessionVersion | REWRITE | `auth.AuthService` | 业务成熟，但与旧实体/Token/审计耦合 |
| `auth/TokenService` | 在用；自定义 HMAC Token | REFERENCE | `auth` | 不原样继承私有 Token 协议；保留最小 claim 思路 |
| `auth/TokenAuthenticationFilter` | 在用；Bearer/Cookie、sessionVersion 再校验 | REWRITE | `auth` | 过滤流程可参考，改用 V2 认证契约 |
| `auth/AuthCookieService` | 在用；HttpOnly/Secure/SameSite | REWRITE | `auth` | 代码很小且绑定旧配置；保留安全属性测试比复制实现更有价值 |
| `auth/LoginAttemptGuard` | 在用；单机内存锁定 | REFERENCE | `auth` | V2 单机可重写轻量版本，不宣称分布式限流 |
| `project/ProjectEntity`、`ProjectService` | 在用；项目生命周期 | REWRITE | `project` | 新表、新聚合和统一授权边界 |
| `member/ProjectMemberEntity`、`ProjectRole` | 在用；LEADER/DEVELOPER/REVIEWER | REWRITE | `project` | member 归入 project，不增加顶层包 |
| `member/ProjectMemberService` | 在用；唯一负责人、转移、权限 | REWRITE | `project.ProjectMemberService` | 规则保留，消除对旧 common/project 的循环；恰一 LEADER 约束见 D004 |
| `project/ProjectCleanupService` | 在用；中央跨域硬删除 | DROP | 无 | 同时依赖十余领域，是依赖团核心；改用 FK/领域内删除 |
| `common/security/CryptoService` | 在用；SCM 密钥加密 | REWRITE | `common.security.SecretCipher` | 保留信封加密/不回显契约，重新设计密钥管理 |
| `common/security/SecurityAuditLogger` | 在用；安全事件 | REWRITE | `common.audit` | 只保留必要审计，不迁复杂观测链 |
| `common/api/ApiResponse`、异常映射 | 在用；统一 API | REWRITE | `common.web` | 清理历史 error code/HTTP 状态兼容债务 |

## Requirement

| Legacy 资产 | 使用状态与价值 | 判断 | V2 去向 | 理由与风险 |
|---|---|---|---|---|
| `requirement/RequirementStatus` | 在用；合法状态边和内容锁定 | REWRITE | `requirement.RequirementStatus` | V2 删除 NEEDS_IMPROVEMENT，质量建议不再驱动工作流状态 |
| `RequirementEntity` | 在用；需求主体 | REWRITE | `requirement.Requirement` | 新增明确 project/assignee/review 状态与附件关系 |
| `AcceptanceCriterionEntity` | 在用；顺序化 AC | REWRITE | `requirement.AcceptanceCriterion` | 模型简单但需新 FK/唯一约束/版本语义 |
| `RequirementService` | 在用；CRUD、指派、提交评审 | REWRITE | `requirement.RequirementService` | 保留用例，拆开命令、查询与外部 AI 调用 |
| `RequirementRuleChecker` | 在用；零 Token 确定性检查 | REWRITE | `requirement.RequirementQualityService` | 保留规则与测试；旧实体/DTO 和中文启发式需版本化重建 |
| `RequirementCheckParser` | 在用；严格结构化解析 | REWRITE | `requirement.RequirementQualityParser` | 保留整体拒绝与全量补齐测试，新输出 Schema 不同 |
| `RequirementCheckService` | 在用；规则+知识+LLM | REWRITE | `requirement.RequirementQualityService` | 核心业务保留，外部调用移出长事务；与规则检查同一编排入口 |
| `OpenAiCompatibleRequirementCheckClient` | 在用；独立 HTTP 客户端 | DROP | 统一 `ai.AiGateway` | 重复客户端且反向依赖 Agent Prompt |
| `RequirementQualityReport*` | 在用；检查历史 | REWRITE | `requirement.quality_json/version/checked_at` 快照字段 | 不建独立报告表（ARCHITECTURE §2.1）；不可复用旧 schema |
| `RequirementLinkEntity/Service` | 在用；Branch/Commit/PR 通用链接 | REFERENCE | Requirement↔PR/代码引用显式字段 | 幂等/提取策略可参考，字符串多态模型不迁 |
| 需求上传附件逻辑 | 不完整/分散 | REWRITE | `RequirementAttachment` + `KnowledgeDocument` | 一次上传、一个文档事实源，禁止双解析/双索引 |

## Knowledge / RAG

| Legacy 资产 | 使用状态与价值 | 判断 | V2 去向 | 理由与风险 |
|---|---|---|---|---|
| `knowledge/KnowledgeUploadValidator` | 在用；大小/类型/UTF-8/NUL/文件名安全 | KEEP | `knowledge.KnowledgeUploadValidator` | 隔离且测试价值高；补 MIME/压缩炸弹边界 |
| `knowledge/KnowledgeDocument` | 在用；PENDING/INDEXED/FAILED | REWRITE | `knowledge.KnowledgeDocument` | 新表统一 Requirement 附件与项目知识；source_type/source_requirement_id 见 D005 |
| `knowledge/KnowledgeChunk` | 在用；chunk + embeddingJson | REWRITE | `knowledge.KnowledgeChunk` | 去 JSON；无维度 `vector` 单列 + provider/model/version/dimension 审计元数据（D001） |
| `knowledge/KnowledgeService` | 在用；上传/切片/索引/重建 | REWRITE | `knowledge.KnowledgeIngestionService` | 旧类职责过宽、同步调用与多域耦合 |
| `KnowledgeDocumentStateService` | 在用；短事务状态更新 | REFERENCE | ingestion transaction pattern | 继承事务思想，不复制类名与 REQUIRES_NEW 滥用 |
| `knowledge/EmbeddingClient` | 在用；descriptor/result 校验 | REWRITE | `ai.AiGateway`（embed 入口） | 接口质量高，但 Embedding 属 AI provider 边界（ARCHITECTURE §4.1） |
| `OpenAiCompatibleEmbeddingClient` | 在用；单条 embedding | REWRITE | `ai.openai` | 增加批量、超时、失败可见性、版本契约 |
| `knowledge/EmbeddingJson` | 在用；JSON/pgvector 双格式 | DROP | 无 | V2 单一 vector 列，不双写 |
| `rag/PgVectorIndexService` | 在用；动态 extension/table | DROP | Flyway + JPA/native query | 禁止运行时 DDL、独立 vector table |
| `rag/RagService` | 在用；full context/memory/pgvector 多路径 | REWRITE | `knowledge.KnowledgeRetrievalService` | 不保留 rag 顶层包；只保留 hybrid retrieval 思路 |
| `context/HybridContextRanker` | 在用；混合排序 | REFERENCE | `knowledge` retrieval policy | 先用简单 vector+metadata；数据证明需要再加权 |

## SCM

| Legacy 资产 | 使用状态与价值 | 判断 | V2 去向 | 理由与风险 |
|---|---|---|---|---|
| `scm/ScmProvider` | 在用；provider-neutral webhook 边界 | REWRITE | `scm.ScmProvider` | V2 接口还需 repository/PR/diff/commit 能力，不只 webhook |
| `NormalizedPullRequestEvent`、`PullRequestSnapshot` | 在用；归一化事件/权威快照分离 | REWRITE | `scm` | 保留 event/snapshot 分离与 contract fixture；删除 clone/Agent publication 假设 |
| `github/GitHubScmProvider` | 在用；事件过滤/归一化 | REWRITE | `scm.github` | 保留协议映射，接入统一 Provider 全接口 |
| `gitlab/GitLabScmProvider` | 在用；MR 映射/确定性 delivery key | REWRITE | `scm.gitlab` | 同上；核对不同 GitLab 版本 payload 差异 |
| GitHub/GitLab WebhookVerifier | 在用；HMAC/Token 常量时间比较 | KEEP | `scm.github` / `scm.gitlab` | 独立、安全、易测试 |
| `WebhookDeliveryRecorder` | 在用；验签后记录+唯一键并发幂等 | REFERENCE | `scm.WebhookService` | V2 可保留 delivery 字段或最小审计；不要新增无必要表 |
| GitHub/GitLab WebhookController | 在用；安全顺序正确，末端启动 AgentRun | REWRITE | `scm` Controller 保存 PR 后发布 `PullRequestChanged` 事件 | 移除重复控制器模板与 Agent 耦合；SCM 不得依赖 review（ARCHITECTURE §1.3/3.1） |
| `ScmInstallation*` | 在用；连接、加密凭据、轮换 | REWRITE | `scm.ScmRepository`（`scm_repository` 含加密凭据） | `scm_connection` 已并入 `scm_repository`（ARCHITECTURE §2.1）；密钥不可回显 |
| `git/OutboundUrlPolicy` | 在用；SSRF 防护 | KEEP | `common.security.OutboundUrlPolicy` | 高价值纯安全代码；仍需声明 DNS rebinding 边界 |
| `git/GitInputValidator` | 在用；ref/SHA/本地 clone 输入安全 | DROP / REFERENCE | 无本地 Git 实现 | V2 只消费 Provider 返回的 PR/patch，不接收本地路径；保留恶意输入测试思想 |
| `scm/ScmHttpSupport` | 在用；HTTPS、禁重定向、SSRF | REFERENCE | provider-specific HTTP client | 只保留安全策略；重写错误、分页、rate limit |
| `repo/CodeRepositoryEntity/RepositoryService` | 在用；本地 clone 与仓库服务 | REWRITE | `scm` | repo/git/scm 合并，生产以 provider API 为主 |
| `pullrequest/PullRequestEntity/Service` | 在用；同步 PR 与 Review Decision | REWRITE | `scm.PullRequest` + `review` decision | 去字符串状态、WAIVE 和旧 report 依赖；Requirement 1:N PR 见 D004 |
| `WebhookAgentRunService` | 在用；Webhook→Agent | DROP | `review` 内事件监听 → `ReviewService.requestReview` | 不能把 AgentRun 带入 V2；自动触发与手动重试同一入口 |

## AI / Context / Assistant

| Legacy 资产 | 使用状态与价值 | 判断 | V2 去向 | 理由与风险 |
|---|---|---|---|---|
| `ai/PromptSanitizer` | 在用；敏感信息脱敏、Unicode 截断 | KEEP | `ai.PromptSanitizer` | 纯函数；继续补注入与密钥格式测试 |
| `ai/AiReviewClient` | 在用；过窄旧接口 | DROP | `ai.AiGateway` | 新 Gateway 支持 typed chat/embedding，不按业务复制 client |
| `OpenAiCompatibleReviewClient` | 在用；超时/usage/JSON | REWRITE | `ai.openai.OpenAiCompatibleGateway` | 移除 Agent Prompt 依赖，统一响应校验 |
| `AiTransientFailureClassifier` | 在用；瞬时/永久错误 | REFERENCE | `ai.openai.AiFailurePolicy` | 去两运行时/历史 error code 兼容，保留决策表 |
| `ai/langchain4j/*` | 在用；第二运行时/rollout | DROP | 无（V1） | V2 只选一个简单 OpenAI-compatible client，避免双运行时 |
| `context/ContextBuilder` | 在用；需求/AC/知识/代码统一上下文 | REWRITE | `review.ReviewContextBuilder` | 这是核心算法边界，但需消除跨 8 包直连 |
| `assistant/AssistantPromptAssembler` | 在用；预算、来源白名单、不可信数据标签 | REFERENCE | 后置 Requirement Assistant | Prompt 设计保留，MVP 不建设 Assistant |
| `assistant/AssistantService`、SSE | 在用；需求范围问答 | DROP（MVP）/REFERENCE（后置） | 无 MVP 目标 | 删除后核心 Review 产品成立；避免聊天历史/SSE/状态膨胀 |
| `agent/prompt/*` | 在用；共享模板却位于 Agent | DROP | V2 Prompt 归各业务、通用渲染归 `ai` | 包归属错误且把 Agent 变成上游 |
| `ai/AiCallLog*` | 在用；调用成本与失败追踪 | REWRITE | `ai.AiCallLog` | 只留必要字段：useCase/model/version/token/latency/status/error |
| `AiMetrics` + 全观测集成 | 在用；生产可观测性 | REFERENCE | Actuator/结构化日志 | 毕设 V1 不部署完整 OTel/Grafana 栈 |

## Review / Finding

| Legacy 资产 | 使用状态与价值 | 判断 | V2 去向 | 理由与风险 |
|---|---|---|---|---|
| `review/DiffSplitter` | 在用；纯函数文件级分片 | REWRITE | `review.ChangedFileBatcher` | 旧实现按字符/文件数静默丢内容；V2 基于 Provider ChangedFile 分片并输出 coverage manifest，分批语义见 D002 |
| `review/ReviewService` | 在用；旧手动任务主入口 | DROP | 新 `review.ReviewService` | 名字可复用，旧实现不可迁；MQ/report/feedback 多域耦合 |
| `review/ReviewProcessor` | 在用；旧 Review Engine | DROP | 新 `review.ReviewEngine` | 必须避免与 Agent 第二引擎共存 |
| `ReviewTask/Report/Issue` 旧模型 | 在用；报告制 | REWRITE | `Review` + `Finding` | 收敛任务、报告、问题的重复状态和表 |
| `CoverageJudgeService/Parser` | 在用；AC 覆盖与证据校验 | REFERENCE | Review structured output | 保留 AC coverage 维度；默认一次 Review 输出，必要时二阶段 |
| `ReviewReportExporter` | 在用；报告导出 | REFERENCE | Phase 8 可选 | 不进入核心闭环，答辩需要时再实现 |
| `finding/FindingLifecycle` | 在用；人工闭环状态机 | KEEP | `review.FindingStatus` | 与目标状态边一致；Finding 不再独立成顶层模块 |
| `FindingCandidate`、`FindingEvidence` | 在用；结构化问题与证据 | REWRITE | `review` | 新证据必须绑定 review/requirement/ac/source whitelist，旧 Agent sourceVersion 语义不适用 |
| `FindingDeduplicator` | 在用；路径/符号/邻域 fingerprint | REWRITE | `review.FindingFingerprint` | 旧算法 lower-case path 且缺 head/ac 语义，可能错误合并；新实现须按 D009 承担"批内去重 + 跨 Review 血缘键"双职责，需求类 Finding 必须含 `requirement_id + ac_key` |
| `FindingVerifier` | 在用；证据门槛 | REFERENCE | Review output validation | 规则过于简单，不作为可信真值 |
| `FindingConfidenceService` | 在用；人工加权置信度 | REFERENCE | 评测/展示，不自动 gate | 未校准权重不得决定 PR 是否通过 |
| `Finding` JPA 实体 | 在用；pipeline status + lifecycle 双轴 | DROP | 新 Finding 实体 | 旧实体依赖 AgentRun 且双状态债务严重 |
| `FindingLifecycleService` | 在用；角色与状态流转 | REWRITE | `review.FindingLifecycleService` | 保留用例/权限，去 AgentRun/ScmContext 依赖并避免 review↔finding 循环 |
| `FindingDecisionEntity` | 在用；自动 gate 置信度决定 | DROP | `FindingEvent`/人工 decision | 不混淆 AI 置信度、Finding 人工决策和 PR 决策 |
| `PullRequest ReviewAction` | 在用；APPROVE/REQUEST_CHANGES/WAIVE/COMMENT | REWRITE | `ReviewDecision` | V2 核心只允许 APPROVE/REQUEST_CHANGES，评论另作注释 |

## 明确整包 DROP

| Legacy 范围 | 判断 | 原因 |
|---|---|---|
| `backend/.../agent/**`（除纯 Prompt 思想） | DROP | 115 类的第二产品；AgentRun/Step/Planner/Orchestrator/Queue/Outbox 不服务主线 |
| `backend/.../patch/**` | DROP | 自动 Patch/校验/审批/提交越过 V2 产品边界 |
| `backend/.../mq/**`、RabbitMQ | DROP | V1 单体不需要分布式队列；可用进程内执行器/数据库状态 |
| `sandbox-runner/**` | DROP（运行时）/REFERENCE（安全策略） | 复杂、持 Docker socket，且旧文档承认不是真多租户隔离 |
| `model-service/**` | DROP | 旧 TF-IDF/轻量风险模型不在新主线 |
| Legacy Review Projection | DROP | 仅为双轨兼容存在 |
| 32 个 Legacy Flyway migrations | DROP | V2 从干净 `V1__init.sql` 起步 |
| `deploy/observability/**` | DROP（V1 部署）/REFERENCE | OTel/Prometheus/Alertmanager/Grafana 超出毕业设计核心 |
| 前端 Agent/Patch/Workspace/Metrics 页面与 composable | DROP | 普通用户不应看到内部编排与研究设施 |

## 非运行时资产

| 资产 | 判断 | V2 用法 |
|---|---|---|
| `evaluation/cases` + `manifest.json` | REWRITE/KEEP DATA | 去 Patch/Agent 字段，保留 dev/holdout、Requirement/AC、Finding truth、nonFinding |
| `evaluation/tools/score.py` | KEEP TOOL / ADAPT | 核心匹配与统计成熟；只适配 V2 输出并移除 Agent/Patch 运行字段 |
| `demo-repos/**` | KEEP DATA | V2 集成测试和答辩演示；固定 SHA 与诚实边界继续保留 |
| `docs/**` | REFERENCE | 只摘取协议、安全、评测、部署经验；不整体复制多代架构 |
| `.trellis/tasks/**` | REFERENCE | 只作 Legacy 决策档案；V2 使用精简 Trellis 任务与 `DECISIONS.md`，不带旧任务噪声 |
