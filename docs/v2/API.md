# 账户、成员目录与 SCM 身份 API

本文记录 D020 新增或替换的当前 HTTP 契约。所有写请求继续使用 Session Cookie + `X-XSRF-TOKEN`；错误体统一为 `{code,message,traceId}`。项目内资源对非成员返回 404，对已知项目但角色不足返回 403。

## 账户

- `POST /api/auth/register`
  - 请求：`{username, displayName, password}`
  - 响应 201：`{id, username, displayName}`
- `GET /api/auth/me`、表单登录成功响应
  - 响应：`{id, username, displayName}`
- `PATCH /api/auth/profile`
  - 请求：`{displayName}`
  - 响应：更新后的账户；刷新后仍从数据库返回新显示名。

用户名是登录标识且唯一；显示名用于识别人，可重复；`id` 是 ForgePilot 平台 ID。

## 项目与成员

项目响应把旧 `myRole` 替换为 `myRoles: ProjectRole[]`。成员响应为：

```json
{"userId":12,"username":"lin","displayName":"林工","roles":["DEVELOPER","REVIEWER"]}
```

- `GET /api/projects/{projectId}/members`
  - 所有成员可读。
- `GET /api/projects/{projectId}/members/candidates?q={query}&page=0&size=20`
  - 仅 LEADER；按显示名、用户名模糊搜索，或按平台 ID 精确搜索。
  - 返回 `{userId,username,displayName,enabled,alreadyMember}[]`。
- `POST /api/projects/{projectId}/members/batch`
  - 仅 LEADER；最多 50 行，整批原子提交。
  - 请求：`{"members":[{"userId":12,"roles":["DEVELOPER","REVIEWER"]}]}`。
  - 添加时不能授予 LEADER；任一账户无效、重复、已是成员或角色为空时整批 422。
- `PATCH /api/projects/{projectId}/members/{userId}/roles`
  - 请求：`{"roles":["DEVELOPER","REVIEWER"]}`。
  - 不能用此接口授予或移除 LEADER。
- `POST /api/projects/{projectId}/members/leader-transfer`
  - 请求：`{"targetUserId":12,"confirmed":true}`；成功 204。

## 用户 SCM 身份

- `GET /api/scm/identities`：列出当前用户的全部身份及状态。
- `POST /api/scm/identities/verify`
  - 请求：`{provider,apiBase,oneTimeToken,label,usageType}`。
  - `usageType`：`WORK | PERSONAL | CLIENT | OTHER`。
  - 服务端用 Token 调 Provider 当前用户接口，保存稳定外部 ID 和当前用户名；Token 不保存、不响应。
- `PATCH /api/scm/identities/{identityId}`
  - 请求：`{label,usageType}`。
- `DELETE /api/scm/identities/{identityId}`
  - 撤销身份及其活动/待审项目绑定；成功 204。

GitHub 默认 `apiBase=https://api.github.com`；GitLab 默认 `https://gitlab.com/api/v4`。自建实例使用其实际 API Base。

## 项目 SCM 身份绑定

- `GET /api/projects/{projectId}/scm/binding-options`
  - 当前成员自己的、已验证且与项目仓库 Provider/实例兼容的身份。
- `GET /api/projects/{projectId}/scm/bindings`
  - 普通成员只看到自己的历史；LEADER 看到项目全部成员绑定。
- `POST /api/projects/{projectId}/scm/bindings`
  - 请求：`{identityId,oneTimeToken}`。
  - 只能绑定当前用户自己的身份；Token 再次验证当前远端用户和仓库访问级别。
  - 默认响应状态 `ACTIVE`；严格项目响应 `PENDING_APPROVAL`。
- `POST /api/projects/{projectId}/scm/bindings/{bindingId}/approve|reject`
  - 仅 LEADER 审批待审绑定；成功 204。
- `POST /api/projects/{projectId}/scm/bindings/{bindingId}/revoke`
  - 仅绑定本人撤销活动或待审绑定；成功 204。

绑定响应包含身份标签、用途、外部用户名/ID、`status`、`accessLevel`、核验与审批时间，不包含 Token。

## 仓库严格模式

`PATCH /api/projects/{projectId}/scm/repositories/{repositoryId}` 增加可选字段：

```json
{"identityApprovalRequired":true}
```

它只影响之后提交的新绑定；已有活动绑定不会因开关变化被自动撤销。仓库安全响应增加同名布尔字段，仍不返回仓库 Token 或 Webhook Secret。

## PR 作者映射

远端 PR 保存的 `authorExternalUserId/authorUsername` 是不可变快照。`authorUserId` 是可重算投影：只有 Provider、实例、稳定外部用户 ID 与当前活动绑定一致时才有值。撤销、替换或审批绑定会重算项目内既有 PR；任何“本人 PR”授权均按稳定 ID 判断，不按用户名。
