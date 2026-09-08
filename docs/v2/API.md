# 账户、成员、SCM 身份与评审协作 API

本文定义账户、成员目录、SCM 身份及评审协作的 HTTP 契约。所有写请求使用 Session Cookie + `X-XSRF-TOKEN`；错误体统一为 `{code,message,traceId}`。项目内资源对非成员返回 404，对已知项目但角色不足返回 403。

登录、注册与两个 webhook 端点另有按客户端地址计的限流，超出配额返回 **429** 与 `{"code":"too_many_requests"}`；该响应由过滤器直接写出，不经 MVC。配额见 `.env.example` 的三个 `FORGEPILOT_*_PER_*` 变量。

下文列出本轮变更涉及的需求、知识与审查端点；其余端点的行为见 [ARCHITECTURE.md](./ARCHITECTURE.md) 与对应 `*Controller`。

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

- `POST /api/projects/{projectId}/archive`
  - 仅 LEADER；成功 204。已归档时返回 409。归档只改 `status`，项目内数据一律保留。
- `POST /api/projects/{projectId}/unarchive`
  - 仅 LEADER；成功 204。未归档时返回 409。
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
- `DELETE /api/projects/{projectId}/members/{userId}`
  - 仅 LEADER；成功 204。硬删成员关系，并在同一事务里撤销角色集合、需求开发/审查人指派、Finding 认领与本项目 SCM 绑定。
  - 唯一 LEADER 返回 409（先做负责人转移）；重复移除返回 404；跨项目与不存在同答 404。
  - `pull_request` 的两列不可变作者快照、`pull_request_requirement_event`、Finding 血缘与审计不受影响；`author_user_id` 按预设置空。用户自有 `scm_identity` 与平台账号不受影响。

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

## 项目钉钉通知

以下端点均仅限项目 LEADER。Webhook URL 与加签密钥只写不读，任何响应都不会回显它们。

业务通知在事务提交后尽力发送：AI 完成→有效审查人，AI 失败→LEADER，退回→有效开发负责人，合并→LEADER；无有效处理人时回退 LEADER。消息包含处理人显示名，不包含决定理由。配置 `FORGEPILOT_BASE_URL`（Spring 属性 `forgepilot.base-url`）后链接为 `/reviews/{id}?project={projectId}`；留空时不生成链接。发送失败不影响已提交决定。

- `GET /api/projects/{projectId}/notifications/dingtalk`
  - 返回 `{configured,enabled,signed,keyword,updatedAt}`；未配置时为
    `{configured:false,enabled:false,signed:false,keyword:null,updatedAt:null}`。
- `PUT /api/projects/{projectId}/notifications/dingtalk`
  - 请求：`{webhookUrl,secret?,keyword?,enabled}`。
  - Webhook 必须以 `https://oapi.dingtalk.com/` 开头；`secret` 缺失或空白表示不加签，
    `enabled` 缺失时为 `false`。
- `DELETE /api/projects/{projectId}/notifications/dingtalk`
  - 删除该项目的钉钉通知配置；成功 204。
- `POST /api/projects/{projectId}/notifications/dingtalk/test`
  - 使用已启用的已存凭据发送固定测试消息，返回 `{sent:boolean}`。
  - 不创建 Review、不调用 AI；配置了自定义关键词时，测试消息自动包含该关键词。
  - 未配置或已停用渠道返回 409。

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

## 需求审查人

- `POST /api/projects/{projectId}/requirements/{requirementId}/reviewer`
  - 请求：`{"userId":12}`；`{"userId":null}` 清空指派。
  - 仅 LEADER；目标须为本项目 REVIEWER/LEADER。`DONE` / `CANCELED` 返回 409。
  - 成功 200，返回更新后的 `RequirementDetail`；不改变生命周期或创建 Revision。
- 需求列表与详情新增 `reviewerId: number|null`、`reviewerName: string|null`。
  - 名称仅在该成员仍有 REVIEWER/LEADER 资格时返回；因此保留历史引用时 `reviewerId` 有值但 `reviewerName` 可以为 null。
  - 未指定或失效由 LEADER 接手；移除成员时引用置空。

开发指派继续使用 `POST .../requirements/{id}/assignee` 和非空 `userId`，服务端校验目标必须是本项目 DEVELOPER/LEADER：无资格成员返回 403，非成员返回 404，失败不改变原指派或需求状态。两类指派各自独立。

## Review 最终决定与历史

- `POST /api/projects/{projectId}/reviews/{reviewId}/decision`
  - 请求：`{"decision":"REQUEST_CHANGES","comment":"请补齐权限校验"}`，或 `{"decision":"APPROVE","comment":null}`。
  - 成功 200：`{decision,decisionBy,decisionAt}`；详情保留 `decisionComment`。
  - 仅指定且仍有效的 REVIEWER 或 LEADER；未关联需求、未指定审查人时仅 LEADER。
  - 关联需求须处于 `IN_DEVELOPMENT` 且开发负责人仍有 DEVELOPER/LEADER 资格。Review 必须已完成、当前有效、未决定，且同 head 无既有退回。
  - 新退回理由去除首尾空白后必须非空；任意决定备注最多 2000 字。历史空备注仍能读取。
  - `REQUEST_CHANGES` 不调用远端写接口，保留 PR/MR 与分支。`APPROVE` 合并被审查的 SHA，Provider 确认合并后才提交本地决定；不会把需求改为 `DONE`。
- `GET /api/projects/{projectId}/reviews/{reviewId}`
  - 新增 `decisionBlockReason: string|null`，仅表示调用人权限和需求状态限制。
  - 前端仅在该字段显式为 null，且 `isCurrent=true`、`status=COMPLETED`、`decision=PENDING`、无 head 退回闸门时展示决定按钮。
- `GET /api/projects/{projectId}/pull-requests/{pullRequestId}/reviews`
  - 返回现有 `{id,headSha,requirementRevisionId,status,decision,isCurrent,createdAt}[]`，按创建时间、ID 从旧到新排序。
  - UI 从该序列推导轮次，按需读取上一轮详情中的退回理由，不增加轮次字段或历史表。
- 项目审查列表包含 `provider`、`pullRequestNumber`、`pullRequestTitle`；PR 详情包含 `title` 及原有作者映射字段，用于区分远端 PR/MR 与内部记录 ID。

| 情况 | HTTP / code | 处理 |
|---|---|---|
| 非成员、跨项目或资源不存在 | 404 / `not_found` | 不泄露资源存在性 |
| 非指定审查人、无相应角色；开发负责人失去所需角色 | 403 / `forbidden` | 由 LEADER 调整角色/指派 |
| 非终局 decision、空退回理由、备注超长 | 422 / `unprocessable` | 修改请求 |
| 需求状态不允许、Review 过期/未完成/已决定、同 head 已退回 | 409 / `conflict` | 纠正状态或更新 head 后重新审查 |
| 远端 head 变化、关闭、拒绝合并或响应明确未合并 | 409 / `conflict` | 检查冲突、保护分支与仓库 Token 权限；同步后重审 |
| 远端响应丢失/无法读取，且只读确认仍不能证明已合并同 SHA | 503 / `merge_outcome_unknown` | 本地决定回滚；人工核查远端，不能按失败自动重复写入 |

远端合并与数据库提交不是原子操作。已合并同 SHA 的人工重试可补上本地决定；这不是撤销/改判接口。GitLab 仓库 Token 需要 `api` 与实际合并权限，个人身份核验 Token 的只读要求不变。

## 公共知识原文

- `GET /api/projects/{projectId}/knowledge/documents/{documentId}/content`
  - 成功 200：`{documentId,title,text}`，返回现有存储文本。
- `GET /api/projects/{projectId}/knowledge/documents/{documentId}/download`
  - 成功 200：UTF-8 `text/plain`；`Content-Disposition: attachment` 使用文档标题作为文件名。

均允许所有项目成员读取，但文档必须属于当前项目且为 `PROJECT_KNOWLEDGE`。需求附件、跨项目、非成员、已删除或不存在均返回 404；附件必须经所属需求的 `attachments/{documentId}/content|download` 访问。知识列表仍只返回元数据，向量始终不返回。历史 Review 的证据摘录不依赖当前原文是否存在。

## 资源删除

三类资源三种策略；授权主体均为项目 LEADER，重复删除一律 404。

- `DELETE /api/projects/{projectId}/knowledge/documents/{documentId}`
  - 硬删。成功 204，同事务删除该文档的全部 `knowledge_chunk`（含向量），此后 Guidance 与 Review 的检索都不再召回它。
  - 文档是需求附件时返回 409，并指出是哪条需求在引用；请先在该需求下解除附件。
  - 批量上传**没有**批量端点：一次多文件上传就是对 `POST .../knowledge/documents` 的 N 次独立调用，逐文件成败互不影响。
- `DELETE /api/projects/{projectId}/members/{userId}`
  - 见上「项目与成员」。
- `DELETE /api/projects/{projectId}/requirements/{requirementId}`
  - **软删**。成功 204；需求从列表与详情消失，但行保留，因此 `ai_call_log` 与 `pull_request_requirement_event` 的审计与既成事实完好。
  - 仅 `CANCELED` 可删，其他状态返回 409；不额外要求「无 PR 关联且无 Finding」。
