# Research: Phase 2 (Auth + Project/Member) 既定契约抽取

- **Query**: 把 Phase 2（Auth + Project/Member）的全部既定契约从权威文档抽取成可直接写 `design.md` 的输入
- **Scope**: internal（仅 `docs/v2/**`、`.trellis/spec/backend/**`、`.trellis/spec/frontend/**`、Phase 1 `result.md`、`AGENTS.md`）
- **Date**: 2026-08-21

## 0. 引用口径与阅读说明

**本文只做摘录，不做设计。** 每条结论后面标出处；凡权威文档未写死的，一律进入 §8 开放问题，不在本文替它决定。

三条影响所有摘录的口径：

1. **`ARCHITECTURE.md` §2.1 的第三列是"关键约束"，不是完整 DDL。** 表里没出现的列（例如 `created_at`）既不代表禁止，也不代表已定义。本文用【明写】/【§2.4 推导】/【未定义】三档标注每一列。（`ARCHITECTURE.md:84-104`）
2. **单一事实源纪律**：16 表、依赖规则、状态机、运行边界只在 `ARCHITECTURE.md` 定义（`ARCHITECTURE.md:10`）；产品角色权限只在 `PRD.md` §3 定义（`docs/v2/README.md:13-14`）。冲突时先停下改文档，不用代码自行解释（`IMPLEMENTATION-PLAN.md:13`）。
3. **新增表 / 新增顶层包 / 改变已接受决策，都必须先补并批准新的决策记录**（`ARCHITECTURE.md:104`、`IMPLEMENTATION-PLAN.md:116`、`DECISIONS.md:144`）。Phase 2 若需要 16 表以外的表（例如 session 表），属于新决策而不是实现细节。

Phase 2 建的 3 张表（`user_account`、`project`、`project_member`）是 16 张表预算中的前 3 张（`ARCHITECTURE.md:82`、`docs/v2/README.md:25`）；批次 1 的另外 3 张属于 Phase 3（`requirement`、`requirement_revision`、`acceptance_criterion`，`IMPLEMENTATION-PLAN.md:48-51`）。

---

## 1. 三张表的完整定义

### 1.1 `user_account` — 本地演示账户

权威行：`ARCHITECTURE.md:86`（§2.1）——「`username` unique；password_hash、enabled、session_version」。

| 列 | 类型倾向 | 可空性 | 文档依据 |
|---|---|---|---|
| `id` | `BIGINT` identity，主键 | NOT NULL | 【§2.4 推导】`ARCHITECTURE.md:240`「主键 `id`，BIGINT identity」 |
| `username` | 未定义（长度/字符集/大小写规则均无） | 未定义 | 【明写】列名与 unique：`ARCHITECTURE.md:86` |
| `password_hash` | 未定义（算法与长度均无） | 未定义 | 【明写】列名：`ARCHITECTURE.md:86` |
| `enabled` | boolean 倾向（文档只给列名） | 未定义 | 【明写】列名：`ARCHITECTURE.md:86` |
| `session_version` | 整型倾向（文档只给列名） | 未定义 | 【明写】列名：`ARCHITECTURE.md:86` |
| `created_at` / `updated_at` | `timestamptz`，UTC 存储 | — | 【§2.4 推导】`ARCHITECTURE.md:242`；§2.1 未声明本表是否有时间列 |

约束：

| 约束 | 保证方 | 出处 |
|---|---|---|
| `UNIQUE(username)` | DB | 【明写】`ARCHITECTURE.md:86` |
| 本表是**全局**表，不带 `project_id` | DB/建模 | 【明写】`ARCHITECTURE.md:133`「除全局 `user_account` 与项目根 `project` 外，所有项目作用域表都携带 `project_id`」 |

**文档明确没有的东西**（不要发明）：email、显示名、全局角色列、锁定/失败计数列。全局角色不存在——角色是项目级的（`PRD.md:45`、`ARCHITECTURE.md:88`）；登录失败锁定在 Legacy 是**单机内存**实现且判定为 REFERENCE（`LEGACY-MIGRATION-MATRIX.md:19`），即不落库。

### 1.2 `project` — 项目边界

权威行：`ARCHITECTURE.md:87`（§2.1）——「name、created_by（→ `user_account`）、status」。

| 列 | 类型倾向 | 可空性 | 文档依据 |
|---|---|---|---|
| `id` | `BIGINT` identity，主键 | NOT NULL | 【§2.4 推导】`ARCHITECTURE.md:240` |
| `name` | 未定义（长度、是否唯一均无） | 未定义 | 【明写】列名：`ARCHITECTURE.md:87` |
| `created_by` | `BIGINT`，FK → `user_account(id)` | 未定义 | 【明写】列与引用目标：`ARCHITECTURE.md:87` |
| `status` | `varchar` + `CHECK`，Java 侧 enum，全大写下划线 | 未定义 | 【明写】列名：`ARCHITECTURE.md:87`；【§2.4 推导】存储形态：`ARCHITECTURE.md:243`。**取值集合未定义** |
| `created_at` / `updated_at` | `timestamptz` | — | 【§2.4 推导】`ARCHITECTURE.md:242` |

约束与建模事实：

- `project` 是**项目根**，本身不带 `project_id`（`ARCHITECTURE.md:133`）。
- §2.1 **没有**给 `project` 规定额外唯一键（对比 `requirement`/`review` 等表都明写了 `UNIQUE(project_id,id)`）；项目作用域表通过 `project_id → project(id)` 引用它（`ARCHITECTURE.md:133` 的总则）。
- `project.created_by → user_account` 是跨模块 FK，但**不属于**§2.3 所说的「审计 actor 唯一例外」那一类，它属于「项目根表引用全局表」（`ARCHITECTURE.md:133`、`ARCHITECTURE.md:87`）。

### 1.3 `project_member` — 成员、角色与项目级 SCM 身份

权威行：`ARCHITECTURE.md:88`（§2.1），完整原文：

> 成员、角色与项目级 SCM 身份 | `(project_id,user_id)` unique；`UNIQUE(project_id) WHERE role='LEADER'`，Service 事务保证至少一个 LEADER（D004）；`scm_external_user_id`（权限依据）、`scm_username`（仅显示）、`scm_identity_verified_at`，`(project_id,scm_external_user_id)` unique（D010）

| 列 | 类型倾向 | 可空性 | 文档依据 |
|---|---|---|---|
| `id` | `BIGINT` identity，主键 | NOT NULL | 【§2.4 推导】`ARCHITECTURE.md:240` |
| `project_id` | `BIGINT`，FK → `project(id)` | NOT NULL（项目作用域表必带） | 【明写】唯一键含该列：`ARCHITECTURE.md:88`；【总则】`ARCHITECTURE.md:133` |
| `user_id` | `BIGINT`，FK → `user_account(id)` | NOT NULL（唯一键组成部分） | 【明写】`ARCHITECTURE.md:88`；关系图 `ARCHITECTURE.md:110` |
| `role` | `varchar` + `CHECK IN ('LEADER','DEVELOPER','REVIEWER')` | 未定义（部分唯一索引依赖它，实质不可空） | 【推导】列名来自 `UNIQUE(project_id) WHERE role='LEADER'`（`ARCHITECTURE.md:88`）；三个取值来自 `PRD.md:47-61` 表头与 `LEGACY-MIGRATION-MATRIX.md:21`；存储形态 `ARCHITECTURE.md:243` |
| `scm_external_user_id` | 未定义（字符串/数字未定） | 未定义 | 【明写】列名 + 语义「权限依据」：`ARCHITECTURE.md:88`、`DECISIONS.md:107`、`PRD.md:152` |
| `scm_username` | 未定义 | 未定义 | 【明写】列名 + 语义「仅显示」：`ARCHITECTURE.md:88` |
| `scm_identity_verified_at` | `timestamptz` | 未定义 | 【明写】列名：`ARCHITECTURE.md:88`；【§2.4 推导】时间列类型 `ARCHITECTURE.md:242`。**语义未定义** |
| `created_at` / `updated_at` | `timestamptz` | — | 【§2.4 推导】`ARCHITECTURE.md:242` |

约束清单：

| # | 约束 | 形态 | 出处 |
|---|---|---|---|
| C1 | `UNIQUE(project_id, user_id)` | 普通复合唯一 | 【明写】`ARCHITECTURE.md:88` |
| C2 | `UNIQUE(project_id) WHERE role='LEADER'` | **部分唯一索引** | 【明写】`ARCHITECTURE.md:88`、`DECISIONS.md:47` |
| C3 | `UNIQUE(project_id, scm_external_user_id)` | 普通复合唯一 | 【明写】`ARCHITECTURE.md:88`（D010） |
| C4 | 至少一个 LEADER | **Service 事务**，非 DB | 【明写】`ARCHITECTURE.md:88`、`DECISIONS.md:47` |

两条与 C1/C2 相关的事实补充：

- **C1 同时是三条下游复合外键的目标唯一键**（§2.3，见下一节）。Phase 2 必须现在就把它建对，否则 Phase 3/5/6 的 FK 建不出来。
- §2.1 **没有**给 `project_member` 规定 `UNIQUE(project_id, id)`；§2.3 中所有指向它的引用目标都是 `(project_id, user_id)`（`ARCHITECTURE.md:138-139,163,175`），因此不需要按 `(project_id,id)` 建键。
- PostgreSQL 里带 `WHERE` 的唯一性只能写成 `CREATE UNIQUE INDEX ... WHERE ...`，不能写成表级 `UNIQUE` 约束——这是 C2 的语法事实，不是设计选择。

### 1.4 约束责任划分：DB 保证 vs Service 事务保证

| 不变式 | 谁保证 | 出处 |
|---|---|---|
| 每个项目**至多**一个 LEADER | **数据库**（部分唯一索引 C2） | `DECISIONS.md:47`、`ARCHITECTURE.md:88` |
| 每个项目**至少**一个 LEADER | **Service 事务** | `DECISIONS.md:47`、`ARCHITECTURE.md:88` |
| 一个用户在一个项目至多一条成员记录 | 数据库（C1） | `ARCHITECTURE.md:88` |
| 一个 SCM 外部身份在一个项目至多绑定一个成员 | 数据库（C3） | `ARCHITECTURE.md:88`、`DECISIONS.md:107` |
| `username` 全局唯一 | 数据库 | `ARCHITECTURE.md:86` |
| 拒绝跨项目写入 | **数据库**（含 `project_id` 的复合外键） | `ARCHITECTURE.md:133`、`DECISIONS.md:65` |
| 读路径必须携带 `projectId`，禁止裸 id 查询后补权限判断 | **Repository 纪律**（非 DB） | `ARCHITECTURE.md:133`、`.trellis/spec/backend/database-guidelines.md:84-86` |
| 约束冲突 → HTTP 409/422 | Service / 异常映射 | `ARCHITECTURE.md:133`、`DECISIONS.md:71` |
| 约束触发器与 ORM 若不兼容，**必须先新增决策**，不得静默降级为无测试的 Service 纪律 | 流程 | `DECISIONS.md:71`、`DECISIONS.md:144` |

> D012 明确说明：批次 1 被刻意排在第一位，就是为了在「第一次真正建业务表」时暴露数据库约束与 ORM 的兼容性风险（`DECISIONS.md:144`）。

---

## 2. 复合外键链（§2.3）

**总则原文**（`ARCHITECTURE.md:133`）：

> 除全局 `user_account` 与项目根 `project` 外，所有项目作用域表都携带 `project_id`。项目内外键必须把 `project_id` 一并带入，被引用表提供对应复合唯一键；**指向全局 `user_account` 的审计 actor 是唯一例外**。数据库负责拒绝跨项目写入，Repository 读路径仍必须接受 `projectId`，禁止裸 id 查询后再补权限判断。约束冲突统一映射为 409/422。

### 2.1 A 类：项目内引用，必须带 `project_id`（目标是 `project_member` 的 C1）

| 引用方（所属 Phase） | 复合外键 | 出处 |
|---|---|---|
| `requirement.assignee`（Phase 3） | `(project_id, assignee_id) -> project_member(project_id, user_id)` | `ARCHITECTURE.md:138-139` |
| `pull_request.author_user_id`（Phase 5） | `(project_id, author_user_id) -> project_member(project_id, user_id)`，**列级 `ON DELETE SET NULL (author_user_id)`** | `ARCHITECTURE.md:160-164`、`DECISIONS.md:107` |
| `finding.assignee_id`（Phase 6） | `(project_id, assignee_id) -> project_member(project_id, user_id)` | `ARCHITECTURE.md:175` |

关联事实：

- 列级 `ON DELETE SET NULL` 是 **PostgreSQL 15 硬依赖的两处语法之一**（另一处是 `UNIQUE NULLS NOT DISTINCT`）（`ARCHITECTURE.md:448`、`PRD.md:184`、`DECISIONS.md:113`）。
- 可空复合外键使用 `MATCH SIMPLE`，任一列为 NULL 时整条 FK 不校验（`ARCHITECTURE.md:196`）。这对 Phase 5 的 `author_user_id` 直接相关，对 Phase 2 的意义是：`project_member` 的唯一键必须真实存在，不能靠可空 FK 反证。

### 2.2 B 类：指向全局 `user_account` 的审计 actor（唯一例外，**不带 `project_id`**）

| 引用方（所属 Phase） | 外键 | 出处 |
|---|---|---|
| `pull_request_requirement_event.actor_user_id`（可空，Phase 5） | `→ user_account` | `ARCHITECTURE.md:97` |
| `finding_event.actor_id`（Phase 6/7） | `→ user_account` | `ARCHITECTURE.md:100` |

理由原文（`ARCHITECTURE.md:219`）：

> 审计表的 `actor_id` 指向 `user_account`，因为退出项目不能抹掉既成事实；`pull_request.author_user_id` 与 Finding assignee 指向 `project_member`，因为成员退出后活权限必须失效。

### 2.3 C 类：项目根表引用全局表

| 引用方 | 外键 | 出处 |
|---|---|---|
| `project.created_by` | `→ user_account` | `ARCHITECTURE.md:87` |

`project` 与 `user_account` 都不在「项目作用域表」范围内（`ARCHITECTURE.md:133`），因此这条不属于 A 类，也不属于 §2.3 所说的"审计 actor 例外"。

### 2.4 D 类：`project_id` 本身的引用

§2.3 的引用清单没有逐条列出 `X.project_id → project(id)`，它是「所有项目作用域表都携带 `project_id`」这一总则的直接含义（`ARCHITECTURE.md:133`）。**标注：这是总则推导，不是逐条明写。**

### 2.5 对 Phase 2 的净含义

Phase 2 自身只需要建 `project_member.project_id → project(id)` 与 `project_member.user_id → user_account(id)` 两条普通 FK，加上 §1.3 的 C1–C3。但 **C1 是三条未来复合 FK 的唯一支点**，`ON DELETE` 行为的缺口见 §8 Q11。

---

## 3. 角色权限矩阵 → 可测断言

### 3.1 权威原文（`PRD.md` §3）

- 「每个项目**恰有一个 LEADER**。不设独立 OWNER。AI 只生成分析结果，**不改变任何业务状态**。」（`PRD.md:45`）
- 「**跨项目一律不可见、不可操作。**」（`PRD.md:63`）

矩阵中与 Phase 2 直接相关的行（`PRD.md:47-50`）：

| 动作 | LEADER | DEVELOPER | REVIEWER | 行号 |
|---|:--:|:--:|:--:|---|
| 创建项目、管理成员与角色 | ✅ | ❌ | ❌ | `PRD.md:49` |
| 配置 SCM 仓库、上传项目知识 | ✅ | ❌ | ❌ | `PRD.md:50` |

其余 10 行（`PRD.md:51-61`：需求 CRUD、质量检查、指派、实现建议、PR 关联、触发 Review、Finding 生命周期、Review Decision、取消需求）属于 Phase 3–7，但**全部复用 Phase 2 建立的同一套 `project_member.role` 模型**，Phase 2 的角色枚举与查询接口必须能支撑它们。

补充（Phase 2 相关但在矩阵之外）：成员的项目级 SCM 身份**由 LEADER 配置，开发者不得自行声明**（`PRD.md:152` P11、`DECISIONS.md:107`、`IMPLEMENTATION-PLAN.md:43`）。

### 3.2 可测断言

命名 `A<n>`，每条都能写成一个集成测试。**期望 HTTP 状态码只在文档写死处标注**；未写死的见 §8 Q18。

| # | 断言 | 依据 |
|---|---|---|
| A1 | 项目内角色为 DEVELOPER 的用户调用"添加成员/改角色/移除成员"被拒绝 | `PRD.md:49` |
| A2 | 项目内角色为 REVIEWER 的用户调用同上被拒绝 | `PRD.md:49` |
| A3 | 项目内角色为 LEADER 的用户调用同上成功 | `PRD.md:49` |
| A4 | 非该项目成员（哪怕在别的项目是 LEADER）对该项目的成员管理动作被拒绝 | `PRD.md:63` |
| A5 | 非该项目成员读取该项目详情/成员列表不可见 | `PRD.md:63` |
| A6 | A 项目用户直接用 B 项目的 `projectId` 或成员 id 猜测访问，不可见 | `PRD.md:63`、`PRD.md:177`、`ARCHITECTURE.md:233`、`IMPLEMENTATION-PLAN.md:45` |
| A7 | DEVELOPER/REVIEWER 配置成员 SCM 身份（含配置自己的）被拒绝；只有 LEADER 可配置 | `PRD.md:152`、`DECISIONS.md:107` |
| A8 | 把项目唯一的 LEADER 降级或移除被拒绝（"至少一个"由 Service 事务保证） | `DECISIONS.md:47`、`ARCHITECTURE.md:88` |
| A9 | 并发把两名成员置为 LEADER，只有一个成功，另一个映射为 409/422 | C2 + `ARCHITECTURE.md:133` |
| A10 | 同一项目重复添加同一 `user_id` 被唯一约束拒绝，映射 409/422 | C1 + `ARCHITECTURE.md:133` |
| A11 | 同一项目两名成员配置同一 `scm_external_user_id` 被拒绝；**不同项目**配置相同 external id 允许（唯一键含 `project_id`） | C3 + `DECISIONS.md:107` |
| A12 | `scm_username` 不参与任何唯一约束与任何授权判定 | `ARCHITECTURE.md:88`「仅显示」、`PRD.md:152`「禁止按用户名授权」（**唯一约束的"不存在"是从 §2.1 已逐条列出两条唯一键推导的，见 §8 Q21**） |
| A13 | 任何响应体不回显 `password_hash` 或任何凭据 | `IMPLEMENTATION-PLAN.md:114` |
| A14 | 项目内资源的 REST 路径都带 `projectId` 段 | `ARCHITECTURE.md:245` |
| A15 | Repository 读路径接受 `projectId`；不存在"先按裸 id 查再补判权限"的代码路径 | `ARCHITECTURE.md:133`、`.trellis/spec/backend/database-guidelines.md:84-86` |

---

## 4. 认证形态：文档说了什么

### 4.1 全部相关表述（逐条摘录，无补充）

| 出处 | 原文/要点 |
|---|---|
| `ARCHITECTURE.md:21` | `auth` 包 = 「登录、Cookie/Session、Spring Security」 |
| `ARCHITECTURE.md:86` | `user_account`：`username` unique；`password_hash`、`enabled`、`session_version` |
| `ARCHITECTURE.md:434` | Spring Security 对应需求 =「三角色与人工决策可信边界」；删除后果 =「只能做单用户 Demo」 |
| `ARCHITECTURE.md:445` | **不引入** Redis（以及 MQ/ES/向量服务等） |
| `ARCHITECTURE.md:103-104` | 明确"不建"的表清单；新增表必须有业务事实 + 新决策记录 |
| `PRD.md:69` | MVP 必须有「最小账户、项目成员与三角色」 |
| `IMPLEMENTATION-PLAN.md:42` | Phase 2 =「本地账户、**Cookie/Session、CSRF、登录失效**；Project、ProjectMember、三角色和恰好一个 LEADER」 |
| `AGENTS.md:44` | 批次 1 范围复述：local accounts, Cookie/Session, CSRF, ... |
| `LEGACY-MIGRATION-MATRIX.md:15` | Legacy `AuthService` 价值 =「登录、防枚举、审计、sessionVersion」，判定 REWRITE |
| `LEGACY-MIGRATION-MATRIX.md:17` | Legacy `TokenAuthenticationFilter` =「Bearer/Cookie、sessionVersion **再校验**」，判定 REWRITE，「改用 V2 认证契约」 |
| `LEGACY-MIGRATION-MATRIX.md:18` | Legacy `AuthCookieService` =「HttpOnly/Secure/SameSite」，判定 REWRITE，「保留安全属性**测试**比复制实现更有价值」 |
| `LEGACY-MIGRATION-MATRIX.md:19` | Legacy `LoginAttemptGuard` =「单机内存锁定」，判定 REFERENCE，「不宣称分布式限流」 |
| `.trellis/spec/frontend/hook-guidelines.md:37` | 前端 `requestJson` 始终 `credentials: "same-origin"`（Cookie 会随请求发出） |
| `.trellis/spec/frontend/hook-guidelines.md:51-54` | 「When CSRF protection is introduced, the token belongs in `options.headers` of `requestJson` — that is the single intended injection point」；Phase 1 未实现，**不得新增第二条请求路径** |

**结论：文档规定的是"形态"（Cookie/Session + CSRF + 登录失效 + `session_version` 列存在 + Cookie 安全属性），没有规定任何一项的机制细节。** 密码哈希算法、session 存储介质、`session_version` 递增时机、CSRF token 传递方式、失效时长在 `PRD/ARCHITECTURE/DECISIONS/IMPLEMENTATION-PLAN` 中**均无任何表述**（全仓 grep 无 bcrypt/argon/scrypt/pbkdf2 命中）→ 见 §8 Q1–Q5。

### 4.2 §1.3 分层规则对 Auth 的具体含义

依赖表原文（`ARCHITECTURE.md:56-62`）：

```text
common   ←  auth, project, ai
common, project                              ←  scm
common, project, ai                          ←  knowledge
common, project, knowledge, ai               ←  requirement
common, project, scm, knowledge, requirement, ai  ←  review
```

关键约束原文（`ARCHITECTURE.md:64`）：

> 业务模块**不依赖 auth**：Controller 从登录上下文取 `userId` 后作为参数传入业务 Service。

由这两条可直接推出的分层含义（前 4 条是直接读出，第 5 条已标注为推导）：

1. **`auth` 只依赖 `common`；`project` 只依赖 `common`。二者互不依赖**（`ARCHITECTURE.md:57`）。`auth` 不能查 `project_member`，`project` 不能调 `auth`。
2. **认证（你是谁）在 `auth`；项目内授权（你在这个项目能干什么）在 `project`。** `project` 包的职责明写包含 `ProjectAccessService`（`ARCHITECTURE.md:22`）与「成员、角色、项目隔离」（`ARCHITECTURE.md:47`）。
3. **业务 Service 的方法签名显式接收 `userId` 参数**，不从 SecurityContext 里读（`ARCHITECTURE.md:64`）。取登录上下文这件事只发生在 Controller。
4. **ArchUnit 会在编译期挡住违规**：跨 feature 不直接注入对方 `*Repository`（`ARCHITECTURE.md:75`）、Controller 不直连跨模块 Repository（`ARCHITECTURE.md:76`），对应 `ArchitectureRulesTest` 的 `crossFeatureRepositoriesAreNotInjectedDirectly` 与 `controllersCannotReachCrossFeatureRepositories`（`.trellis/spec/backend/quality-guidelines.md:35-39`）。
5. 【**推导，非明写**】既然项目角色存在 `project_member` 且 `auth` 不能依赖 `project`，登录会话就无法在登录时装载"项目角色"作为全局 Spring Security 权限来做 `hasRole(...)` 式判定；角色判定只能发生在 `project` 侧、按 `(projectId, userId)` 查询。这条是 `ARCHITECTURE.md:57` + `ARCHITECTURE.md:88` 的必然结果，但文档没有明写这句话。
6. **反向缺口**：`project` 添加成员时必须解析"哪个用户"，但它不能依赖 `auth` 去读 `user_account` → 见 §8 Q8（本文标记的最关键开放问题之一）。

---

## 5. SCM 身份（D010）

### 5.1 权威表述

| 出处 | 原文要点 |
|---|---|
| `PRD.md:152`（P11） | 「"本人 PR" 由项目级 SCM 稳定外部 ID 判定，**禁止按用户名授权**；身份由 LEADER 配置，开发者不得自行声明」 |
| `DECISIONS.md:107`（D010） | 「成员的"本人 PR"权限使用项目级稳定外部用户 ID，禁止用户名比对；身份由 LEADER 通过 SCM API 配置。PR 保存不可变作者外部 ID/用户名快照和可重算的本地映射，成员退出后映射由列级 `ON DELETE SET NULL` 清空」 |
| `DECISIONS.md:111` | 理由：「用户名可变且可被复用」 |
| `ARCHITECTURE.md:88` | `scm_external_user_id`（权限依据）、`scm_username`（仅显示）、`scm_identity_verified_at`，`(project_id,scm_external_user_id)` unique |
| `IMPLEMENTATION-PLAN.md:43` | Phase 2：「成员项目级 SCM 身份由 LEADER 配置，稳定外部 ID 唯一」 |
| `IMPLEMENTATION-PLAN.md:45` | Phase 2 退出条件含「SCM 身份唯一性集成测试通过」 |

### 5.2 Phase 2 必须落库的内容

- `project_member` 上的三列：`scm_external_user_id`、`scm_username`、`scm_identity_verified_at`（`ARCHITECTURE.md:88`）。
- 唯一约束 C3 `UNIQUE(project_id, scm_external_user_id)`（`ARCHITECTURE.md:88`）。
- LEADER 配置/修改成员 SCM 身份的用例与权限（`PRD.md:152`、`IMPLEMENTATION-PLAN.md:43`）。
- 断言 A7、A11、A12（§3.2）。

### 5.3 要等到 Phase 5 才有意义的行为

| 行为 | 归属 | 出处 |
|---|---|---|
| `pull_request.author_external_user_id` / `author_username` 不可变作者快照 | Phase 5 | `ARCHITECTURE.md:96`、`DECISIONS.md:107` |
| `pull_request.author_user_id` 可重算映射 + 列级 `ON DELETE SET NULL` | Phase 5 | `ARCHITECTURE.md:96,163-164` |
| 真正的"本人 PR"授权判定（改 PR↔需求关联、触发 Review） | Phase 5/6/7 | `PRD.md:55-56`、`DECISIONS.md:76` |
| 通过 SCM API 验证外部身份（provider client 到 Phase 5 才存在） | Phase 5 | `IMPLEMENTATION-PLAN.md:60-65`；与 `DECISIONS.md:107` 的"通过 SCM API 配置"存在时序缺口 → §8 Q19 |
| `scm_repository`、provider、instance identity | Phase 5 | `ARCHITECTURE.md:95`、`IMPLEMENTATION-PLAN.md:61-62` |

---

## 6. Legacy 取舍（Auth / Project / Member / Common 一节）

分类口径原文（`LEGACY-MIGRATION-MATRIX.md:7`）：`KEEP` = 源代码可低成本迁移并补测试；`REWRITE` = 保留业务但按 V2 边界重新实现；`REFERENCE` = 只继承算法/安全策略/Prompt/测试思想；`DROP` = 不进入 V2。
纪律原文（`LEGACY-MIGRATION-MATRIX.md:3,9`）：Legacy 已归档为只读参考仓库 `LinYsssss/reposage`（基线 `96137dd3…`）；**不依赖、不以子模块引入、不整包复制**；即使 KEEP 也必须先迁特征/安全测试，旧 Flyway 历史一律不进 V2。同一纪律在 `AGENTS.md:59-64` 与 `IMPLEMENTATION-PLAN.md:9` 复述。

| # | Legacy 资产 | 判定 | V2 去向 | 保留什么 / 丢弃什么 | 出处 |
|---|---|---|---|---|---|
| L1 | `auth/AuthService` | **REWRITE** | `auth.AuthService` | 保留：登录用例、防枚举、审计与 sessionVersion 的业务思路（「业务成熟」）。丢弃：与旧实体、旧 Token、旧审计链的耦合 | `LEGACY-MIGRATION-MATRIX.md:15` |
| L2 | `auth/TokenService` | REFERENCE | `auth` | 只继承"最小 claim"思路；**不原样继承私有 HMAC Token 协议** | `:16` |
| L3 | `auth/TokenAuthenticationFilter` | **REWRITE** | `auth` | 保留：过滤流程形状 + `sessionVersion` **再校验**的时机。丢弃：Bearer/自定义 Token 契约，改用 V2 认证契约 | `:17` |
| L4 | `auth/AuthCookieService` | **REWRITE** | `auth` | 保留：**HttpOnly/Secure/SameSite 的安全属性测试**（矩阵原话：保留测试比复制实现更有价值）。丢弃：绑定旧配置的实现代码 | `:18` |
| L5 | `auth/LoginAttemptGuard` | REFERENCE | `auth` | 只继承单机内存锁定思路，可重写轻量版本；**不宣称分布式限流** → 含义：不落库、不新增表 | `:19` |
| L6 | `project/ProjectEntity`、`ProjectService` | **REWRITE** | `project` | 保留：项目生命周期用例。丢弃：旧表结构、旧聚合、旧授权边界（换新表 + 统一授权边界） | `:20` |
| L7 | `member/ProjectMemberEntity`、`ProjectRole` | **REWRITE** | `project` | 保留：LEADER/DEVELOPER/REVIEWER 三角色语义。丢弃：member 独立成包——**member 归入 `project`，不增加顶层包** | `:21` |
| L8 | `member/ProjectMemberService` | **REWRITE** | `project.ProjectMemberService` | 保留：唯一负责人、**转移**、权限规则。丢弃：对旧 common/project 的循环依赖。恰一 LEADER 见 D004 | `:22` |
| L9 | `project/ProjectCleanupService` | **DROP** | 无 | 完全不进 V2。理由：同时依赖十余领域，是依赖团核心；**改用 FK / 领域内删除** | `:23` |
| L10 | `common/security/CryptoService` | **REWRITE** | `common.security.SecretCipher` | 保留：信封加密 + 不回显契约。丢弃：旧密钥管理，需重新设计。（消费方是 `scm_repository` 的加密凭据 → 实际需求在 **Phase 5**，`ARCHITECTURE.md:95`） | `:24` |
| L11 | `common/security/SecurityAuditLogger` | **REWRITE** | `common.audit` | 保留：必要的安全审计事件。丢弃：复杂观测链（不迁完整可观测性栈） | `:25` |
| L12 | `common/api/ApiResponse`、异常映射 | **REWRITE** | `common.web` | 保留：统一 API 响应/异常映射的形态。丢弃：**历史 error code 与 HTTP 状态兼容债务**（`ARCHITECTURE.md:246` 明确"不复用 Legacy error code"） | `:26` |

**跨节提醒**：`LEGACY-MIGRATION-MATRIX.md` 的 SCM 一节里 `git/OutboundUrlPolicy`（KEEP → `common.security.OutboundUrlPolicy`，`:72`）虽然落点在 `common`，但其需求（SSRF 防护）属于 Phase 5 的出站 HTTP，不是 Phase 2 的交付。

**Phase 1 遗留前置条件（与 L12 直接绑定）**：`common.web` 错误契约落地时必须同步填写 `.trellis/spec/backend/error-handling.md` 与 `logging-guidelines.md`（`IMPLEMENTATION-PLAN.md:122`、Phase 1 `result.md:229-230`）。这两份 spec 目前如实标注为空（`error-handling.md:3-11`、`logging-guidelines.md:3-11`）。

---

## 7. 必须覆盖的测试形态

来源：Phase 2 退出条件（`IMPLEMENTATION-PLAN.md:45`）、§2.3 固定集成测试（`ARCHITECTURE.md:233`）、测试与研究纪律（`IMPLEMENTATION-PLAN.md:112-114`）、Phase 1 沉淀的后端约定（`.trellis/spec/backend/**`）、Phase 1 遗留前置条件（`result.md:223-231`）。

### 7.1 Phase 2 退出条件直接对应的四类（`IMPLEMENTATION-PLAN.md:45`）

| # | 测试 | 类型 | 依据 |
|---|---|---|---|
| T1 | **跨项目猜 id**：A 项目用户用 B 项目的 `projectId`/成员 id 访问，全部不可见、不可操作 | 集成（真实 PG） | `IMPLEMENTATION-PLAN.md:45`、`ARCHITECTURE.md:233`、`PRD.md:63,177` |
| T2 | **角色越权**：DEVELOPER/REVIEWER 执行"创建项目、管理成员与角色"被拒绝（A1/A2/A7） | 集成 | `IMPLEMENTATION-PLAN.md:45`、`PRD.md:49` |
| T3 | **Leader 唯一性**：(a) DB 层——并发/重复置两个 LEADER 只成功一个（部分唯一索引）；(b) Service 层——降级/移除唯一 LEADER 被拒绝 | 集成（必须真实 PG，H2 证明不了部分唯一索引） | `IMPLEMENTATION-PLAN.md:45`、`DECISIONS.md:47`、`database-guidelines.md:49-68` |
| T4 | **SCM 身份唯一性**：同项目重复 `scm_external_user_id` 被拒绝；跨项目相同 external id 允许 | 集成 | `IMPLEMENTATION-PLAN.md:45`、`ARCHITECTURE.md:88` |

### 7.2 §2.3 固定集成测试清单（`ARCHITECTURE.md:233`）中属于 Phase 2 的部分

原文五条：「A 项目用户猜 B 项目 requirement/document/review/finding id；附件关系与投影不一致；Finding 无父 Review 或父子上下文不一致；乱序 Webhook 回退；过期 Worker 持旧 token 写入」。

- 只有**第一条**在 Phase 2 有对应物（T1），且 Phase 2 尚无 requirement/document/review/finding 表，可测对象是 `project` 与 `project_member`。
- 其余四条分别属于 Phase 4（附件投影）、Phase 6（Finding 父 FK、fencing）、Phase 5（乱序 Webhook）。**Phase 2 不要提前实现它们的被测对象。**

### 7.3 测试纪律强制项（`IMPLEMENTATION-PLAN.md:112-114`）

| # | 测试 | 类型 | 依据 |
|---|---|---|---|
| T5 | 单元 + Spring 集成 + **真实 PostgreSQL Testcontainers** + ArchUnit 四类齐全 | 全部 | `IMPLEMENTATION-PLAN.md:112` |
| T6 | 安全：跨项目猜 ID、角色越权、**凭据不回显** | 集成 | `IMPLEMENTATION-PLAN.md:114` |
| T7 | 前端：类型检查、构建、交互、关键可访问性（登录 / 项目列表 / 成员管理三屏） | 前端 | `IMPLEMENTATION-PLAN.md:112`、`IMPLEMENTATION-PLAN.md:44` |

### 7.4 数据库测试硬约束（`.trellis/spec/backend/database-guidelines.md`）

| # | 规则 | 依据 |
|---|---|---|
| T8 | 禁止 H2 或任何内存替代；禁止"缺 Docker 就跳过"分支、`@Disabled`、`assumeTrue` —— 跑不了的数据库测试必须让构建失败 | `database-guidelines.md:65-68`；`quality-guidelines.md:118-119` 有对应的 grep 检查 |
| T9 | 新数据库测试沿用 `@DynamicPropertySource` 注入容器坐标，不新增第二套接线方式 | `database-guidelines.md:56-58` |
| T10 | 迁移只增不改：Phase 2 的业务表必须是新版本迁移文件，不得修改已应用的 `V1__foundation.sql` | `database-guidelines.md:37-39`；另见 §8 Q22 的措辞冲突 |
| T11 | 引入实体必须同时引入建表迁移（`ddl-auto: validate` 会在启动时失败，这是预期结果） | `database-guidelines.md:81-83` |

### 7.5 ArchUnit 加固（Phase 2 的明确责任）

| # | 责任 | 依据 |
|---|---|---|
| T12 | **cycle 规则非空化**：Phase 1 只有 bootstrap 类与 8 个 `package-info`，`featureSlicesAreFreeOfCycles` 目前无可选中的类，"不得描述为已证明"；**第一个创建跨包生产代码的 Phase 负责让它非空**——那就是 Phase 2 | `quality-guidelines.md:41-46` |
| T13 | 补齐 ArchUnit 的**子包深度**规则（阻止 `project.domain.infrastructure.web` 这类四层脚手架） | `IMPLEMENTATION-PLAN.md:122`、Phase 1 `result.md:202-205,228` |
| T14 | 补齐 **Repository 识别**规则（当前只按类名后缀识别，`ProjectRepositoryImpl`/`ProjectDao` 会漏） | 同上 |
| T15 | 新增规则必须同批次附**反向探针**（counter-probe）；靠"没选中任何类"而通过的规则不算强制 | `quality-guidelines.md:48-50` |
| T16 | 验证 `auth` 与 `project` 互不依赖（`ARCHITECTURE.md:57`）、业务 Controller 不直连跨模块 Repository（`:76`） | `ARCHITECTURE.md:70-76`、`quality-guidelines.md:35-39` |

### 7.6 构建与检查命令（既有，不新造）

- 闸门：`cd backend && ./mvnw -B -ntp verify`（`quality-guidelines.md:104`）。
- Phase 2 会让 `quality-guidelines.md:113` 的 grep（`class .*Controller|class .*Service|@Entity|@Table` 期望无命中）与 `:116` 的建表 DDL grep 首次**合法地**产生命中——这两条 grep 的措辞是「Expect no match **until the owning phase**」/「outside an authorized phase」，Phase 2 就是 owning phase。落地时应同步更新该文件的期望说明。

---

## 8. 开放问题（文档未定义，实现必须回答）

**共 23 条。** 每条给出：问题 / 文档为什么没覆盖 / 已有的边界条件（只用于约束选项，不代表本文已选定）。

### 认证机制（文档只写了形态，没写机制）

**Q1 — 密码哈希算法与参数。**
文档只有 `password_hash` 列名（`ARCHITECTURE.md:86`）。全仓 `docs/v2/**` grep 无 bcrypt/argon2/scrypt/pbkdf2 命中。
为什么没覆盖：R2.3 基线的收敛目标是"16 表、依赖方向、状态机、D001–D011"（`DECISIONS.md:146`），密码学参数不在冻结范围内。
已有边界：4 GB 目标机与 JVM 包络 `-Xms128m -Xmx384m -XX:MaxDirectMemorySize=128m …`（`quality-guidelines.md:90`）；改动该包络需**完整重跑已批准的容量协议**（`quality-guidelines.md:93-97`）。内存开销大的 KDF 参数会直接撞上这条。

**Q2 — Session 存储介质。**
文档只写「Cookie/Session」（`ARCHITECTURE.md:21`、`IMPLEMENTATION-PLAN.md:42`），没说是 Servlet 内存 session、JDBC session 表，还是签名 Cookie。
为什么没覆盖：`ARCHITECTURE.md` §2 只定义 16 张业务表，session 不是业务事实，落在表清单之外。
已有边界：**不引入 Redis**（`ARCHITECTURE.md:445`）；16 表清单中没有 session 表，**新增表必须有新决策记录**（`ARCHITECTURE.md:104`、`IMPLEMENTATION-PLAN.md:116`）；单进程有界执行器模型（`ARCHITECTURE.md:443`）。→ 若选 JDBC session 表，必须先走新决策流程，不能当实现细节处理。

**Q3 — `session_version` 的语义与递增时机。**
`ARCHITECTURE.md:86` 只给列名；Legacy 侧只说明它被用于"再校验"（`LEGACY-MIGRATION-MATRIX.md:15,17`）。未定义：初值、谁递增、改密码/禁用账户/角色变更/"登出全部设备"是否递增、递增后既有会话的失效路径与响应。
为什么没覆盖：这是 Legacy 资产判定表里的一个能力描述，从未被提升为 V2 的规则条款。

**Q4 — CSRF token 的生成、存储与传递方式。**
`IMPLEMENTATION-PLAN.md:42` 只写了"CSRF"两个字。前端只规定了**注入点**（`requestJson` 的 `options.headers`，且不得新增并行请求路径，`hook-guidelines.md:51-54`），没规定协议。未定义：double-submit cookie 还是 session 绑定 token、header 名称、哪些方法豁免、SPA 首次如何获取 token。
为什么没覆盖：`ARCHITECTURE.md` 未设"安全协议"章节，CSRF 只在实施计划里作为 Phase 2 交付项被点名。

**Q5 — "登录失效"的具体含义。**
`IMPLEMENTATION-PLAN.md:42` 写了"登录失效"，未定义是空闲超时还是绝对超时、时长、失效后返回的状态码，以及前端的跳转行为。

**Q6 — 账户如何产生。**
`PRD.md:69` 只说"最小账户"；`PRD.md` §3 权限矩阵**没有任何"创建账户"的行**，也没有全局管理员角色。没有账户就无法登录，因此必答：注册端点？迁移 seed？启动时创建？管理命令？
为什么没覆盖：PRD 的角色矩阵是**项目内**动作矩阵，账户供给属于项目之外，落在矩阵覆盖范围外。
已有边界：`V1__foundation.sql` 刻意不含 seed 行（`database-guidelines.md:34-36`）。

### 项目与成员生命周期

**Q7 — 项目创建者与 LEADER 的关系（本文判定为最关键之一）。**
`PRD.md:49` 把"创建项目"放在 LEADER 列，但用户在项目创建**之前**在该项目没有任何角色；`ARCHITECTURE.md:87` 只有 `created_by`。未定义：是否任何已登录用户都能建项目？创建者是否在同一事务成为 LEADER？`created_by` 与 LEADER 是否必须是同一人、之后能否分离？
为什么没覆盖：`PRD.md` §3 是项目内角色矩阵，天然无法表达"项目还不存在时"的权限；`DECISIONS.md` D004 只规定基数不规定建立时刻。
影响：D004 的"至少一个 LEADER 由 Service 事务保证"（`DECISIONS.md:47`）需要一个明确的建立时刻，否则第一条不变式没有起点。

**Q8 — `project` 无法读取 `user_account`（本文判定为最关键之一）。**
`ARCHITECTURE.md:57` 规定 `project` 只依赖 `common`，**不依赖 `auth`**；`ARCHITECTURE.md:75` + ArchUnit 禁止跨 feature 直接注入对方 `*Repository`。但"添加成员"必须把某个用户解析成 `user_id`，"成员列表"必须显示用户名——这两个都需要读 `user_account`（该表属于 `auth` 的领域）。文档没有给出任何合法通道（`common` 里的用户查询 facade？Controller 层解析后只把 `userId` 传下去？仅靠 DB FK 失败反推？）。
为什么没覆盖：§1.3 的依赖规则是围绕"业务模块不依赖 auth"写的（`ARCHITECTURE.md:64`），而该句只解决了**写入方向**（Controller 取 userId 传参），没解决**读取方向**（按用户名查用户、列出可加成员）。
影响：选错方案会直接触发 ArchUnit 失败或迫使新增顶层包（后者被明令禁止，`ARCHITECTURE.md:30`）。

**Q9 — "至少一个 LEADER"的并发保护手段（本文判定为最关键之一）。**
`DECISIONS.md:47` 只说"Service 事务保证至少一个"，没说怎么保证。对比之下，文档对 Review Decision 的一次性写入给出了极具体的机制（`SELECT … FOR UPDATE` 锁 `pull_request` 行 + `WHERE decision='PENDING'` 条件更新 + 影响行数必须为 1，`ARCHITECTURE.md:273-281`），对 LEADER 不变式却没有等价规定。未定义：锁哪一行、隔离级别、两个并发请求同时降级/移除最后一个 LEADER 时的预期结果与错误码。
为什么没覆盖：D004 关注的是领域基数本身，机制细节在 R2.3 只对 Decision 这一条做了硬化。

**Q10 — LEADER 转移的协议。**
Legacy `ProjectMemberService` 的价值明写包含"转移"（`LEGACY-MIGRATION-MATRIX.md:22`，判定 REWRITE = 规则保留），但 V2 文档没有定义转移是"一次请求同事务改两行"还是"先升后降"，也没定义原 LEADER 转移后的新角色（DEVELOPER？REVIEWER？移除？）。
注意：C2 是部分唯一索引，"先升后降"的中间态会违反"至多一个"。

**Q11 — 成员移除的语义与被引用时的 FK 行为。**
`ARCHITECTURE.md:219`（「成员退出后活权限必须失效」）与 `DECISIONS.md:107`（「成员退出后映射由列级 `ON DELETE SET NULL` 清空」）**暗示**成员退出 = 删除 `project_member` 行（否则 `ON DELETE` 不会触发）。但 `ARCHITECTURE.md:138-139` 的 `requirement.assignee` 与 `:175` 的 `finding.assignee_id` **没有写任何 `ON DELETE` 子句**，PostgreSQL 默认 `NO ACTION` 会直接**阻止**删除仍被指派的成员。文档既没说这两条 FK 用什么删除行为，也没说"成员已退出但仍是需求负责人"时的产品行为。
为什么没覆盖：§2.3 只对 `pull_request.author_user_id` 明写了列级 `ON DELETE SET NULL`（因为它同时是 PG 15 硬依赖的来源），其余引用没有逐条给删除策略。
影响：Phase 2 的"移除成员"用例必须先回答它，Phase 3 的 `requirement.assignee` FK 才能落地。

**Q12 — `project.status` 的取值集合与转换规则。**
`ARCHITECTURE.md:87` 只给列名；`ARCHITECTURE.md:243` 规定枚举存 `varchar` + `CHECK`，但没有任何取值。是否存在归档/停用？项目删除是硬删除还是状态？
关联：`ProjectCleanupService` 是 DROP，理由是「改用 FK / 领域内删除」（`LEGACY-MIGRATION-MATRIX.md:23`），但"领域内删除"的具体形态未定义。

**Q13 — `project.name` 是否唯一、命名规则与长度。** `ARCHITECTURE.md:87` 只列了 `name`。

**Q14 — `username` 的规范化与大小写敏感性。**
`ARCHITECTURE.md:86` 只写 unique。未定义：是否大小写不敏感（需要 `lower()` 唯一索引或 `citext`）、长度、允许字符、是否为邮箱。与防枚举（`LEGACY-MIGRATION-MATRIX.md:15`）耦合：大小写处理不当会制造账户存在性的旁路。

**Q15 — `user_account.enabled` 的语义。** 谁能禁用账户（没有全局管理员角色，见 Q6）、禁用后既有会话是否立即失效（与 Q3 耦合）、被禁用用户是否仍出现在成员列表。

### 接口与契约

**Q16 — auth 相关的 REST 路径。**
`ARCHITECTURE.md:245` 只规定了项目内资源的路径形态 `/api/projects/{projectId}/...`。登录、登出、当前用户、CSRF token 获取端点的路径未定义。

**Q17 — 前端登录路由与未登录重定向。**
`ARCHITECTURE.md:407-415` 的路由清单里**没有** `/login`，而 `IMPLEMENTATION-PLAN.md:44` 要求 Phase 2 交付"登录、项目列表、成员管理"界面；一级导航被限定为三个（`ARCHITECTURE.md:405`）。登录页的路由归属、未登录访问受保护路由的行为均未定义。

**Q18 — 401/403/404 的区分与统一错误体的 `code` 取值。**
`ARCHITECTURE.md:246` 定义了响应形状 `{code, message, traceId}` 并禁止复用 Legacy error code；`ARCHITECTURE.md:133` 只规定"约束冲突统一映射为 409/422"。未认证、无角色权限、跨项目不可见分别返回什么状态码没有定义——而 `PRD.md:63` 说的是"不可见、不可操作"，若跨项目一律返回 403 会泄漏资源存在性，与"不可见"的字面含义存在张力。
关联：`.trellis/spec/backend/error-handling.md` 当前如实标注为空，由引入 `common.web` 的 Phase 填写（`error-handling.md:22-26`）——即 Phase 2。

**Q19 — Phase 2 阶段 `scm_identity_verified_at` 的含义。**
`DECISIONS.md:107` 说身份「由 LEADER **通过 SCM API** 配置」，但 SCM provider client 到 Phase 5 才存在（`IMPLEMENTATION-PLAN.md:60-65`）。未定义：Phase 2 是否允许 LEADER 手工录入 external id？该列在 Phase 2 是否恒为 NULL？未验证的身份是否可用于任何判定（Phase 5 之前无判定场景）？

**Q20 — `scm_external_user_id` 的列类型与 provider 归属。**
文档未定义该列是字符串还是数字。更实质的问题：provider 归属在 `scm_repository`（`ARCHITECTURE.md:95`），而成员身份可能在项目还没有仓库时就被配置——那么这个 external id 属于哪个 provider、换 provider 时如何处理，文档没写（仓库稳定身份"有 PR 后冻结"只约束了仓库侧，`DECISIONS.md:109`）。

**Q21 — `scm_*` 三列的可空性与唯一键的 NULL 语义。**
文档未声明三列可空。若可空（成员尚未配置身份的常态），PostgreSQL 默认 `UNIQUE` 是 NULLS DISTINCT，允许多个未配置成员共存；而文档在别处**显式**使用了 `UNIQUE NULLS NOT DISTINCT`（仅限 `review`，`ARCHITECTURE.md:98`、`DECISIONS.md:113`），却没有为 `project_member` 指定采用哪一种。断言 A12（`scm_username` 无唯一约束）也依赖"§2.1 已逐条列出该表全部唯一键"这一假设，文档未声明该列表是穷举的。

**Q22 — Phase 2 迁移文件编号（这是文档间措辞冲突，不是空白）。**
`ARCHITECTURE.md:244` 写「Flyway `V<n>__<snake_case>.sql`，**V1 为唯一初始化脚本**」；而 `IMPLEMENTATION-PLAN.md:27` 说业务表随各 Phase 增加、首个可发布版本前再 squash 为干净初始化迁移，`database-guidelines.md:37-39` 则给出硬规则「迁移只增不改，绝不编辑或重编号已应用的迁移」。
实践上仓库约定已经回答了（新增 `V2__*.sql`），但 `ARCHITECTURE.md:244` 的措辞未同步。按 `IMPLEMENTATION-PLAN.md:13`，发现规则冲突应先停下更新文档，不由代码自行解释。

**Q23 — Phase 2 的审计落点是表还是日志。**
`LEGACY-MIGRATION-MATRIX.md:25` 要求 `SecurityAuditLogger` REWRITE 为 `common.audit`（"只保留必要审计"），`:15` 把"审计"列为 `AuthService` 的保留价值；但 `ARCHITECTURE.md:103` 明确**不建通用 `audit_event` 表**，16 表里也没有登录审计表或成员变更审计表。因此 Phase 2 的登录/成员变更审计只能落在日志——但文档没有明写这一点，且 `.trellis/spec/backend/logging-guidelines.md:3-11` 当前如实标注为空，规定由引入 `common.web` 的 Phase 填写。

### 次要但需一次性定下的

**Q24（并入 Q23）**：成员变更是否需要可查询的审计事实。若需要，就会撞上"不建通用 audit_event 表"（`ARCHITECTURE.md:103`）→ 新表 = 新决策。

**Q25 — 列表分页契约。** `ARCHITECTURE.md:20` 说 `common` 负责 paging，但没有定义分页参数与响应形状；项目列表与成员列表都需要。

**Q26 — 项目列表的可见性口径。** 「跨项目一律不可见」（`PRD.md:63`）**推断**项目列表只列出自己是成员的项目，但文档没有明写这一点（也没说 `created_by` 但已被移除成员的用户是否还能看到）。

> 编号说明：Q24 已并入 Q23，实际独立开放问题 **23 条**（Q1–Q23，另加 Q25、Q26；Q24 不单独计数）。

---

## 9. Caveats / Not Found

1. **`ARCHITECTURE.md` §2.1 的列清单不是完整 DDL**，第三列标题就是"关键约束"。本文对每一列都标了【明写】/【§2.4 推导】/【未定义】，不要把推导项当契约。
2. **Phase 2 的 UI 契约不在本文范围**。`IMPLEMENTATION-PLAN.md:44` 要求登录/项目列表/成员管理三屏；视觉、动效与设计漂移规范在 `.trellis/spec/frontend/`（`ARCHITECTURE.md:422`），本文只摘了与 CSRF/凭据相关的 `hook-guidelines.md:37,51-54`。
3. **本文未读取 Legacy 源码**。`LinYsssss/reposage` 是只读外部仓库，本地不存在；§6 的判定与"保留/丢弃"全部来自 `LEGACY-MIGRATION-MATRIX.md` 的表述本身，不是对 Legacy 代码的复核。
4. **未找到的内容（确认为空白，不是漏查）**：`docs/v2/**` 全文无密码哈希算法、无 session 存储介质、无 `session_version` 递增规则、无 CSRF 协议、无登录失效时长、无账户创建路径、无 `project.status` 取值、无 auth 端点路径。grep 关键词：`password|hash|bcrypt|argon|login|auth|session|csrf|cookie`。
5. **`user_account` 的模块归属是推断**：`ARCHITECTURE.md:21` 把"登录"归给 `auth`，`:86` 把该表描述为"本地演示账户"，但文档没有一句话明写"`user_account` 表由 `auth` 包拥有"。这个推断是 Q8 成立的前提；若实际归属为 `common`，Q8 的形态会变。
6. **Phase 3（Requirement/AC/Revision）不在本文范围**，尽管它与 Phase 2 同属批次 1（`DECISIONS.md:132`）。本文只在 FK 链、成员删除语义（Q11）与角色矩阵后 10 行处标注了 Phase 3 的耦合点。

## Related Specs

- `.trellis/spec/backend/index.md` — 后端 pre-development checklist 与 quality check 入口
- `.trellis/spec/backend/database-guidelines.md` — Flyway 权威、迁移只增不改、真实 PG 测试的硬约束
- `.trellis/spec/backend/quality-guidelines.md` — 构建闸门、五条 ArchUnit 规则与 counter-probe 要求、凭据与 Actuator 纪律
- `.trellis/spec/backend/directory-structure.md` — 八包扁平布局，禁止为对称建四层目录
- `.trellis/spec/backend/error-handling.md` / `logging-guidelines.md` — 当前为空，由引入 `common.web` 的 Phase（即 Phase 2）填写
- `.trellis/spec/frontend/hook-guidelines.md` — `requestJson` 单一请求边界与 CSRF token 的唯一注入点
- `.trellis/tasks/archive/2026-08/08-20-phase-1-foundation/result.md:223-231` — Phase 2 的五条前置条件
