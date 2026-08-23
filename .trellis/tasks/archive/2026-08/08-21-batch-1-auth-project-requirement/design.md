# 批次 1 技术设计

依据：`prd.md`、`docs/v2/ARCHITECTURE.md`、[D013](../../../../../docs/v2/DECISIONS.md#d013)，以及 `research/` 下三份研究（`auth-project-contracts.md`、`requirement-contracts.md`、`pg15-hibernate-constraints.md`）。本文只写实现形态，不复述权威文档已定义的事实。

## 1. 设计原则

- **数据库是隔离与完整性的执行者**，Service 不做数据库已经能拒绝的重复校验。跨项目写入靠复合外键，恰一 LEADER 靠部分唯一索引。
- **零新增结构**：不加表、不加列、不加顶层包、不加抽象层。所有开放问题按 [D013](../../../../../docs/v2/DECISIONS.md#d013) 已裁定的简单解落地。
- **先约束后代码**：迁移与实体映射先通过真实 PostgreSQL 集成测试，再写业务逻辑。
- 每个 feature 只建实际需要的类，不为目录对称造空分层（`ARCHITECTURE.md` §1.1）。

## 2. 数据库迁移

新增 `V2__auth_project.sql` 与 `V3__requirement.sql`。Flyway 只增不改；`V1__foundation.sql` 不动。

### 2.1 `V2__auth_project.sql`

三张表按 `ARCHITECTURE.md` §2.1 的列与约束建立。需要显式写出的关键项：

```sql
-- 至多一个 LEADER：PostgreSQL 中带 WHERE 的唯一性只能是唯一索引，不能写成表级约束
CREATE UNIQUE INDEX ux_project_member_single_leader
  ON project_member (project_id) WHERE role = 'LEADER';

-- 供项目内复合外键引用的唯一键
ALTER TABLE project        ADD CONSTRAINT uq_project_id            UNIQUE (id);
ALTER TABLE project_member ADD CONSTRAINT uq_project_member_pk_pair UNIQUE (project_id, user_id);
```

`project_member` 不需要 `UNIQUE(project_id,id)`——`ARCHITECTURE.md` §2.3 中所有指向它的引用目标都是 `(project_id, user_id)`。

枚举（`project_member.role`、`project.status`）在数据库存 `varchar` + `CHECK`，Java 侧用 enum（§2.4）。

### 2.2 `V3__requirement.sql`

```sql
-- requirement 的自引用复合外键：必须 MATCH SIMPLE，必须 NOT DEFERRABLE（D013.10）
ALTER TABLE requirement ADD CONSTRAINT fk_requirement_current_revision
  FOREIGN KEY (project_id, id, current_revision_id)
  REFERENCES requirement_revision (project_id, requirement_id, id);
```

实测依据（`research/pg15-hibernate-constraints.md` 第 2 项）：三步回填在非延迟下全程通过；回填别的需求或别的项目的 revision、删除在用 revision 均被 23503 拒绝；改 `MATCH FULL` 会直接报错使设计不可能。

其余唯一键按 §2.1 原文：`requirement.UNIQUE(project_id,id)`、`requirement_revision.(requirement_id,seq)`、`UNIQUE(project_id,id)`、`UNIQUE(project_id,requirement_id,id)`、`acceptance_criterion.(requirement_revision_id,ac_key)`、`UNIQUE(project_id,id)`、`UNIQUE(project_id,requirement_revision_id,id)`。

### 2.3 迁移纪律

- 不写 `ON DELETE`（§2.3 未规定的地方一律不自行添加）。成员移除与需求删除的语义见 §6.4。
- 不建向量索引、不绑模型维度（[D001](../../../../../docs/v2/DECISIONS.md#d001)）。
- 每条迁移都要有对应的集成测试断言其约束真的生效，而不是只断言"表存在"。

## 3. 实体映射形态（[D013.1](../../../../../docs/v2/DECISIONS.md#d013) 变体 A）

**全局统一**：含 `project_id` 的复合外键关联，`@JoinColumn` 全部 `insertable=false, updatable=false`，写入由标量 `Long xxxId` 承担。

```java
@Entity
class Requirement {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "current_revision_id")          private Long currentRevisionId;   // 写入走这里

    @ManyToOne(fetch = LAZY)
    @JoinColumns({
        @JoinColumn(name = "project_id",          referencedColumnName = "project_id",     insertable = false, updatable = false),
        @JoinColumn(name = "id",                  referencedColumnName = "requirement_id", insertable = false, updatable = false),
        @JoinColumn(name = "current_revision_id", referencedColumnName = "id",             insertable = false, updatable = false)
    })
    private RequirementRevision currentRevision;                                     // 只读导航
}
```

不这样写会在**启动期**失败，实测报 `AnnotationException: mix insertable with 'insertable=false'` 或 `MappingException: Column 'project_id' is duplicated in mapping`。因为每条项目内外键都带 `project_id`，该冲突几乎覆盖全部关联。

该约定必须同步写进 `.trellis/spec/backend/`，否则下一个写复合关联的人会再撞一次。

## 4. 包与类布局

```text
auth/         AuthController · AuthService · UserAccount · UserAccountRepository
              UserDirectory（只读 Query facade：byId / byUsername → id, username, enabled）
              SecurityConfig · CurrentUser（从 SecurityContext 取 userId）

project/      ProjectController · ProjectService · Project · ProjectRepository
              ProjectMemberController · ProjectMemberService · ProjectMember · ProjectMemberRepository
              ProjectRole · ProjectAccessService（唯一的项目内授权入口）

requirement/  RequirementController · RequirementService · Requirement · RequirementRepository
              RequirementRevision · RequirementRevisionRepository
              AcceptanceCriterion · AcceptanceCriterionRepository
              RequirementStatus
```

- 不建 `domain/application/infrastructure/web` 四层目录。
- `project` 与 `requirement` 通过 `UserDirectory` 读取账户展示信息（[D013.6](../../../../../docs/v2/DECISIONS.md#d013)），不注入 `UserAccountRepository`——后者会触发 ArchUnit 规则 4。
- `requirement` 经 `ProjectAccessService` 做授权判定，不自行查 `ProjectMemberRepository`。

## 5. 授权模型

`ProjectAccessService` 是唯一入口，暴露形如 `requireRole(projectId, userId, ProjectRole...)` 的方法，返回成员身份或抛出。约定：

- Controller 从登录上下文取 `userId`（`ARCHITECTURE.md` §1.3），作为参数传入 Service；业务 Service 不接触 Spring Security。
- 具体形态：`project` / `requirement` 的 Controller 接收 JDK 的 `java.security.Principal`，再经 `auth` 的只读 `UserDirectory.byUsername` 换成 `userId`。这样业务模块既不 import Spring Security，也不依赖 `auth` 的认证机制，只用到 [D013.6](../../../../../docs/v2/DECISIONS.md#d013) 明确放行的只读 facade。代价是每个请求多一次按唯一索引的账户查询，MVP 接受。
- 项目角色**不进入** Spring Security 的全局权限体系——角色是项目内概念，全局权限只区分「已认证/未认证」。
- **不存在的资源与无权访问的资源返回同一种结果**，避免通过状态码差异探测跨项目资源是否存在。

## 6. 关键流程

### 6.1 创建项目（[D013.5](../../../../../docs/v2/DECISIONS.md#d013)）

单事务：插 `project`（`created_by = 当前用户`）→ 插 `project_member(role=LEADER)`。`created_by` 与 LEADER 是两件事，LEADER 可转移而 `created_by` 不变。

### 6.2 LEADER 转移（[D013.8](../../../../../docs/v2/DECISIONS.md#d013)）

单事务，顺序不可换：把原 LEADER 降级 → **flush** → 把目标成员升级。

禁止写成单条 `UPDATE ... CASE`：实测其成败依赖物理扫描顺序，同一条 SQL 可能成功也可能 23505。并发转移由 `project` 行锁串行化，失败者按 409 返回。

### 6.3 创建需求（[D013.10](../../../../../docs/v2/DECISIONS.md#d013)）

单事务三步：插 `requirement`（`current_revision_id = NULL`，此时复合外键因 `MATCH SIMPLE` 跳过校验）→ 插 `requirement_revision(seq=1)` 与其 AC → 回填 `current_revision_id`（三列全非空，此刻才真正校验）。

Hibernate 的 flush 顺序（INSERT 先于 UPDATE）天然产出该顺序，实测 bind 日志确认第一步写入的是 `null`。

### 6.4 状态转换

| 转换 | 触发者 | 同事务副作用 |
|---|---|---|
| 创建 | LEADER | 建 Revision 1 + 其 AC |
| DRAFT 内编辑 | LEADER | 原地改 Revision 1；清空 `quality_json/quality_version/quality_checked_at` |
| DRAFT → READY | LEADER | 冻结 Revision 1 |
| READY → IN_DEVELOPMENT | LEADER | **与首次指派同事务**；后续换人不再改状态 |
| READY 后修改正文/AC | LEADER | 发布新 Revision（`seq+1`、`change_reason` 必填）并回填 `current_revision_id` |
| → DONE | LEADER | 无 |
| 任意非终态 → CANCELED | LEADER | 无；终态不可恢复 |

`ac_key` 由创建 AC 时生成并在后续 Revision 中原样继承；`sort_order` 变化不影响 `ac_key`。发布新 Revision 时逐条复制 AC 并保留各自 `ac_key`。

删除语义：本批次**不提供**项目、成员、需求的硬删除接口。外键未声明 `ON DELETE`，硬删会被数据库挡住；成员移出项目的能力若需要，须先回答 `requirement.assignee` 的处置，属批次 2 之前的开放项，本批次不实现。

## 7. 认证与会话（[D013.7](../../../../../docs/v2/DECISIONS.md#d013)）

- Spring Security 表单登录 + 服务端进程内 `HttpSession`；BCrypt `PasswordEncoder`。
- CSRF 用 Spring Security 的 cookie token repository；所有写请求校验。
- `session_version` 仅在改密码与「强制全端登出」时递增；过滤器比对会话中捕获的版本与数据库当前值，不一致即失效。
- 登录失败不区分「用户不存在」与「密码错误」，防账户枚举（Legacy `AuthService` 的 REWRITE 要点）。
- 不新增 session 表、不引入 Redis。**进程重启会话失效**，写入部署说明。

## 8. API 形态

项目内资源一律带 `projectId` 段（§2.4）。**请求体、响应体、校验规则与状态机入口的完整契约见
[`api-contract.md`](./api-contract.md)**，前后端共用同一份，避免各写各的形状：

```text
POST   /api/auth/register           POST /api/auth/login         POST /api/auth/logout
GET    /api/auth/me                 POST /api/auth/password
GET    /api/projects                POST /api/projects
GET    /api/projects/{projectId}
GET    /api/projects/{projectId}/members
POST   /api/projects/{projectId}/members
PATCH  /api/projects/{projectId}/members/{userId}          # 角色、SCM 身份
GET    /api/projects/{projectId}/requirements
POST   /api/projects/{projectId}/requirements
GET    /api/projects/{projectId}/requirements/{id}
PATCH  /api/projects/{projectId}/requirements/{id}         # DRAFT 原地编辑
POST   /api/projects/{projectId}/requirements/{id}/revisions   # READY 后发布新版本
POST   /api/projects/{projectId}/requirements/{id}/status      # READY / DONE / CANCELED
POST   /api/projects/{projectId}/requirements/{id}/assignee
GET    /api/projects/{projectId}/requirements/{id}/revisions
```

`register` 与 `password` 是规划期补入的：AC2 要求「可注册或由种子数据获得账户」并要求「改密码后其它会话失效」，
而 `.trellis/spec/backend/database-guidelines.md` 不允许迁移里放种子行，注册接口是不新增结构的那个解。

错误响应用 `common` 的统一 `{code, message, traceId}`；约束冲突映射 409/422（[D013.11](../../../../../docs/v2/DECISIONS.md#d013)），**不捕获后在同一事务内继续**——实测约束触发器错误会使事务进入 25P02，savepoint 也救不回来。

## 9. 前端

在既有路由外壳内填充，不新增一级菜单：

```text
/login                        登录
/projects                     项目列表 + 创建
/projects/:id/members         成员管理（角色、SCM 身份）
/requirements                 需求列表（按当前项目过滤）
/requirements/:id             需求详情 + AC + 版本历史
```

- 沿用 `.trellis/spec/frontend/` 的设计令牌、组件与动效契约；不引入 Pinia/Axios/UI 大库。
- `requirement` 表无 `title` 列，列表必须 JOIN `current_revision_id` 取标题——后端直接返回组装好的视图对象，前端不做二次拼接。
- 需求状态与评审活动分开呈现；本批次评审活动恒为 `NO_PR`，按只读派生量渲染，不落表、不可编辑。
- 登录态失效时统一跳转登录页；CSRF token 由请求层附加（`http.ts` 已预留注入点）。

## 10. 测试策略

只测本批次改变的行为与高风险契约，不搬运 Legacy 全套测试。

**必须有的集成测试**（真实 PostgreSQL 15 Testcontainers）：

1. 跨项目猜 id：project / requirement / revision / member 四类，读写各一条。
2. 角色越权：DEVELOPER / REVIEWER 触碰 LEADER 专属操作。
3. 恰一 LEADER：插第二个 LEADER 被拒；转移后仍恰一个；并发转移只有一个成功。
4. SCM 身份唯一：`(project_id, scm_external_user_id)` 冲突被拒。
5. 三步回填：正常路径成功；指向别的需求/别的项目的 revision 被 23503 拒绝。
6. Revision 冻结：READY 后原地改被拒；新 Revision 保留旧 AC 与其 `ac_key`。
7. `quality_json` 清空：DRAFT 编辑后归空（需在夹具中先 seed 非空值，否则该语义测不到）。
8. 状态机非法转换逐条被拒。

**单元测试**：`ac_key` 生成与继承、状态机转换表、`ProjectAccessService` 判定矩阵。

**ArchUnit**：现有五条 + 新增两条（子包白名单、Repository 不只按类名识别），新增两条各配反证 fixture。

**前端**：路由守卫、表单校验与错误呈现、键盘可达、reduced-motion 不回归。

**不做**：为简单 getter、样板 CRUD、框架成熟行为新增测试。

## 11. 安全边界

- 密码只存 BCrypt 哈希，任何响应与日志都不回显。
- 跨项目一律不可见：不存在与无权限返回同一结果。
- `scm_external_user_id` 是权限依据，`scm_username` 仅显示，任何授权判定都不读后者（[D010](../../../../../docs/v2/DECISIONS.md#d010)）。
- 输入校验覆盖长度、非法 UTF-8 与 NUL；错误信息不回显内部标识。

## 12. 回滚

- 迁移只增不改，回滚以「丢弃本批次提交」为单位，不写 down migration。
- 后端、前端可按文件组独立回滚；数据库回滚等价于重建空库（本批次尚无生产数据）。
- 若 [D013](../../../../../docs/v2/DECISIONS.md#d013) 的任一裁定在真实实体上不成立，**停止实现并回到决策**，不得在代码里加兼容分支或降级为 Service 校验绕过——这正是 [D006](../../../../../docs/v2/DECISIONS.md#d006) 明确禁止的行为。
