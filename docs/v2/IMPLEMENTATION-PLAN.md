# ForgePilot V2 实施计划

规范依据：[ARCHITECTURE.md](./ARCHITECTURE.md)（技术规则）+ [PRD.md](./PRD.md)（产品规则）+ [DECISIONS.md](./DECISIONS.md)（决策理由）。本文只定义实施顺序、授权闸门、验证纪律和退出条件，不重复字段或业务规则。

状态：**Phase 0–8 已于 2026-08-22 全部完成并通过退出闸门**，逐阶段证据见文末「全部阶段已完成」一节列出的归档任务。2026-08-23 用户通过 [D017](./DECISIONS.md#d017) 批准一个独立的产品主链路补全任务；历史 Phase 小节继续作为存档，不因本次补全被重写。

## 不可违反的实施纪律

- Legacy 只读；写代码前先查 [LEGACY-MIGRATION-MATRIX.md](./LEGACY-MIGRATION-MATRIX.md)，按 KEEP/REWRITE/REFERENCE/DROP 处理，不整包复制。
- 只允许 `common/auth/project/requirement/scm/knowledge/ai/review` 八个顶层包；Finding 留在 `review`。
- 禁止 Agent、Patch、MQ/Outbox、第二 Review Engine、第二 AI runtime、本地 Git/clone、代码向量库和运行时 DDL。
- `scm` 不依赖 `review`；跨模块只经 Service/Query facade；项目内数据必须由 `project_id` 复合外键和查询过滤隔离。
- 一件事实只在一个权威文档定义；实现发现规则冲突时先停下，更新文档或新增决策，不用代码“自行解释”。
- 每个 Phase 单独建立 Trellis 任务；复杂任务必须有 `prd.md`、`design.md`、`implement.md`，经用户确认并 `task.py start` 后才可实现。
- 不自动提交或推送；提交前先展示文件分组和提交信息并取得用户确认。

## Phase 0：契约与治理 ✅

已完成：V2 方案冻结、R2.3 契约加固、权威文档收敛、Trellis 治理初始化。不得在本阶段创建业务源码或工程实现。

## Phase 1：最小绿地底座 ✅

### 目标产物

- 单 Spring Boot 模块化单体、Vue 3 前端、PostgreSQL 15+ 与 pgvector。
- Flyway、Testcontainers、ArchUnit、基础 CI 和可重复的空库启动。
- 只建立底座所需最小 schema；业务表随对应纵向 Phase 增加，首个可发布版本前再 squash 为干净初始化迁移。
- 前端路由、请求层、设计令牌、基础组件和三方向视觉对比；用户选定的视觉方向、动效基线、`prefers-reduced-motion` 与设计漂移清单写入 `.trellis/spec/frontend/`。
- 评测契约与确定性评分器骨架；从既有 development 26 例中选 10–15 例快速集，不调用尚不存在的 Review Engine，不运行 holdout。
- 目标 4 GB 部署机容量基线：PostgreSQL + 空后端 + 前端静态服务与现有常驻服务完成 5 分钟基线、2 分钟预热和至少 4 分钟稳定窗口；稳定窗口每 15 秒采样，至少 17 组。记录 RSS/PSS、JVM heap/direct memory、Postgres 参数、可用内存和 swap/OOM 情况，空载后至少保留 1 GB 可用内存。该短窗口由用户于 2026-08-20 明确批准，只证明 Phase 1 空栈的短时容量，不外推长期稳定性。

### 明确禁止

登录、项目、成员、需求、知识、SCM、Review、Finding、业务实体、业务迁移和任何业务 UI 均不在 Phase 1。

### 退出条件

空库启动、构建、CI、pgvector 与 PostgreSQL 15+ 硬约束验证全绿；ArchUnit 证明顶层包无环且 `scm` 不依赖 `review`；前端视觉方向已选定并固化；评测评分器能重算快速集；容量原始数据、命令和结论已版本化；无业务源码和业务表。

## Phase 2：Auth + Project ✅

- 本地账户、Cookie/Session、CSRF、登录失效；Project、ProjectMember、三角色和恰好一个 LEADER。
- 成员项目级 SCM 身份由 LEADER 配置，稳定外部 ID 唯一。
- 界面：登录、项目列表、成员管理。
- 退出：跨项目猜 id、角色越权、Leader 唯一性和 SCM 身份唯一性集成测试通过。

## Phase 3：Requirement ✅

- Requirement/AC CRUD、指派、`DRAFT/READY/IN_DEVELOPMENT/DONE/CANCELED` 状态；不可变 Revision 与稳定 `ac_key`。
- DRAFT 原地编辑，READY 冻结；之后由 LEADER 创建带变更原因的新 Revision；质量结果归属 Revision。
- 界面：需求列表、详情和版本历史；无 AI/SCM 也能完成创建、确认和指派。

## Phase 4：AI Gateway + Knowledge ✅

- 统一 chat/embed Gateway、超时、一次 retry、PromptSanitizer、`ai_call_log`。
- KnowledgeDocument/Chunk、单 vector 列、project-scoped 检索、附件关系与安全上传。
- 一次性 Requirement Implementation Guidance；不建 Conversation、SSE 或 Assistant 模块。
- 退出：项目隔离、非法 UTF-8/NUL/超限/维度不匹配显式失败，附件关系和检索边界通过测试。

## Phase 5：GitHub SCM ✅

- 一个项目一个活动 `scm_repository`；稳定身份为 provider + 规范化 instance identity + external id，有 PR 后冻结。
- 验签后读取 Provider 权威快照；保存 source revision/time、base/head、changed files、patch 和确定性 `review_input_fingerprint`。
- PR 关联解析、作者稳定外部 ID 映射、人工纠正和 `PullRequestChanged` 同步事件。
- 退出：重放幂等、乱序/并发不回退、Base/Diff 变化更新 fingerprint、非法签名不写数据、编译依赖无 `review`。

## Phase 6：Requirement Quality + Review Engine ✅

- 规则 + 一次结构化 AI Quality；唯一 Review Engine；大 PR 分批产 evidence/candidate、Final Synthesis 统一产出。
- Review 身份为 `(pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`，当前有效性同时匹配四项输入；旧 Review 保留。
- `PullRequestChanged` 在事务内同步创建 PENDING，失败则 SCM 回滚；事务提交后才调度执行器。reconciliation 只恢复已落库但未执行或停滞的 PENDING/RUNNING，禁止补建缺失 Review。
- attempt/token/lease fencing；旧 Worker 不得完成、失败或插入 Finding。Finding 永久父 FK `(project_id,review_id) → review(project_id,id)`，上下文由数据库约束触发器保持 NULL-safe 一致。
- Finding continuity 同时绑定 `evidence_hash + basis_hash`；Review activity 覆盖 `REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/MIXED/APPROVED/NO_PR`。
- development 集三臂增量试跑；只据 development 调参，不运行 holdout。
- 在目标 4 GB 机以生产 JVM/PostgreSQL 上限运行至少一个最大预算 Review，据实把并发 Review 冻结为 1 或 2，并记录峰值、失败与降级行为。
- 退出：非法 JSON 不假成功、大 PR 不静默丢文件、after-commit 失败可恢复、fencing/父 FK/上下文/聚合矩阵集成测试全绿；Review 详情只读页可用。

## Phase 7：人工闭环 + 三页面统一验收 ✅

- Finding 人工生命周期与审计；抑制项可按规则重开。
- Review Decision 仅 `PENDING → APPROVE|REQUEST_CHANGES` 一次；PR 行锁、完整前置校验和条件更新保证并发只有一个成功；同 head 的 REQUEST_CHANGES 只能由新 head 解除。
- 三个一级页面完成浏览器、可访问性、响应式和视觉漂移验收。
- 退出：三角色可重复演示“需求→PR→Finding→退回→修复→新 Review→通过→DONE”；Revision/Diff 变化显示 `REVIEW_REQUIRED`。

## Phase 8：GitLab + 正式评测与答辩 ✅

- GitLab 使用同一 Provider contract；沿用 development 26 + holdout 12，不重新切分。
- 配置冻结后首次运行 holdout；报告 Precision、Recall、误报/漏报、需求违规召回、AC verdict、结构失败、Token、耗时、notRun，并说明小样本不确定性。
- 干净环境可复现部署、演示和论文数据重算。

## 统一授权闸门

1. 前一批次通过退出闸门后，才可开启下一批次。批次划分见 [D012](./DECISIONS.md#d012)：批次 1 = Phase 2+3，批次 2 = Phase 4+5，批次 3 = Phase 6+7，Phase 8 单独且最后。闸门由谁执行见 [D014](./DECISIONS.md#d014)。
2. 开启一个批次仅表示可以创建该批次的任务级计划，不表示可以跳过计划直接写业务代码，也不表示下一批次已开启。
3. 每个批次开始前必须有 Trellis `prd.md`；复杂批次还必须有 `design.md`、`implement.md` 和验证清单；确认后执行 `task.py start`。
4. 每个批次完成后必须停下来做退出闸门自证，提交验证证据和风险，不得直接滑进下一批次。批次内部的 Phase 退出条件不变，逐条满足后才算该批次完成。
5. 任一退出条件未通过，不得归档任务为 completed，不得以"后续补齐"越过闸门。验收条件只有通过与不通过，**部分通过必须如实记为部分通过**。
6. holdout 仍锁定在 Phase 8 且只运行一次；Phase 6 的运行边界必须实测确定。批次化与评审委托都不放宽这两条（[D012](./DECISIONS.md#d012)、[D014](./DECISIONS.md#d014)）。

## 每阶段 `result.md` 验收模板

- 实际完成项、明确未完成项及与计划的偏差。
- 影响的模块、表、API、页面和配置；明确业务边界是否保持。
- 单元、集成、架构、前端、构建、性能和安全验证命令及结果。
- 跨项目隔离、角色权限和关键失败路径的证据。
- 是否触发新决策；没有则明确写“无新决策”。
- 使用的 Legacy 资产及 KEEP/REWRITE/REFERENCE/DROP 依据。
- 已知风险、回滚方法、遗留问题和下一 Phase 的前置条件。

## 测试与研究纪律

- 后端必须覆盖单元、Spring 集成、真实 PostgreSQL/pgvector Testcontainers、ArchUnit；前端覆盖类型检查、构建、交互和关键可访问性。
- SCM 测试必须包含验签、重放、分页/限流、错误映射、乱序和并发；Review 测试必须包含小/大 PR、截断清单、非法结构、reconciliation、fencing、并发 Decision 和跨项目隔离。
- 安全测试必须覆盖跨项目猜 ID、角色越权、凭据不回显、上传 NUL/超限/非法 UTF-8、SSRF 和 Prompt injection。
- 评测固定模型、温度、Prompt 版本和语料；只在 development 集调参，holdout 只在 Phase 8 配置冻结后首次运行，不得泄漏。
- 任何新增表、模块、一级页面、运行时依赖或改变已接受决策的行为都必须先补充并批准新的决策记录。

## 全部阶段已完成

Phase 0–8 已于 2026-08-22 全部完成并通过退出闸门，各阶段证据见
`.trellis/tasks/archive/2026-08/<任务>/result.md`：

| 批次 | 覆盖阶段 | 证据任务 |
|---|---|---|
| — | Phase 0 契约与治理 | `08-19-final-execution-plan-review`、`08-20-r2-3-contract-hardening` |
| — | Phase 1 最小绿地底座 | `08-20-phase-1-foundation` |
| 批次 1 | Phase 2 Auth + Project、Phase 3 Requirement | `08-21-batch-1-auth-project-requirement` |
| 批次 2 | Phase 4 AI + Knowledge、Phase 5 GitHub SCM | `08-21-batch-2-ai-knowledge-scm` |
| 批次 3 | Phase 6 Review Engine、Phase 7 人工闭环 | `08-21-batch-3-review-engine-human-loop` |
| — | Phase 8 GitLab + 正式评测 | `08-22-phase-8-gitlab-evaluation-defense` |
| — | 前端视觉与能力补全 | `08-22-frontend-visual-rebuild`、`08-22-restore-full-dynamic-frontend`、`08-22-frontend-capability-completion` |

Phase 0–8 验收时形态：16 张业务表 / 7 个 Flyway 迁移、8 个后端顶层包、307 个后端测试全绿；
前端当时为 3 个一级导航 / 7 条产品路由、32 个测试全绿；Compose 空卷冷启动通过；
三臂正式评测完成且 holdout 只运行一次。

## D017 产品主链路补全（已实现、验证并归档）

- 保持 16 张业务表、8 个后端顶层包、单一 Review Engine 与正式评测证据不变。
- 补齐 Project Knowledge/需求附件读写提升、按当前 Requirement 的 SQL 检索隔离、知识增强结构化 Guidance，以及 SCM 安全读取。
- 前端改为工作台、项目、研发需求、项目知识、仓库接入、代码审查六个入口，加入两份正式 Logo、真实向量元数据和显著但仍在上下文内的 AI 能力展示。
- 只增加关键回归测试，完成后各运行一次后端 `verify` 与前端 lint/typecheck/test/build；不做负载、Compose 容量或正式评测重跑。
- 实施与验收证据见 `.trellis/tasks/archive/2026-08/08-23-product-flow-completion/result.md`；工作提交和任务归档已按 Trellis Finish 流程完成。

后续工作仍受本文件的授权闸门与测试纪律约束：任何新增表、模块、一级页面、运行时依赖，
或改变已接受决策的行为，都必须先补充并批准新的决策记录，再立 Trellis 任务。
正式评测资产（配置冻结、语料清单、holdout 台账、原始输出）为不可变证据，
不得删除、覆盖或重跑；换实验必须换新的 case-set 与配置身份。
真实凭据只在人工验证时使用，且不进仓库。
