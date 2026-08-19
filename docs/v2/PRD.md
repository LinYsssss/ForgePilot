# ForgePilot V2 最终候选 PRD

## Goal and User Value

将现有 ForgePilot 作为只读 `Legacy Reference / 技术验证版本`，提取成熟业务规则、安全策略、测试资产和评测方法，绿地设计一套更小的 ForgePilot V2。

ForgePilot V2 的产品定位是：

> **基于需求与项目知识上下文增强的 AI 代码审查系统。**

核心用户价值：负责人和 Reviewer 不再只看到通用代码建议，而能看到代码变更与 Requirement、Acceptance Criteria、项目规范之间的可追踪差异，并由人工完成退回、修复、复审和通过。

本任务只做审计、规划与方案收敛。人工批准前，不修改、删除或重构 Legacy 产品代码，不生成 V2 工程，不提交 commit，不创建 PR。

## Core Flow

> 负责人创建并指派带 AC 的需求，开发者提交关联 PR 后，ForgePilot 结合需求、项目知识与 Diff 生成可核验 Finding，Reviewer 据此退回或通过，开发者修复后复审闭环。

任何不能服务这条因果链的能力默认不进入 MVP。

## Users and Roles

- `LEADER`：创建项目、管理成员、确认需求、指派开发、配置仓库和项目知识、做必要的 Review 管理。
- `DEVELOPER`：接收需求、提交关联 PR、处理被确认的 Finding、提交修复。
- `REVIEWER`：确认/拒绝 Finding，验证修复，对整个 Review 做 `APPROVE` 或 `REQUEST_CHANGES`。
- AI：只生成质量分析、AC 判定和 Finding；不得改变业务状态或替代人工决定。

每个项目恰有一个 LEADER。MVP 不再引入独立 OWNER 角色。

## Confirmed Legacy Facts

- 当前 main 为 `ff63adc5b78eded62ba4f6db258d9cf417b4b402`；recursive tree 完整覆盖 1642 个 blob。
- Backend main Java 411 个，Agent 单包 115 个；22 个业务包位于同一强连通依赖团。
- Legacy 同时存在旧 Review 与 Agent Pipeline，并通过 Legacy Projection 维持双轨。
- Legacy 已验证 Requirement/AC、成员角色、SCM Adapter、Webhook 验签、Project Knowledge、pgvector、结构化审查、Finding 生命周期和评测语料。
- 旧系统的主要问题不是无引用类，而是多代产品路线和兼容层同时存活。

详细证据见 `research/legacy-audit.md` 和 `research/repository-evidence.md`。

## Requirements

### R1. Minimal product boundary

MVP 必须包含 Project/Member、Requirement/AC、一个活动 SCM 仓库、Project Knowledge、Requirement Quality、唯一 Review Engine、Finding 人工闭环和 Review Decision。

MVP 不包含 Requirement Assistant、通用聊天、Workbench、代码仓库一级菜单、相关代码语义检索、多仓库、通用 Commit 审查或本地 Git 工作区。

### R2. Requirement workflow

- Requirement 状态仅为 `DRAFT → READY → IN_DEVELOPMENT → IN_REVIEW → DONE`，以及 `CANCELED`。
- Quality Check 是建议，不产生 `NEEDS_IMPROVEMENT` 状态，也不能自动置 READY。
- Requirement 包含有序 AC、一个 assignee 和零或一个关联 PR（MVP）。
- Requirement 附件只保存一次为 KnowledgeDocument，RequirementAttachment 只存关系。

### R3. Project knowledge

- 所有项目共享 PostgreSQL，通过 SQL 中的 `project_id` 强隔离。
- 支持 `.md/.txt` 上传、严格 UTF-8、大小边界、Chunk、Embedding、pgvector TopK 和维护式 reindex。
- `knowledge_chunk.embedding vector(N)` 是唯一向量事实源；禁止 embedding JSON 双写和运行时 DDL。
- 一个部署只配置一个 Embedding provider/model/dimension；保存 model/version 用于追踪，不建设在线多版本路由。

### R4. Unified AI boundary

- 只保留一个 OpenAI-compatible `AiGateway`，提供 chat 和 embed。
- AI 模块只处理提供方协议、超时、一次有限重试、Token/延迟和错误；不得依赖 Requirement/Review/Finding 业务类型。
- Requirement 与 Review 分别拥有自己的 Prompt 和 Context；不建设 Prompt 平台、通用 Context 平台或第二 AI runtime。

### R5. Unified SCM

- MVP 一个项目绑定一个活动 `scm_repository`，其中包含 provider、外部仓库标识、API base 和加密凭据。
- GitHub/GitLab 通过统一 Provider contract 完成 Webhook 验签、PR/MR 归一化、PR 快照和 changed-file patch 获取。
- 不 clone 仓库、不调用本地 Git CLI、不建设代码索引。
- SCM 保存 PR 后发布进程内 `PullRequestChanged`；SCM 不直接依赖 Review。

### R6. One Review Engine

- 自动 Webhook 与人工 retry 必须进入同一个 `ReviewService.requestReview`。
- Review Context 仅包含 Requirement/AC、Project Knowledge evidence、PR metadata 和 changed-file patch。
- 一次结构化 AI 输出同时返回 AC coverage 与 Finding；禁止第二 Coverage Judge/ReviewProcessor。
- Review 对 `(pull_request_id, head_sha, engine_version)` 幂等；head SHA 变化创建新 Review，不覆盖历史。
- Review 长调用使用有界进程内执行器；状态 PENDING/RUNNING/COMPLETED/FAILED 可见，失败可人工重试。

### R7. Evidence and human decision

- Finding 生命周期：`OPEN → CONFIRMED → IN_PROGRESS → FIXED → VERIFIED → CLOSED`，旁路 `REJECTED`，`FIXED → IN_PROGRESS` 可打回。
- Finding 必须保存 review/requirement/ac/path/line/evidence/assignee/fixCommitSha/fingerprint。
- `acId`、knowledge `sourceId`、filePath、line 必须回查本次 Context 白名单；伪造引用不得落库。
- Finding 状态与整个 Review 的 `PENDING/APPROVE/REQUEST_CHANGES` 严格分离。
- REQUEST_CHANGES 后必须有新 head SHA 才能再次产生终局 Review Decision。

### R8. Package and dependency boundary

V2 顶层只包含：

`com.forgepilot.common/auth/project/requirement/scm/knowledge/ai/review`

Finding 归入 review。禁止独立 `agent/patch/mq/rag/repo/pullrequest/context/assistant/finding` 顶层包。

采用 package-by-feature，不强制每个模块建立 domain/application/infrastructure/web 四层。允许依赖：

```text
common ← auth, project, ai
common + project ← scm
common + project + ai ← knowledge
common + project + knowledge + ai ← requirement
common + project + scm + knowledge + requirement + ai ← review
```

ArchUnit 必须验证 feature cycle=0、禁止包不存在、SCM 不依赖 Review、跨 feature 不直接注入 Repository。

### R9. Clean data baseline

V2 从一个 `V1__init.sql` 开始，核心表 14 张：

`user_account, project, project_member, requirement, acceptance_criterion, knowledge_document, requirement_attachment, knowledge_chunk, scm_repository, pull_request, review, finding, finding_event, ai_call_log`。

不建 scm_connection、review_task、review_report、review_issue、review_decision、webhook_delivery、vector shadow table。新增表必须以已发生的业务事实和 ADR 说明无法用现有模型表达。

### R10. Frontend MVP

登录后的一级导航只保留：项目、研发需求、代码审查。

- 项目详情包含成员、SCM 配置和 Knowledge 文档状态。
- Requirement 详情包含 AC、指派、质量检查、附件和关联 PR。
- Review 详情包含 AC coverage、Finding、证据、人工状态和 PR Decision。
- 不暴露 Agent、Patch、Metrics、Workspace、AI Logs、评测设施和内部执行步骤。

### R11. Minimal infrastructure

目标技术组件限于 Java 17+、Spring Boot 3、Spring Security、Spring Data JPA、Flyway、PostgreSQL + pgvector、Vue 3、GitHub/GitLab API、OpenAI-compatible Chat/Embedding、Testcontainers、ArchUnit 和 Docker Compose。

不引入 RabbitMQ/Kafka/Redis/Elasticsearch/Milvus/Qdrant、Resilience4j Circuit Breaker、服务发现、API Gateway、新数据库、新微服务、独立 Sandbox 或完整 OTel/Prometheus/Grafana。

### R12. Legacy migration rule

- KEEP 实现白名单仅限 KnowledgeUploadValidator、PromptSanitizer、WebhookVerifier、OutboundUrlPolicy、FindingLifecycle、evaluation score 核心和 demo/evaluation 数据；迁移时仍需适配新异常/DTO。
- DiffSplitter、FindingCandidate/Evidence/Deduplicator、RequirementStatus/RuleChecker/Parser、Normalized PR records、AuthCookieService 全部降为 REWRITE/REFERENCE，优先迁测试和不变量。
- 明确 DROP Agent/Patch/MQ/Outbox/Legacy Review/Projection/第二 runtime/本地 Git/向量双写/运行时 DDL/model-service/sandbox/旧 Flyway/复杂观测和旧前端内部技术页面。
- 不得随整包删除 SCM contract fixtures、Webhook 安全测试、AC 全量补齐/证据降级测试、上传边界测试和评测判分方法。

## Acceptance Criteria

- [x] Legacy backend/resources/frontend/sandbox/deploy/evaluation/demo/docs/Trellis 已实际扫描。
- [x] 已从三视角完成复杂度、职责、循环依赖、KEEP/DROP 和组件必要性复审。
- [x] 核心流程可以用一句话讲清。
- [x] 每个 MVP 模块均通过“删除后产品是否成立”测试。
- [x] 独立 Finding、Assistant MVP、相关代码检索、多仓库、在线 Embedding 多版本和强制四层已删除。
- [x] 单一 Review Engine、SCM 事件边界和无循环依赖规则已明确。
- [x] 数据模型收敛为 14 张表，新增组件均对应真实业务/评测需求。
- [x] 错误 KEEP 已降级，成熟测试/数据/工具已从 DROP 中救回。
- [x] 《ForgePilot V2 最终方案候选版》、第二轮审查和更新后的实施蓝图已形成。
- [ ] 用户人工批准最终候选版；批准前不进入实施。

## Out of Scope

- 修改、删除、重构或继续加功能到 Legacy 产品代码。
- 生成 V2 工程、运行 task start、提交 commit、推送或创建 PR。
- 在核心 E2E 完成前实施任何 P1：Requirement Assistant、Workbench、多仓库、相关代码读取、报告导出或高级监控。

## Risks and Deferred Items

- 进程内 Review 执行不提供消息队列级持久性；MVP 通过状态可见、幂等和人工 retry 接受此边界。
- 一个项目一个仓库、一个 PR 一个 Requirement 是明确 MVP 约束；出现真实多对多需求后再设计。
- Embedding 换维度需要维护窗口和 Flyway/reindex，不承诺无停机切换。
- GitHub 先完成主线，GitLab 后完成同一 contract，用于证明 Adapter 的实际价值。
- Requirement Assistant 为 P1，不属于本候选版实施范围。

## Blocking Open Questions

无。当前只等待用户对最终候选版做人工评审；该评审不等于实施批准。
