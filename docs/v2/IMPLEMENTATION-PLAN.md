# ForgePilot V2 实施蓝图

规范依据：[ARCHITECTURE.md](./ARCHITECTURE.md)（技术）+ [PRD.md](./PRD.md)（产品）。本文只定义**顺序与退出条件**，不重复规范内容。

Phase 0 已冻结（2026-08-19），Phase 1 可以开始；Phase 2 及以后需前一 Phase 产物通过人工评审。

## 不可违反的实施纪律

- Legacy 只读；新实现必须位于单独绿地工程。
- 不按旧 package 搬运。KEEP 先迁特征测试，再决定是否迁实现。
- 只允许 `common/auth/project/requirement/scm/knowledge/ai/review` 顶层包。
- Finding 必须留在 review；SCM 只能发布事件，不能依赖 review。
- 禁止 Agent、Patch、MQ/Outbox、第二 Review Engine、第二 AI runtime、本地 Git/clone、向量双写和运行时 DDL。
- 任何 P1 功能不得插入核心 Phase：Assistant、Workbench、多仓库、相关代码读取、报告导出、高级监控。
- **写代码前先查迁移矩阵**：该模块的 Legacy 资产是 KEEP / REWRITE / REFERENCE / DROP。

## Phase 0：冻结契约 ✅ 已完成（2026-08-19）

- 冻结主流程、角色权限矩阵、Requirement/Finding/Review 状态。
- 冻结 14 表、8 顶层包、依赖规则、运行边界、SCM event contract。
- 7 条争议决策落为 ADR-001..007。
- 文档收敛为单一事实源（PRD / ARCHITECTURE / PLAN / ADR / 迁移矩阵）。

## Phase 1：最小绿地底座

- 单 Spring Boot 应用、Vue 应用、Postgres pgvector。
- 建 8 个顶层包和 `V1__init.sql` 14 表（含 ADR-001/003/004/005 约定的列与索引约束）。
- ArchUnit：cycle=0、禁止包、scm 不依赖 review、跨模块 Repository 禁止。
- Testcontainers 验证从空库启动、约束和 pgvector。
- 退出条件：不含任何业务 UI 也能证明边界不会长回旧架构。

## Phase 2：Auth + Project

- 本地账户、Cookie/Session、CSRF、登录失效。
- Project、ProjectMember、LEADER/DEVELOPER/REVIEWER、恰好一个 LEADER。
- ProjectAccessService 作为跨业务授权入口；业务模块接收 userId，不依赖 auth。
- 退出条件：跨项目猜 id 和角色越权集成测试全部拒绝。

## Phase 3：Requirement

- Requirement/AC CRUD、简化状态机、指派。
- 需求附件不在本 Phase：Knowledge 模块在 Phase 4，附件（KnowledgeDocument + RequirementAttachment）随 Phase 4 一并实现，避免建临时存储再拆除。
- 确定性质量规则先实现；AI Quality 在 Phase 6 接入。
- 退出条件：无 AI/SCM 时可完成需求创建、确认、指派、提交评审。

## Phase 4：AI Gateway + Knowledge

- 统一 chat/embed Gateway、timeout、一次 retry、PromptSanitizer、ai_call_log。
- KnowledgeUploadValidator、Chunk、单 vector 列、project-scoped TopK、维护式 reindex。
- 需求附件：上传即 KnowledgeDocument（source_type=REQUIREMENT_ATTACHMENT）+ RequirementAttachment 关系（ADR-005）。
- 只支持一个 configured embedding model/dimension；`V1__init.sql` 为无维度 vector 列、不建向量索引，生产 Profile 确定后以独立 migration 创建 HNSW expression index（ADR-001）。
- 退出条件：A 项目检索不到 B 项目；非法 UTF-8/超限/NUL/维度不匹配显式失败。

## Phase 5：GitHub SCM

- 一个项目一个活动 scm_repository。
- GitHub Webhook raw-byte HMAC、PR snapshot、changed-file patch。
- `REQ-N` 分支/标题解析写入 requirement_id，解析失败不阻断（ADR-007）。
- 数据库幂等同步，发布 `PullRequestChanged`；不调用 Review、不 clone。
- 退出条件：重放不重复建 PR，非法签名不写业务数据，SCM compile dependency 不含 review。

## Phase 6：Requirement Quality + Review Engine

- Requirement Quality：规则结果 + 项目知识 + 一次结构化 AI 输出。
- ReviewContext：Requirement/AC、Knowledge evidence、PR metadata、ChangedFile patch、truncation manifest。
- 唯一 Review Engine 产出 AC verdict + Finding；小 PR 单次调用，大 PR 分批产 candidate/evidence 后 Final Synthesis 统一合成（ADR-002）。
- ReviewOutputValidator 校验 acId/sourceId/path/line，补齐漏判 AC，伪造证据不得落库。
- 有界进程内执行器、Review 状态、幂等 retry。
- 退出条件：仓库只有一个 ReviewEngine；大 PR 未审文件显式呈现；AI 非法 JSON 不产生假成功。

## Phase 7：人工闭环 + 三页面 UI

- Finding confirm/reject/assign/in-progress/fixed/verified/closed 与 finding_event。
- Review APPROVE/REQUEST_CHANGES；REQUEST_CHANGES 后必须新 head 才能再次终局决定。
- 项目、研发需求、代码审查三个一级页面完成 E2E。
- 退出条件：需求→PR→Finding→退回→修复→新 Review→通过可由三角色重复演示。

## Phase 8：GitLab + 评测答辩

- GitLab Adapter 通过与 GitHub 同一 Provider contract。
- 适配 Legacy 38 例 fixture/score，删除 Agent/Patch 运行字段。
- 独立呈报漏报率、误报率、AC verdict、结构失败、Token、latency 和 notRun。
- 演示 Prompt injection、Webhook 重放、跨项目访问、大 Diff、AI outage。
- 退出条件：从版本化原始结果可重算论文数据；干净环境可复现部署和演示。

## 首个实施授权的最小范围

即使用户随后批准实施，第一次授权也应只覆盖 Phase 0-1。Phase 2 及以后需要在 Phase 1 产物通过人工评审后再继续，防止业务代码在边界尚未锁定时同时扩张。
