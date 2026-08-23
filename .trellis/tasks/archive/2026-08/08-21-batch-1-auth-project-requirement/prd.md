# 批次 1：Auth + Project + Requirement 纵向切片

对应 `docs/v2/IMPLEMENTATION-PLAN.md` 的 Phase 2 与 Phase 3，按 [D012](../../../../../docs/v2/DECISIONS.md#d012) 合并为一个批次交付。

## Goal

交付 ForgePilot 的第一条真实业务纵深：用户能登录，负责人能建项目、管成员、配成员的 SCM 身份，能写带 AC 的需求、确认发布、指派开发者，并在页面上完成上述全部操作。本批次结束时，系统在**没有 AI、没有 SCM、没有 Review** 的前提下已经可用。

本批次的价值不在功能数量，而在把三条最贵的地基一次打对：**项目隔离**（每条项目内引用都带 `project_id` 复合外键）、**角色权限**（恰一 LEADER 与跨项目不可见）、**需求版本化**（不可变 Revision 与稳定 `ac_key`）。这三条一旦在后续 Phase 才补，返工面覆盖全部业务表。

## Confirmed Facts

- Phase 1 底座已于 2026-08-21 验收，证据见 `.trellis/tasks/archive/2026-08/08-20-phase-1-foundation/result.md`。
- 顶层包仍限于 `common/auth/project/requirement/scm/knowledge/ai/review`；本批次只新建 `auth`、`project`、`requirement` 下的类。
- 数据模型上限仍为 16 张表。本批次落其中 6 张：`user_account`、`project`、`project_member`、`requirement`、`requirement_revision`、`acceptance_criterion`。**不新增任何表外结构。**
- 规划期完成三项研究，其中 PG15 + Hibernate 约束为真实实测；由此产生的 12 条实现裁定见 [D013](../../../../../docs/v2/DECISIONS.md#d013)，**没有一条改动 16 表定义**。
- PostgreSQL 15 与 pgvector 是硬依赖；Flyway 只增不改，业务表随本批次以新迁移加入。
- Phase 1 遗留前置：CI 尚未真实运行过；ArchUnit 的子包深度与 Repository 识别规则待加固（见下 R7）。

## In Scope

1. **认证**：本地账户、登录/登出、服务端会话、CSRF、密码哈希、强制失效（`session_version`）。
2. **项目与成员**：项目创建（创建者同事务成为 LEADER）、成员增删改角色、恰一 LEADER 不变式、LEADER 转移、项目级 SCM 身份配置。
3. **需求**：Requirement/AC 的创建与编辑、不可变 Revision、`ac_key` 稳定身份、`DRAFT/READY/IN_DEVELOPMENT/DONE/CANCELED` 状态机、指派。
4. **前端**：登录页、项目列表、成员管理、需求列表、需求详情、版本历史。全部落在既有三项一级信息架构内。
5. **测试**：跨项目隔离、角色越权、LEADER 唯一性与转移、SCM 身份唯一性、Revision 冻结、`ac_key` 稳定性、复合外键与状态机的定向集成测试。
6. **架构加固**：补齐 ArchUnit 的子包白名单与 Repository 识别规则（Phase 1 遗留项，本批次首次出现真实业务类，此时补最有价值）。

## Requirements

### R1. 项目隔离必须由数据库保证

- 除 `user_account` 与 `project` 外，本批次所有表携带 `project_id`；所有项目内引用使用含 `project_id` 的复合外键，被引用表提供对应唯一键。
- Repository 读路径一律接受 `projectId`，**禁止**裸 id 查询后再补权限判断。
- A 项目用户猜 B 项目的 requirement / revision / member id 必须不可见、不可操作，且由集成测试证明。

### R2. 角色与权限

- 每个项目恰有一个 LEADER：至多一个由数据库部分唯一索引保证，至少一个由 Service 事务保证（[D004](../../../../../docs/v2/DECISIONS.md#d004)，语义按 [D013.9](../../../../../docs/v2/DECISIONS.md#d013) 为每次提交后的不变式）。
- 权限判定按 `PRD.md` §3 的矩阵；本批次涉及的每一行都要有对应的拒绝路径测试。
- 「本人 PR」类判定依赖项目级 SCM 稳定外部 ID，本批次只落库与配置，不做授权使用（Phase 5 才有 PR）。

### R3. 需求版本化

- 创建需求时同事务建立 Revision 1；`DRAFT` 期间 Revision 1 可原地编辑；`DRAFT → READY` 同事务冻结。
- READY 之后的任何正文/AC 修改由 LEADER 发布新的不可变 Revision 并填写变更原因，旧 Revision 与旧 AC 永久保留。
- `ac_key` 是跨 Revision 稳定的业务身份，**禁止**用数据库行 id 或显示顺序代替；`sort_order` 仅用于显示。
- `requirement_revision` 的 `quality_json/quality_version/quality_checked_at` 本批次只建列并实现「DRAFT 正文或 AC 修改时同事务清空」，质量检查本身属 Phase 6。

### R4. 状态机纪律

- 持久状态仅 `DRAFT/READY/IN_DEVELOPMENT/DONE/CANCELED`。
- `READY → IN_DEVELOPMENT` 与**首次指派**同事务完成，后续更换负责人不再改变状态。
- `CANCELED` 可由任意非终态到达且不可恢复（[D013.4](../../../../../docs/v2/DECISIONS.md#d013)）。
- 本批次没有 AI/Webhook/PR/Review，但代码中不得预留任何由它们推进状态的入口。

### R5. 认证与会话

- 服务端进程内 `HttpSession` + Spring Security 默认机制，CSRF 用 cookie token repository，密码用 BCrypt（[D013.7](../../../../../docs/v2/DECISIONS.md#d013)）。
- 不新增 session 表、不引入 Redis。进程重启会话失效是被接受的代价，须在部署说明中写明。
- 业务模块不依赖 auth 的认证机制；账户展示信息经 `auth` 的只读 Query facade 读取（[D013.6](../../../../../docs/v2/DECISIONS.md#d013)）。

### R6. 前端

- 只在既有 `/projects`、`/projects/:id/members`、`/requirements`、`/requirements/:id` 路由内交付，**不新增一级菜单**。
- 遵循 `.trellis/spec/frontend/` 的设计契约、动效基线与 `prefers-reduced-motion` 规则。
- 需求状态与派生的评审活动分开呈现；本批次所有需求的评审活动恒为 `NO_PR`。
- 不使用虚构业务数据模拟未完成能力。

### R7. 架构与实现形态

- 复合外键关联统一采用 [D013.1](../../../../../docs/v2/DECISIONS.md#d013) 的变体 A，并写入 `.trellis/spec/backend/`。
- 外键保持 `NOT DEFERRABLE` 与 `MATCH SIMPLE`；需求创建走三步回填（[D013.10](../../../../../docs/v2/DECISIONS.md#d013)）。
- 约束冲突一律不捕获后继续，统一映射 409/422（[D013.11](../../../../../docs/v2/DECISIONS.md#d013)）。
- ArchUnit 补两条：feature 内部子包白名单（仅允许 `scm.github`/`scm.gitlab`/`ai.openai`）、Repository 识别不再只靠类名后缀。

## Acceptance Criteria

- [ ] **AC1**　空库执行 Flyway 后得到 6 张业务表；所有项目内引用均为含 `project_id` 的复合外键，且每个被引用表具备对应唯一键。真实 PostgreSQL 15 集成测试证明跨项目写入被数据库拒绝（23503），而不是只靠 Service 校验。
- [ ] **AC2**　用户可注册/由种子数据获得账户并登录；登出后旧会话不可用；改密码后其他会话失效；CSRF 缺失或错误的写请求被拒绝；密码永不回显、日志中不出现口令。
- [ ] **AC3**　任何登录用户可创建项目，且创建者在同一事务内成为该项目 LEADER；数据库拒绝同项目出现第二个 LEADER；LEADER 转移按「先降级 → flush → 再升级」实现，转移后仍恰有一个 LEADER。并发转移只有一个成功。
- [ ] **AC4**　`(project_id,user_id)` 与 `(project_id,scm_external_user_id)` 唯一性由数据库保证；SCM 身份只能由 LEADER 配置；`scm_username` 仅用于显示，任何授权判定都不读它。
- [ ] **AC5**　跨项目隔离集成测试通过：A 项目成员用 B 项目的 project/requirement/revision/member id 发起读写，全部得到 404 或 403，且不泄漏资源是否存在。
- [ ] **AC6**　角色越权集成测试通过：DEVELOPER/REVIEWER 执行 LEADER 专属操作（建需求、改 AC、置 READY、指派、配 SCM 身份、管成员）全部被拒。
- [ ] **AC7**　创建需求时同事务产生 Revision 1；三步回填后 `current_revision_id` 正确指向本需求的 Revision；把它指向别的需求或别的项目的 revision 被数据库拒绝。
- [ ] **AC8**　`DRAFT` 下 Revision 1 可原地编辑且修改会同事务清空 `quality_json`；`DRAFT → READY` 后正文与 AC 冻结，再修改必须产生新 Revision 并带变更原因；旧 Revision 与旧 AC 仍可读。
- [ ] **AC9**　`ac_key` 跨 Revision 稳定：同一 AC 在新 Revision 中保持相同 `ac_key`，调整 `sort_order` 不改变 `ac_key`；`(requirement_revision_id, ac_key)` 唯一性由数据库保证。
- [ ] **AC10**　状态机断言全部通过：仅 LEADER 可 `DRAFT→READY`、`→DONE`、`→CANCELED`；`READY→IN_DEVELOPMENT` 与首次指派同事务；换人不改状态；非法转换被拒；`CANCELED` 不可恢复。
- [ ] **AC11**　前端可完成完整闭环：登录 → 建项目 → 加成员并配 SCM 身份 → 写需求与 AC → 置 READY → 指派 → 查看版本历史。类型检查、lint、单元/交互测试、生产构建全绿；无新增一级菜单；键盘可达与 reduced-motion 契约不回归。
- [ ] **AC12**　ArchUnit 规则增至七条并全部通过，新增两条各有反证 fixture 证明非恒真；`scm` 仍不依赖 `review`；顶层包集合不变。
- [ ] **AC13**　`./mvnw -B -ntp verify` 与前端全套命令在干净环境通过；Compose 空库冷启动仍成功；CI 四个 job 全绿（含首次真实运行）。
- [ ] **AC14**　`result.md` 完整，记录实际命令与结果、偏差、Legacy 使用依据、风险与批次 2 前置条件；未触发 D013 之外的新决策，若触发则先停下请求批准。

## Out of Scope

- Knowledge 文档、上传、切片、Embedding、检索；需求附件关系（[D005](../../../../../docs/v2/DECISIONS.md#d005) 属批次 2）。
- AI Gateway、任何真实模型调用、需求质量检查、一次性实现建议。
- SCM 仓库配置、Webhook、PR、`REQ-<n>` 解析（[D013.2](../../../../../docs/v2/DECISIONS.md#d013) 只定义了 `<n>` 的含义，解析实现属 Phase 5）。
- Review、Finding、评审活动派生（本批次所有需求恒为 `NO_PR`）。
- 需求状态转换的审计留痕（[D013.3](../../../../../docs/v2/DECISIONS.md#d013) 明确列为 MVP 缺口）。
- 批次 2 及以后的任何实现。

## Execution Checkpoints

1. 本规划经确认后执行 `task.py start`。
2. 数据库迁移与实体映射先行并通过真实 PostgreSQL 集成测试，再写 Service 与 API——[D013.1](../../../../../docs/v2/DECISIONS.md#d013) 的映射形态若在真实实体上不成立，必须立刻停下重新裁定，不得改用 Service 校验绕过。
3. 后端 API 稳定后再做前端，避免前端对着未定契约返工。
4. 全部验收证据齐备后更新 `result.md`，展示提交分组等待确认；不自动推送。
5. 批次 1 完成后停止，等待批次 2 的单独授权。
