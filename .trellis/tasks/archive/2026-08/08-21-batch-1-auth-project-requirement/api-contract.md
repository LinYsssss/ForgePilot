# 批次 1 API 契约

前后端共用的唯一契约。后端按本文实现，前端按本文调用；任何一方要改形状，先改本文。
路径与错误体的权威来源是 `docs/v2/ARCHITECTURE.md` §2.4 与 `design.md` §5/§8。

## 0. 通用

- 所有响应体为 JSON；错误体一律 `{ "code": string, "message": string, "traceId": string }`。
- 状态码：`401` 未登录；`403` CSRF 缺失/错误，或已是项目成员但角色不够；`404` 资源不存在**或**调用者不是该项目成员
  （两者必须无法区分）；`409` 与资源当前状态冲突；`422` 请求体不合法或领域拒绝；`204` 无响应体。
- **409 与 422 的分界**：只看请求体就能判定的拒绝是 `422`（长度、空值、缺 `changeReason`、引用了不存在的 `acKey`、
  非法状态转换）；必须读到资源当前状态才能判定的拒绝是 `409`（对已冻结需求原地编辑、对 `DRAFT` 发新版本、
  对终态指派、并发 LEADER 转移的失败方、唯一键冲突）。前端两者都按「操作被拒绝，展示 message」处理。
- 会话是服务端进程内 `HttpSession`（[D013.7](../../../docs/v2/DECISIONS.md#d013)），进程重启即失效。
- CSRF：cookie token repository。写请求（POST/PATCH/PUT/DELETE）必须带 `X-XSRF-TOKEN` 请求头，
  值取自 `XSRF-TOKEN` cookie。`GET /api/auth/me` 必须下发该 cookie，供前端冷启动引导。
  登录会重签 token，登录响应里的新 cookie 要覆盖旧值。
- 口令与哈希**永不**出现在任何响应体或日志中。

## 1. auth

| 方法 | 路径 | 请求 | 成功 | 失败 |
|---|---|---|---|---|
| POST | `/api/auth/register` | `{username, password}` | `201 {id, username}` | 用户名已存在 `409` |
| POST | `/api/auth/login` | 表单 `username`、`password`（`application/x-www-form-urlencoded`） | `200 {id, username}` | `401`，**用户不存在与口令错误返回完全相同的体** |
| POST | `/api/auth/logout` | 空 | `204` | — |
| GET | `/api/auth/me` | — | `200 {id, username}` | `401` |
| POST | `/api/auth/password` | `{currentPassword, newPassword}` | `204`，当前会话仍可用，其它会话立即失效 | 当前口令错误 `422` |

`username` 1–64 字符，`password` 至少 8 字符。登录用 Spring Security 表单登录机制（`loginProcessingUrl`），
成功/失败处理器返回上表的 JSON，不重定向。

## 2. project

| 方法 | 路径 | 请求 | 成功 | 权限 |
|---|---|---|---|---|
| GET | `/api/projects` | — | `200 [Project]`，只含当前用户为成员的项目 | 任何登录用户 |
| POST | `/api/projects` | `{name}` | `201 Project` | 任何登录用户，同事务成为 LEADER |
| GET | `/api/projects/{projectId}` | — | `200 Project` | 该项目成员 |
| GET | `/api/projects/{projectId}/members` | — | `200 [Member]` | 该项目成员 |
| POST | `/api/projects/{projectId}/members` | `{username, role}` | `201 Member` | 仅 LEADER |
| PATCH | `/api/projects/{projectId}/members/{userId}` | `{role?, scmExternalUserId?, scmUsername?}` | `200 Member` | 仅 LEADER |

```jsonc
Project = { "id": 1, "name": "…", "status": "ACTIVE", "createdAt": "…", "myRole": "LEADER" }
Member  = { "userId": 7, "username": "…", "role": "DEVELOPER",
            "scmExternalUserId": null, "scmUsername": null, "scmIdentityVerifiedAt": null }
```

- `name` 1–120 字符；`role` ∈ `LEADER|DEVELOPER|REVIEWER`。
- `PATCH` 把 `role` 改为 `LEADER` 即 LEADER 转移：同事务「原 LEADER 降级为 `DEVELOPER` → flush → 目标升级」
  （[D013.8](../../../docs/v2/DECISIONS.md#d013)），并对 `project` 行加锁串行化；失败者 `409`。
- 不允许把唯一的 LEADER 降级（[D013.9](../../../docs/v2/DECISIONS.md#d013) 的每次提交后不变式）→ `422`。
  想换人就直接把目标成员改成 `LEADER`，转移是一个动作而不是两个。
- 直接以 `role=LEADER` 新增成员会被数据库部分唯一索引拒绝 → `409`；先加成员再转移。
- `scmExternalUserId` 与 `scmUsername` 同时给出；同项目重复的 `scmExternalUserId` → `409`。
- 本批次不提供成员移除接口（`design.md` §6.4）。

## 3. requirement

| 方法 | 路径 | 请求 | 成功 | 权限 |
|---|---|---|---|---|
| GET | `/api/projects/{projectId}/requirements` | — | `200 [RequirementSummary]` | 该项目成员 |
| POST | `/api/projects/{projectId}/requirements` | `CreateRequirement` | `201 RequirementDetail` | 仅 LEADER |
| GET | `/api/projects/{projectId}/requirements/{id}` | — | `200 RequirementDetail` | 该项目成员 |
| PATCH | `/api/projects/{projectId}/requirements/{id}` | `EditDraft` | `200 RequirementDetail` | 仅 LEADER，仅 `DRAFT` |
| POST | `/api/projects/{projectId}/requirements/{id}/revisions` | `PublishRevision` | `201 RequirementDetail` | 仅 LEADER，仅非 `DRAFT` 非终态 |
| GET | `/api/projects/{projectId}/requirements/{id}/revisions` | — | `200 [Revision]`，按 `seq` 升序，含各自 AC | 该项目成员 |
| POST | `/api/projects/{projectId}/requirements/{id}/status` | `{status}` | `200 RequirementDetail` | 仅 LEADER |
| POST | `/api/projects/{projectId}/requirements/{id}/assignee` | `{userId}` | `200 RequirementDetail` | 仅 LEADER |

```jsonc
RequirementSummary = { "id": 12, "title": "…", "status": "READY", "assigneeId": null,
                       "assigneeUsername": null, "currentRevisionSeq": 2,
                       "updatedAt": "…", "reviewActivity": "NO_PR" }

RequirementDetail  = { "id": 12, "status": "READY", "assigneeId": null, "assigneeUsername": null,
                       "createdAt": "…", "updatedAt": "…", "reviewActivity": "NO_PR",
                       "currentRevision": Revision }

Revision = { "id": 30, "seq": 2, "title": "…", "background": null, "description": null,
             "createdBy": 3, "createdByUsername": "…", "changeReason": "…", "createdAt": "…",
             "acceptanceCriteria": [ { "id": 91, "acKey": "AC-1", "sortOrder": 1, "text": "…" } ] }

CreateRequirement = { "title": "…", "background": null, "description": null,
                      "acceptanceCriteria": [ { "text": "…" } ] }
EditDraft         = { "title": "…", "background": null, "description": null,
                      "acceptanceCriteria": [ { "acKey": "AC-1", "text": "…" } ] }   // acKey 可空 = 新增
PublishRevision   = EditDraft + { "changeReason": "…" }                              // changeReason 必填
```

- `title` 1–200 字符；`acceptanceCriteria` 至少一条，每条 `text` 非空。
- `sortOrder` 由数组下标（从 1 开始）派生，**不接受客户端传入**；`acKey` 不随顺序变化。
- `acKey` 由服务端生成，形如 `AC-<n>`；`<n>` = 该需求**全部 Revision** 中已用过的最大编号 + 1，
  已退休的编号不重用。带 `acKey` 的条目沿用原值，未带的分配新值。
- 请求体给出的 `acKey` 若不属于本需求 → `422`。
- `PATCH`（`DRAFT` 原地编辑）同事务清空 `quality_json/quality_version/quality_checked_at`。
- `reviewActivity` 本批次恒为字符串 `"NO_PR"`，是只读派生量，不落表、不可写。

### 状态机（`design.md` §6.4，[D013.4](../../../docs/v2/DECISIONS.md#d013)）

| 起点 | 允许 | 方式 |
|---|---|---|
| `DRAFT` | `READY`、`CANCELED` | `POST /status` |
| `READY` | `IN_DEVELOPMENT` | **仅**由首次 `POST /assignee` 同事务触发 |
| `READY` | `CANCELED` | `POST /status` |
| `IN_DEVELOPMENT` | `DONE`、`CANCELED` | `POST /status` |
| `DONE` / `CANCELED` | 无 | 终态，任何转换 `422` |

- `POST /status` 直接传 `IN_DEVELOPMENT` → `422`（唯一入口是首次指派）。
- **指派只在 `READY` 与 `IN_DEVELOPMENT` 两个状态开放**，其余状态 `409`。否则「先给 `DRAFT` 指派、再置 `READY`」
  会让 `IN_DEVELOPMENT` 只能靠"换人"才进得去，与「首次指派同事务进入」自相矛盾。有了这条守卫，
  `READY` 的需求必然还没有 assignee，`status == READY` 本身就等价于「这是首次指派」。
- 已是 `IN_DEVELOPMENT` 时换人不改状态。
- `assignee` 必须是本项目成员，否则数据库复合外键拒绝（`23503` → `409`）。
