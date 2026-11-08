# ForgePilot 全量代码盘点

日期:2026-10-17 · 对应提交 `8b2a5fd`(main)· 目的:回答"我到底做了什么、有哪些功能",供逐段分析

这份文档按四层展开:规模账本 → 后端九个包(逐文件一句话)→ 数据模型(14 个迁移 → 21 张表)→ 对外功能面(70 个端点 + 11 条路由)。配套的后半部分在同一文件里继续:审查引擎解剖、测试面、评测资产、工程与运维、现状与答辩提纲。

---

## 一、规模账本(可复算)

后端主代码(backend/src/main/java):197 个 Java 文件、16,191 行。九个包分别是 ai 9 文件 709 行、auth 12/625、common 6/306、knowledge 14/1012、notification 10/783、project 20/1007、requirement 25/2027、review 50/6120、scm 50/3590,外加一个启动类。

后端测试(backend/src/test/java):63 个文件、15,870 行、385 个 `@Test` 标注;固定容器入口一次全量实际运行 432 项(差额来自参数化与重复执行,以运行输出为准)。

前端(frontend/src):34 个文件、约 10,300 行,其中 features 25 个文件 9,313 行、components 3/443、composables 2/190、app 2/236、lib 2/118;测试 18 个文件 3,298 行、66 项。

数据库:14 个 Flyway 迁移 → 21 张业务表(线上库 16 MB);对外 70 个 HTTP 端点;前端 11 条产品路由、6 个导航入口。

评测与论文资产:evaluation/tools 15 个 Python 文件 3,564 行;公开 case 12 个、私有正式语料一份(本机、被 gitignore);契约 5 份(含 metrics.md)。

文档与流程:docs/v2 六份、docs/deliverables 七份 HTML、docs/thesis 17 份材料;.trellis 三个 spec 域(backend/frontend/guides 共 18 份规范)、47 个已归档任务、4 个活动任务。

提交历史:613 次提交、63 个有提交的日期,从 2026-05-29 到 2026-10-17;按月为 5 月 9 次、6 月 19 次、7 月 171 次、8 月 325 次、9 月 50 次、10 月 39 次。

---

## 二、后端九个包(逐文件一句话)

### common(6 文件,306 行)

统一错误体、异常基类与限流;它是所有模块共用的最小底座。

- `ApiError.java` — 唯一的错误响应结构 `{code, message, traceId}`。
- `ApiException.java` — 业务异常的构造入口(notFound/conflict/forbidden 等)。
- `ApiExceptionHandler.java` — 把各类失败统一转换成 `ApiError`;框架异常也有兜底映射。
- `RateLimitFilter.java` — 按客户端地址限流一组路径的过滤器。
- `RateLimiter.java` — 进程内固定窗口计数器;超出跟踪上限后对新地址 fail-open(有注释说明理由)。
- `package-info.java` — 包级架构说明。

### auth(12 文件,625 行)

本地账号、表单登录、会话版本与 CSRF;它是"谁能进来"的全部答案。

- `SecurityConfig.java` — 过滤器链:表单登录、Cookie 形式 CSRF(`CsrfConfigurer::spa`)、限流、会话版本、401/403 统一响应体;`/actuator/**` 与注册放行,其余一律认证。
- `AccountDetailsService.java` — 把登录用户名解析到 `user_account`。
- `AccountPrincipal.java` — 已认证账号的 Principal(带 `userId` 与 `sessionVersion`)。
- `AccountResponse.java` — 全部认证端点返回的账号结构。
- `AccountView.java` — 账号的只读视图(供其他模块展示用户名)。
- `AuthController.java` — `/api/auth` 的注册、当前账号、改密、改显示名。
- `AuthService.java` — 只负责注册与改密(改密会提升会话版本号)。
- `SessionVersionFilter.java` — 强制 `user_account.session_version` 与当前会话一致;被吊销的会话拿到与未登录完全相同的 401。
- `UserAccount.java` — `user_account` 行:用户名、口令哈希、启用开关、会话版本。
- `UserAccountRepository.java` — Spring Data 仓库。
- `UserDirectory.java` — 其他模块读取账号数据的**唯一**途径。
- `package-info.java` — 包级架构说明。

### project(20 文件,1007 行)

项目边界、成员与角色、删除留痕;它是所有业务数据的隔离单位。

- `Project.java` — 项目边界本身(含 archive/unarchive 两个状态迁移)。
- `ProjectStatus.java` — ACTIVE / ARCHIVED。
- `ProjectRole.java` — 项目内角色 LEADER / REVIEWER / DEVELOPER。
- `ProjectAccessService.java` — 项目内一切操作的**唯一**授权入口(`requireMember` / `requireRole`)。
- `ProjectService.java` — 项目列表/创建/详情/归档/取消归档;创建者与 LEADER 是同一事务;归档只允许 LEADER,并取行锁让并发归档串行化。
- `ProjectController.java` — `/api/projects` 五个端点。
- `ProjectMember.java` — 某账号在某项目的成员关系(含角色集合)。
- `ProjectMemberService.java` — 成员增删改角色、批量添加、唯一 LEADER 的移交;批量编辑刻意不允许直接授予 LEADER。
- `ProjectMemberController.java` — 成员列表/候选/批量/改角色/移除/移交 LEADER。
- `ProjectMemberRemoving.java` — 成员移除事务内、删除前发布的事件(供其他模块清理)。
- `ProjectMemberRepository.java` — Spring Data 仓库。
- `ProjectRepository.java` — Spring Data 仓库。
- `ProjectResponse.java` — 项目响应体(带 `myRoles`,前端据此显示/隐藏动作)。
- `ProjectDeletionLog.java` — 三类删除写留痕的唯一途径。
- `ProjectDeletionRecord.java` — 一次删除这件事实本身,永久保存。
- `ProjectDeletionRecordRepository.java` — Spring Data 仓库。
- `DeletedResourceType.java` — 会被记入删除留痕的三类资源。
- `MemberResponse.java` / `MemberCandidateResponse.java` — 成员与候选人的响应体。
- `package-info.java` — 包级架构说明。

### requirement(25 文件,2027 行)

需求、不可变修订、验收条件、质量检查与一次性实现建议;它是"要做什么"的载体。

- `Requirement.java` — 需求的稳定身份与状态机字段。
- `RequirementStatus.java` — 需求生命周期词表。
- `RequirementService.java` — 需求与不可变修订的状态机(创建三步、冻结、指派、终态)。
- `RequirementController.java` — 需求 CRUD、修订、状态、指派、审查人、附件共 13 个端点。
- `RequirementRevision.java` — 需求文本的某一个版本,以及该版本的质量检查结果。
- `RequirementContent.java` — 调用方提交的某次修订文本与验收条件。
- `RequirementSummary.java` / `RequirementDetail.java` / `RevisionView.java` — 列表、详情与修订的响应体。
- `AcceptanceCriterion.java` — 属于某一次修订的验收条件(AC)。
- `AcceptanceCriterionView.java` / `CriterionInput.java` — AC 的读/写结构。
- `AcceptanceCriterionRepository.java` — Spring Data 仓库。
- `RequirementQualityService.java` — 确定性规则 + 一次 AI 评估的需求质量检查,`QUALITY_VERSION=quality-2`;超预算时不再调用 AI。
- `ImplementationGuidanceService.java` — 一次性、知识增强的实现建议(单次 provider 调用)。
- `ImplementationGuidance.java` / `QualityReport.java` — 建议与质量报告的持久化结构。
- `RequirementDirectory.java` — 其他模块询问需求的**唯一**途径。
- `RequirementRepository.java` — 每次读取都带 projectId。
- `RequirementRevisionRepository.java` — Spring Data 仓库。
- `RequirementAttachment.java` — 需求与知识文档的关系,附件归属的唯一事实源。
- `RequirementAttachmentService.java` — 需求附件的唯一拥有者(入库与晋升)。
- `RequirementAttachmentRepository.java` — Spring Data 仓库。
- `RemovedMemberAssignmentListener.java` — 成员离开项目时释放它在需求上的指派。
- `package-info.java` — 包级架构说明。

### knowledge(14 文件,1012 行)

项目知识、附件与向量检索;它是"上下文增强"的供给方。

- `KnowledgeService.java` — 项目知识的入库与检索(含晋升为项目公共知识)。
- `KnowledgeController.java` — 文档列表、原文/下载、上传、晋升、删除共 7 个端点。
- `KnowledgeDocument.java` — 项目知识与需求附件共用的内容载体。
- `KnowledgeStatus.java` / `KnowledgeSourceType.java` — 状态与来源词表。
- `KnowledgeDocumentView.java` — 面向产品读取的文档摘要(不含正文)。
- `KnowledgeUploadValidator.java` — 在任何分块或向量化之前先拒绝不该入库的文本。
- `KnowledgeIngestionProcessor.java` — 串行消费 PENDING 文档的唯一后台处理器。
- `KnowledgeReadRepository.java` — 知识页面的聚合读取。
- `KnowledgeDocumentRepository.java` / `KnowledgeChunkRepository.java` — Spring Data 仓库。
- `KnowledgeChunk.java` — 文档的一个可检索切片,外加"哪套 embedding 档案产生它"的审计信息。
- `ChunkSearchRepository.java` — 全代码库中唯一允许出现 `::vector` 与向量运算符的地方。
- `package-info.java` — 包级架构说明。

### ai(9 文件,709 行)

通往模型 provider 的唯一技术入口,以及每次尝试的台账。

- `AiGateway.java` — 唯一技术入口:请求组装、超时、错误分类与重试决策。
- `AiFailurePolicy.java` — 本项目允许的唯一一次重试,以及它何时适用。
- `PromptSanitizer.java` — 任何内容发往 provider 之前经过的最后一道处理。
- `AiCallLog.java` — 一次**尝试**一行(不是一次调用):用量、延迟、状态与错误。
- `AiCallStatus.java` / `AiUseCase.java` — 尝试结局与场景词表。
- `AiCallContext.java` — 一次调用属于哪些业务行(项目/需求/审查),以不透明 id 携带。
- `AiCallLogRepository.java` — Spring Data 仓库。
- `package-info.java` — 包级架构说明。

### scm(50 文件,3590 行)

两个 provider 的仓库接入、webhook 摄取、身份绑定与合并桥接;它是"代码从哪来、结论往哪去"。

- `ScmRepository.java` / `ScmRepositoryService.java` — 项目唯一活动仓库及其静态加密凭据的注册与重配置。
- `ScmRepositoryRepository.java` / `ScmRepositoryResponse.java` — 仓库持久化与响应体。
- `ScmController.java` — 仓库读写、PR 详情、PR↔需求关联修正共 5 个端点。
- `ScmSecretCipher.java` — 对 provider token 与 webhook 密钥做静态加密。
- `WebhookSignatureVerifier.java` — 对原始请求字节校验 provider 认证(GitHub HMAC / GitLab token)。
- `ScmWebhookSecurityConfig.java` — 两个 webhook 端点各成一条独立过滤器链,关掉会话与 Cookie CSRF。
- `PullRequest.java` / `PullRequestSnapshot.java` / `PullRequestResponse.java` — PR 的权威快照与其持久化/响应。
- `PullRequestSyncService.java` — 把"一次已认证的投递 + 一份权威快照"变成 `pull_request` 行。
- `PullRequestRepository.java` — Spring Data 仓库。
- `PullRequestChanged.java` / `PullRequestChangedListener.java`(在 review 包)— 更新事务内发布的事件与自动触发的那一半。
- `PullRequestAuthorMapper.java` — 重算 PR 作者与平台账号的对应。
- `PullRequestAssociationService.java` — PR 与需求关联关系的人工纠正。
- `PullRequestRequirementEvent.java` / `PullRequestRequirementEventRepository.java` — 关联变更的审计记录。
- `ReviewInputFingerprint.java` — 归一化审查输入的确定性哈希(决定"这轮是否与上轮同输入")。
- `ChangedFile.java` — 变更文件清单的一条(含 patch 与字符上限守卫)。
- `InstanceIdentity.java` — 把 api_base 归一化成仓库稳定身份的"实例"那一半。
- `OutboundUrlPolicy.java` — 是否允许向调用方提供的 URL 发出站请求(生产为空白名单)。
- `ScmIdentity.java` / `ScmIdentityService.java` / `ScmIdentityVerifier.java` — 用户全局 SCM 身份的验证、标注与吊销。
- `ScmIdentityController.java` / `ScmIdentityRepository.java` / `ScmIdentityResponse.java` / `ScmIdentityUsage.java` / `VerifiedScmUser.java` — 身份的接口、持久化与视图。
- `ProjectMemberScmBinding.java` / `ProjectMemberScmBindingRepository.java` — 成员在某项目选用某身份的一段历史。
- `ScmBindingService.java` / `ScmBindingController.java` / `ScmBindingResponse.java` — 绑定的申请、批准、驳回、吊销(含审批开关)。
- `ProjectScmIdentityAccess.java` — 供审查使用的"本人 PR"实时判定。
- `PullRequestDecisionGate.java` / `PullRequestDecisionActions.java` — "当前 head 上是否已有人裁定"的闸门与动作。
- `ScmPullRequestDecisionService.java` — 人工决定到 provider 的最小桥接:按被审查的 SHA 合并。
- `RequirementReferenceParser.java` — 从分支名/标题解析 `REQ-` 引用。
- `ScmActorType.java` — 事件发起者类型。
- `github/GitHubClient.java`、`github/GitHubWebhookController.java`、`gitlab/GitLabClient.java`、`gitlab/GitLabWebhookController.java` — 两个 provider 的读取与投递端点。
- `package-info.java`(含 github/gitlab 子包)— 包级架构说明。

### review(50 文件,6120 行)

唯一的审查引擎与它的全部闭环:执行、校验、血缘、裁定、生命周期与派生视图。这是整个系统的重心,下一节单独解剖。

### notification(10 文件,783 行)

把审查事件变成群消息;唯一的外部通知出口。

- `ReviewCompletedListener.java` — 三种事件三类收件人:AI 完成→审查人、AI 失败→负责人、人工决定→开发者或负责人。
- `NotificationChannelService.java` / `NotificationChannelController.java` — 项目通知渠道配置与测试发送。
- `NotificationChannel.java` / `NotificationChannelRepository.java` / `NotificationChannelType.java` / `NotificationViews.java` — 渠道模型、持久化、词表与响应体。
- `DingTalkSender.java` — 往钉钉自定义机器人发一条 Markdown 消息(含加签)。
- `ReviewNotificationRepository.java` — 一条通知需要的那几个事实的读取。
- `package-info.java` — 包级架构说明。

---

## 三、数据模型(14 个迁移 → 21 张表)

按迁移批次分组,每张表一句话。

V1(基础):启用 pgvector 扩展,无表。

V2(账号与项目):`user_account` 账号与口令哈希、`project` 项目边界、`project_member` 成员关系。

V3(需求):`requirement` 需求身份与状态、`requirement_revision` 不可变修订、`acceptance_criterion` 属于某次修订的验收条件。

V4(知识与 AI):`knowledge_document` 文档/附件内容载体、`requirement_attachment` 附件归属、`knowledge_chunk` 切片与向量、`ai_call_log` 每次尝试一行。

V5(SCM):`scm_repository` 项目活动仓库与加密凭据、`pull_request` 权威快照、`pull_request_requirement_event` 关联变更审计。

V6(审查):`review` 一轮审查、`finding` 一条发现(含血缘与三个哈希)、`finding_event` 人工决定留痕。

V7:给 `pull_request` 补标题。

V8(角色与身份):`project_member_role` 多角色、`scm_identity` 用户全局身份、`project_member_scm_binding` 成员在项目内的身份绑定;`user_account` 补会话版本等列。

V9:给 `finding` 补可读的问题陈述与建议(`explanation`/`suggestion`)。

V10(资源移除):需求软删列 + `project_deletion_record` 删除留痕。

V11–V13(通知):`project_notification_channel` 渠道配置,随后两次调整:签名密钥改为可选、补关键词。

V14:需求补审查人(`reviewer_id`)等列。

加上 `flyway_schema_history`,线上共 22 张表;业务表 21 张。

---

## 四、对外功能面(70 个端点 + 11 条路由)

按模块的端点清单(映射注解共 70 个,另有登录/登出两个由过滤器链直接处理)。

- 认证:`POST /api/auth/register`、`GET /api/auth/me`、`POST /api/auth/password`、`PATCH /api/auth/profile`;表单登录与登出由过滤器链处理。
- 项目:`GET/POST /api/projects`、`GET /api/projects/{id}`、`POST .../archive`、`POST .../unarchive`。
- 成员:`GET members`、`GET members/candidates`、`POST members/batch`、`PATCH members/{userId}/roles`、`DELETE members/{userId}`、`POST members/leader-transfer`。
- 需求:`GET/POST requirements`、`GET/PATCH/DELETE requirements/{id}`、`GET/POST revisions`、`POST status`、`POST assignee`、`POST reviewer`、`GET/POST attachments`、`GET attachments/{docId}/content`。
- 知识:`GET documents`、`GET documents/{id}/content`、`GET .../download`、`POST documents`、`POST .../promote`、`DELETE .../{id}`。
- 通知:`GET/PUT/DELETE notifications/dingtalk`、`POST notifications/dingtalk/test`。
- SCM 身份:`GET /api/scm/identities`、`POST /verify`、`PATCH /{id}`、`DELETE /{id}`。
- 仓库与 PR:`GET/POST scm/repositories`、`PATCH scm/repositories/{id}`、`GET pull-requests/{id}`、`PUT pull-requests/{id}/requirement`。
- 身份绑定:`GET binding-options`、`GET/POST bindings`、`POST bindings/{id}/approve|reject|revoke`。
- Webhook:`POST /api/scm/github/webhook`、`POST /api/scm/gitlab/webhook`。
- 审查:`POST pull-requests/{id}/reviews`(请求/重试审查)、`GET reviews`、`GET reviews/{id}`、`GET pull-requests/{id}/reviews`、`POST reviews/{id}/decision`。
- Finding:`POST findings/{id}/status`、`GET findings/{id}/events`。
- 派生视图:`GET requirements/{id}/review-activity`、`GET review-activity`、`GET requirements/{id}/coverage`、`GET review-calibration`。
- 运维:`GET /actuator/health`(只暴露 health)。

前端 11 条路由与 6 个导航入口:工作台、项目、研发需求、项目知识、仓库接入、代码审查;另有登录与账户两条非导航路由,以及 `/projects/:id/settings` 到仓库接入的重定向。各页面职责:工作台做项目选择与三条 AI 能力入口;项目页做创建与归档;成员页做批量添加、改角色、移交负责人与身份绑定审批;知识页做上传(逐文件结果)、状态轮询、原文阅读与晋升;仓库页做仓库注册、webhook 提示与通知渠道配置;需求页做修订、验收条件编辑、质量检查、指派、附件与实现建议;审查页做请求审查、筛选与 PR 历史,详情页做证据工作台、验收判定、覆盖、Finding 生命周期与终局决定。

## 五、审查引擎解剖(50 文件 / 6,120 行)

阶段序列:上下文快照 → 分批 → 候选项 + 验收证据 → 一次最终综合 → 校验 → 血缘 → 落库 → 事件 → 通知与人工闭环。

- `ReviewService.java` — 进入引擎的唯一入口:幂等判定、输入指纹、生成 PENDING 行。
- `ReviewExecutor.java` — worker 侧:抢占、持有租约、执行,并把结果与"置为完成"放进同一事务。
- `ReviewPipeline.java` — 一次审查的完整流程;三条"诚实失败"规则:批次回答解析不回来则整次失败且什么都不写;综合阶段校验不过同样失败,不存在"成功的空报告";finding 与终态一起提交,靠外键围栏拒绝过期 attempt 的写入。
- `ChangedFileBatcher.java` — 把变更文件切成批次,产出计划与覆盖(含未审查文件与截断标记)。
- `ReviewPrompts.java` — 两个提示词与两个输出 schema 的常量,`VERSION="review-5"`。
- `AiGateway.java` / `AiFailurePolicy.java` / `PromptSanitizer.java` / `AiCallLog.java` — 唯一技术入口、允许的唯一一次重试及其适用规则、发送前净化、每次尝试一行台账。
- `ReviewOutputValidator.java` — 结构、引用归属、逐字证据锚定与行号纠正;越界项丢弃并留下可审计记录。
- `FindingKeys.java` — `finding_key`、`evidence_hash`、`basis_hash` 三个确定性键,`RULE_VERSION="1"`;`explanation`/`suggestion`/`confidence` 进不了任何哈希。
- `FindingContinuityCalculator.java` — 相对上一轮判定 NEW / PERSISTING / SUPPRESSED,并记录 `carried_from`。
- `ReviewReconciliationScheduler.java` — 周期把停滞的审查放回轨道(无人抢占的 PENDING、租约过期的执行中)。
- `ReviewDecisionService.java` / `DecisionRepository.java` — 一次性人工裁定与它需要的读取。
- `ScmPullRequestDecisionService.java` — 决定到 provider 的桥接:按被审查的 SHA 合并。
- `FindingLifecycleService.java` / `FindingController.java` — 认领、确认、驳回、标记已修复、验证、关闭;每步写 `finding_event`。
- `ReviewInsightService.java` / `Wilson.java` — 覆盖度与校准两个派生视图;Wilson 给出二项比例区间。
- `PullRequestActivity.java` / `RequirementActivity.java` / `ReviewActivityService.java` — PR 与需求的派生审查活动状态。
- `ReviewFencingTest` 等测试类锁住的核心是:过期 attempt 不得写入、租约不得被双持、失败必须不留半份报告。

## 六、测试面(63 个类、385 个 @Test 标注,全量运行 432 项)

- 架构与数据库契约:`ArchitectureRulesTest`(8,ArchUnit 规则)、`DatabaseConstraintTest`(16)、`KnowledgeAndScmConstraintTest`(14)、`FoundationDatabaseTest`(3)、`ResourceRemovalTest`(4)、`V8MigrationTest`/`V9MigrationTest`/`V10MigrationTest`(各 1)。
- 认证与限流:`AuthApiTest`(9)、`ActuatorExposureTest`(1)、`RateLimiterTest`(3,三条承重性质:配额、窗口翻转、超上限的处置)。
- 知识与 AI:`AiGatewayTest`(13,真实套接字且不依赖任何真实凭据)、`KnowledgeServiceTest`(9)、`KnowledgeGuardTest`(6)、`KnowledgeIngestionProcessorTest`(6)、`PromptSanitizerTest`(4)、`KnowledgeVectorIndexTest`(3)、`AiGatewayConfigurationTest`(1)。
- 需求:`RequirementLifecycleTest`(12)、`RequirementQualityTest`(12)、`ImplementationGuidanceTest`(7)、`RequirementConcurrencyTest`(3)、`RequirementAttachmentServiceTest`(1)。
- 审查(最重的一块):`ReviewOutputValidatorTest`(26,校验是纯函数)、`ReviewDecisionTest`(16)、`ReviewEngineTest`(14)、`ReviewActivityTest`(14)、`ChangedFileBatcherTest`(12)、`FindingLifecycleTest`(12)、`ReviewPipelineIntegrationTest`(11)、`ReviewInsightTest`(10)、`ReviewFencingTest`(8)、`FindingContinuityTest`(7,跨轮血缘跑真实 PostgreSQL)、`ReviewExecutorFailureTest`(4)、`ReviewSnapshotConcurrencyTest`(3)。
- SCM:`GitHubWebhookIngestionTest`(13)、`ScmRepositoryApiTest`(10)、`PullRequestAssociationTest`(8)、`GitLabWebhookIngestionTest`(7)、`WebhookSignatureVerifierTest`(7)、`ReviewInputFingerprintTest`(6,规则被冻结)、`GitHubClientGuardTest`(5)、`ChangedFileLimitTest`(4)、`InstanceIdentityTest`(4)、`OutboundUrlPolicyTest`(4)、`ScmSecretKeyStartupTest`(4)、`ScmBindingServiceTest`(3)、`ScmIdentityFreezeConcurrencyTest`(2)、`ScmIdentityVerifierTest`(2)、`ScmSecretCipherTest`(2)、`ScmMergeTest`(以嵌套/参数化为主)。
- 端到端与通知:`BatchOneApiTest`(7,唯一覆盖真实 HTTP 全闭环:注册→登录→建项目→加成员→写需求→冻结→指派→读回修订,也是过滤器链、CSRF 与错误映射的守卫)、`NotificationChannelTest`(10)、`DingTalkSenderTest`(8)。

## 七、评测与论文资产(冻结边界)

- 公开 case 12 个(`biz-*`、`eng-*`、`fp-*`、`sec-*`、`java-*`、`typescript-*` 等);另有一份私有正式语料在本机、被 gitignore,不进仓库。
- 工具:`run_development.py`(三臂开发集)、`score.py`(确定性打分)、`formal_evaluation.py`(冻结/导入/运行/报告)、`postfreeze_legacy_adapter.py`、`postfreeze_provider_correction.py`(只改 endpoint 的审计订正)、`sensitivity_deleaked.py`(去提示敏感性,不改任何冻结文件),以及四个只吃合成数据的自测。
- 契约 5 份(manifest、formal-manifest、run、score-report 四个 schema 加 metrics.md);`reference-runs/phase1-reference.json` 与 `case-sets/phase1-quick.json`。
- 红线:配置冻结、语料清单、holdout 台账与原始输出绝不重跑或改写;新实验等于新 case set 加新配置身份;本机 `guard-no-holdout` 失败属预期(私有语料在本地)。

## 八、工程与运维

- compose 三个服务:postgres(pgvector 15)、backend(容器内 JDK 21 构建)、frontend(nginx);本机只映射 127.0.0.1:18080 与 18081。重建时不带任何 volume 参数,postgres 容器与数据卷保留。
- 密钥 fail-closed:缺少 `forgepilot.scm.secret-key` 上下文起不来,生产不提供默认值。
- CI 四个 job:Backend verify(固定容器入口全量)、Frontend gates(lint/typecheck/vitest/build)、Evaluation contract(语料校验、评分自测、参考报告重算、论文摘要校验、拒收 holdout 夹具)、Empty-stack Compose smoke(两次全新卷冷启动)。
- 部署流程:先备份(pg_dump custom + gzip + sha256 + 归档项数),再 `up --build --detach --wait`,随后校验健康(直连、nginx、公网)、前端资产哈希与 Flyway 版本,最后写部署记录。
- 本机约束:只有 JDK 17,后端走 `eclipse-temurin:21-jdk` 固定容器;内存 4GB,测试串行运行。

## 九、现状:哪些"做了但还没被用起来"

- 线上数据:181 轮审查(179 完成、2 失败)、374 条 Finding 收敛到 192 个唯一 `finding_key`、118 条继承、2 条抑制;AI 调用 676 次,失败全部发生在 09-22 到 09-25,之后没有新流量。
- 代码里已是 `review-5`,线上数据停在 `review-4`(09-25):当前提示词版本还没有任何一轮真实审查。
- 终局决定只有 16 次且全在 review-2 时代;走完"改好→验证→关闭"的完整生命周期 1 条。
- 三件零使用:SCM 身份与成员绑定(线上 0 行)、需求附件(0 行)、GitLab provider(4 个仓库全是 GitHub)。
- 界面缺口:审查列表不带提示词版本、模型与失败原因;AI 用量与延迟既无只读接口也无页面;失败的知识文档没有重试入口;列表没有分页。
- 89 项浏览器人工验收仍是 0 勾选。

## 十、如果只用三分钟讲"我做了什么"

- 我建了一个有边界的平台:9 包、21 张表、70 个端点、11 条路由;613 次提交、约 4.6 万行代码、432 项测试与四 job CI。
- 我建了唯一一条审查引擎:分批 → 候选项 → 综合 → 校验 → 血缘 → 落库,含三条"诚实失败"规则、租约抢占与周期对账。
- 我把跨轮次的去重与继承做成确定性机制:三个键与两个版本常量;线上 374 条 Finding 收敛到 192 个唯一缺陷,118 条继承,2 条抑制。
- 我把模型结论锚定在证据上:引用必须逐字存在于 diff,越界丢弃与行号纠正都有审计字段,三个哈希不允许看见模型散文。
- 我做了受控评测与冻结纪律:三臂召回 71/90/97,holdout 只跑一次,事后订正可审计。
- 我做了工程与安全:限流、令牌静态加密、webhook 原始字节验签、fail-closed 密钥、部署与回滚流程。
