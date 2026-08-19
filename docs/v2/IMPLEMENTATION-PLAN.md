# ForgePilot V2 实施蓝图

规范依据：[ARCHITECTURE.md](./ARCHITECTURE.md)（技术）+ [PRD.md](./PRD.md)（产品）。本文只定义**顺序与退出条件**，不重复规范内容。

Phase 0 已于 2026-08-19 获用户批准并完成 R2 契约复审（新增 ADR-009/010/011，修订 ADR-002/003/007/008）。当前指令仅为提交并推送方案，**Phase 1 尚未收到开始指令，不得自行启动**；启动后也只实施 Phase 1，Phase 2 及以后仍需前一 Phase 产物通过人工评审。

## 不可违反的实施纪律

- Legacy 只读；新实现必须位于单独绿地工程。
- 不按旧 package 搬运。KEEP 先迁特征测试，再决定是否迁实现。
- 只允许 `common/auth/project/requirement/scm/knowledge/ai/review` 顶层包。
- Finding 必须留在 review；SCM 只能发布事件，不能依赖 review。
- 禁止 Agent、Patch、MQ/Outbox、第二 Review Engine、第二 AI runtime、本地 Git/clone、向量双写和运行时 DDL。
- 任何 P1 功能不得插入核心 Phase：多轮 Assistant、Workbench、多仓库、相关代码读取、报告导出、高级监控。MVP 仅允许 Requirement 详情页的一次性结构化实现建议。
- **写代码前先查迁移矩阵**：该模块的 Legacy 资产是 KEEP / REWRITE / REFERENCE / DROP。

## Phase 0：冻结契约 ✅ 已完成并批准（2026-08-19）

- 冻结主流程、角色权限矩阵、Requirement/Finding/Review 状态。
- 冻结 16 表、8 顶层包、依赖规则、运行边界、SCM event contract。
- 架构争议决策落为 ADR-001..011。
- 文档收敛为单一事实源（PRD / ARCHITECTURE / PLAN / ADR / 迁移矩阵）。

## 纵切原则（适用于 Phase 2..7）

每个 Phase 必须交付**该阶段的最小可用真实界面**，而不是把全部前端堆到 Phase 7。一级导航仍只有三个，页面清单不变，改的只是建造顺序。这样每个 Phase 的 API 当期就有真实消费者，契约问题当期暴露、当期修正。

## Phase 1：最小绿地底座

- 单 Spring Boot 应用、Vue 应用、Postgres **15+** pgvector。
- 建 8 个顶层包、Flyway/Testcontainers/CI 骨架；本阶段不预建全部业务表，表随纵向 Phase 增加，发布首个版本前再 squash 为干净初始化迁移。
- ArchUnit：cycle=0、禁止包、scm 不依赖 review、跨模块 Repository 禁止。
- Testcontainers 验证从空库启动、约束和 pgvector；须实测 PostgreSQL 15+ 的列级 `ON DELETE SET NULL` 与 `UNIQUE NULLS NOT DISTINCT` 可用。
- **前端脚手架与视觉契约**：路由、请求层、设计令牌、组件基础；三方向视觉对比后由用户选定一个方向，结果写入 `.trellis/spec/frontend/`（含动效基线与 `prefers-reduced-motion`、设计漂移检查清单）。
- **评测契约**：固定指标定义、确定性评分器骨架，从既有 development 26 例中挑 10–15 例作为快速集，沿用既有 development/holdout 边界。本阶段不调用尚不存在的 Review Engine。
- **部署容量实测**：在目标 4 GB 部署机上实测 PostgreSQL + JVM 与现有常驻服务共同运行的常驻内存，确认 JVM/Postgres 上限与并发 Review 取值。
- 退出条件：不含任何业务 UI 也能证明边界不会长回旧架构；评测设施与前端脚手架不算业务代码，属本阶段范围。

## Phase 2：Auth + Project

- 本地账户、Cookie/Session、CSRF、登录失效。
- Project、ProjectMember、LEADER/DEVELOPER/REVIEWER、恰好一个 LEADER。
- 成员的项目级 SCM 身份（`scm_external_user_id` / `scm_username` / `scm_identity_verified_at`），由 LEADER 配置，`(project_id, scm_external_user_id)` 唯一（ADR-010）。
- ProjectAccessService 作为跨业务授权入口；业务模块接收 userId，不依赖 auth。
- 界面：登录页 + 项目列表 + 成员管理。
- 退出条件：跨项目猜 id 和角色越权集成测试全部拒绝；同项目内 SCM 身份唯一性用例通过。

## Phase 3：Requirement

- Requirement/AC CRUD、简化状态机（`DRAFT / READY / IN_DEVELOPMENT / DONE / CANCELED`，无 `IN_REVIEW`）、指派。
- 不可变 `requirement_revision` + 稳定 `ac_key`：创建 Requirement 时同步建 Revision 1，DRAFT 期间可原地编辑，`DRAFT → READY` 同事务冻结；其后修改由 LEADER 一次性创建新的已发布 Revision 并填写变更原因（ADR-011）。**禁止给 revision 加 `is_draft`/`status` 列**，可编辑性只由父 Requirement 状态决定。互为外键按"建 requirement → 建 revision → 建 AC → 回填 current_revision_id"顺序解决，不用 DEFERRABLE。
- 需求质量检查结果归属 Revision；DRAFT 期间正文或 AC 一改，同事务清空该 Revision 的质量结果。
- 需求版本链的复合外键与 CHECK 按 ADR-006 §6–8 落地。
- 需求附件不在本 Phase：Knowledge 模块在 Phase 4，附件（KnowledgeDocument + RequirementAttachment）随 Phase 4 一并实现，避免建临时存储再拆除。
- 确定性质量规则先实现；AI Quality 在 Phase 6 接入。
- 界面：需求列表 + 需求详情（含 AC 与版本历史）。
- 退出条件：无 AI/SCM 时可完成需求创建、确认和指派；READY 后正文与 AC 锁定、修改必须产生新 Revision 的规则通过测试；`review_activity` 此阶段恒为 `NO_PR`。

## Phase 4：AI Gateway + Knowledge

- 统一 chat/embed Gateway、timeout、一次 retry、PromptSanitizer、ai_call_log。
- KnowledgeUploadValidator、Chunk、单 vector 列、project-scoped TopK、维护式 reindex。
- 需求附件：上传即 KnowledgeDocument（source_type=REQUIREMENT_ATTACHMENT）+ RequirementAttachment 关系（ADR-005）。
- Requirement 详情页的一次性 Implementation Guidance：Requirement + AC + Project Knowledge → 实现清单、相关规则、风险提示；不建 conversation 表。
- 只支持一个 configured embedding model/dimension；`V1__init.sql` 为无维度 vector 列、不建向量索引，生产 Profile 确定后以独立 migration 创建 HNSW expression index（ADR-001）。
- 界面：知识上传与文档状态、需求详情页的实现建议展示。
- 退出条件：A 项目检索不到 B 项目；非法 UTF-8/超限/NUL/维度不匹配显式失败。

## Phase 5：GitHub SCM

- 一个项目一个活动 scm_repository；有 PR 后 provider + external_id 不可修改（ADR-010）。
- GitHub Webhook raw-byte HMAC、PR snapshot、changed-file patch。
- PR 作者快照（`author_external_user_id` / `author_username`）与派生映射 `author_user_id`（每次同步幂等重算），据此判定"本人 PR"；禁止按用户名授权（ADR-010）。
- `REQ-N` 分支/标题解析写入 requirement_id，解析失败不阻断（ADR-007）；每次关联变更与 `pull_request_requirement_event` 同事务写入，自动解析记为 `actor_type=SYSTEM`。
- 数据库幂等同步，发布 `PullRequestChanged`；不直接依赖 Review、不 clone。
- 界面：PR 列表与需求关联修改入口。
- 退出条件：重放不重复建 PR，非法签名不写业务数据，SCM compile dependency 不含 review；成员被移出项目后 `author_user_id` 自动置空、权限退化为仅 LEADER。

## Phase 6：Requirement Quality + Review Engine

- Requirement Quality：规则结果 + 项目知识 + 一次结构化 AI 输出。
- ReviewContext：Requirement/AC、Knowledge evidence、PR metadata、ChangedFile patch、truncation manifest。
- 唯一 Review Engine 产出 AC verdict + Finding；小 PR 单次调用，大 PR 分批产 candidate/evidence 后 Final Synthesis 统一合成（ADR-002）。
- ReviewOutputValidator 校验 acId/sourceId/path/line，补齐漏判 AC，伪造证据不得落库。
- Review 身份 `(pull_request_id, head_sha, requirement_revision_id)` 且 `NULLS NOT DISTINCT`；终局 Decision 闸门只认 `(pull_request_id, head_sha)`，写入前须行锁串行化（ADR-003）。
- Finding 跨轮血缘：`finding_key` / `evidence_hash` / `continuity` / `carried_from_finding_id`，`evidence_hash` 基于确定性源码证据；"上一轮"按 ADR-009 §12 的两条确定性查找规则实现，禁止各自约定隐含规则。
- 派生 `review_activity` 一次聚合查询，含 `FAILED` 档（ADR-011）。
- 有界进程内执行器、Review 状态、幂等 retry。
- Webhook 返回前持久化 PENDING Review；reconciliation 补偿漏触发与停滞任务；Review 保存 requirement_id、requirement_revision_id 与不可变 context snapshot。
- **评测增量试跑**：每完成一个实验臂（`Diff+LLM` → `+Requirement+AC` → `+Knowledge`）即在 development 集上跑一次，不等本 Phase 结束。Prompt、TopK、证据组织**只允许依据 development 集调整**；**禁止运行 holdout**。
- 界面：Review 详情只读页（AC 判定、Finding、证据、未审文件清单）。
- 退出条件：仓库只有一个 ReviewEngine；大 PR 未审文件显式呈现；AI 非法 JSON 不产生假成功；同一 head 并发终局决策的集成测试证明不产生冲突结论。

## Phase 7：人工闭环 + 三页面 UI

- Finding confirm/reject/assign/in-progress/fixed/verified/closed 与 finding_event；抑制项折叠呈现且可重新打开。
- Review APPROVE/REQUEST_CHANGES；REQUEST_CHANGES 后必须新 head 才能再次终局决定——改需求关联或需求版本都不解除该闸门；APPROVE 不自动完成 Requirement，由 LEADER 单独确认 DONE。
- 项目、研发需求、代码审查三个一级页面统一打磨与浏览器验收（响应式、键盘与焦点、对比度、reduced-motion、设计漂移）。
- 退出条件：需求→PR→Finding→退回→修复→新 Review→通过可由三角色重复演示。

## Phase 8：GitLab + 评测答辩

- GitLab Adapter 通过与 GitHub 同一 Provider contract。
- 适配 Legacy 38 例 fixture/score（既有切分：development 26 + holdout 12，不得重新切分），删除 Agent/Patch 运行字段。
- 配置冻结后同时报告 development、holdout、全量三组结果；**holdout 为首次运行，不得据其调 Prompt**。
- 独立呈报漏报率、误报率、AC verdict、结构失败、Token、latency 和 notRun；holdout 仅 12 例，须给出置信区间或明确的不确定性说明。
- 演示 Prompt injection、Webhook 重放、跨项目访问、大 Diff、AI outage。
- 退出条件：从版本化原始结果可重算论文数据；干净环境可复现部署和演示。

## 首个实施授权的最小范围

用户批准本方案后，第一次实施授权只覆盖 Phase 1。Phase 2 及以后需要在前一 Phase 产物通过人工评审后再继续，防止业务代码在边界尚未锁定时同时扩张。
