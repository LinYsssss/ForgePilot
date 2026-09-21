# 审查待办修复：统一错误体、中文文案、注释与文档同步

## Goal

按 2026-09-21 全仓代码审查的待办清单做一批**最小改动**：把绕过统一错误体的框架异常收回契约、把用户可见的错误文案统一为中文、补齐注释与文档缺口，并清掉几处审查中点名的欠账。不改数据库、路由、前端数据结构、Prompt/schema、正式评测资产。

## Requirements

### R1 — 框架级错误进入统一错误体

- `ApiExceptionHandler` 补映射：畸形请求体、路径/参数类型不匹配、缺少必填参数、方法不支持、媒体类型不支持、未知路径（含静态资源回退），以及一个兜底 `Exception → 500 internal_error`。
- 每个响应仍是 `{code, message, traceId}`；5xx 以 error 记录堆栈，4xx 以 warn 记录异常文本，消息不回显异常原文。

### R2 — 用户可见错误文案统一为中文

- `ApiException` / `ApiError` 里全部英文文案改中文（约 90 条，含两个过滤器与 `SecurityConfig` 的三条静态响应体）。
- 保留语义与状态码；GitHub/GitLab 权威字段缺失类消息保留字段名（如 `head.sha`），因为测试与排错都靠它。
- 前端 `ProjectMembersPage` 解析「第 N 行」的正则随后端文案同步。
- 内部不变式（`IllegalArgumentException`/`IllegalStateException`）、日志、Prompt 文本、`finding_event`/关联审计文本不在范围。

### R3 — 注释与规范

- 删除三处引用已删除 `TEST-ISSUES.md` 的 T 编号（`AppShell.vue`、`ResourceRemovalTest`、`workspace.spec.ts`），改为直述问题。
- SCM 身份与绑定一块（`ScmBindingService`、`ScmIdentityService`、`ScmIdentity`、`ProjectMemberScmBinding`、两个 Controller、`VerifiedScmUser`、`ScmIdentityUsage`、两个 Response、两个 Repository）补类级中文 Javadoc；九月新增的英文一行类注释改为中文并写出取舍。
- `ReviewDecisionService` 类注释写明「APPROVE 在持有 PR 行锁的事务内同步调远端合并」的代价与为何接受。
- `.trellis/spec/{backend,frontend}/index.md` 的语言规范改为：spec 用英文，代码注释与 `docs/` 用中文，与仓库现状一致。

### R4 — 文档同步

- `API.md` 补齐全部 70 个端点的索引表（动词、路径、谁可调用、成功状态），不改既有详述小节。
- `PRD.md` §7 增加两条已知限制：知识文档 FAILED 无重试端点（删除后重传）；列表端点不分页。
- `frontend/README.md` 门禁数字更新为 2026-09-20/21 实测（63 用例 / 18 文件，JS 263.09 kB）。

### R5 — 代码欠账

- `ReviewPipeline` 不再借 LEADER 身份检索：`KnowledgeService` 提供引擎专用的无 actor 检索入口，删除 `retrievalActor` 及其 SQL。
- `ScmPullRequestDecisionService` 两处裸 `orElseThrow()` 改为 `ApiException::notFound`。
- `frontend/nginx.conf`：`/api/` 代理响应的 Cookie 追加 `SameSite=Lax`；全站加 `Strict-Transport-Security` 与 `Referrer-Policy`。不加 `Secure`（回环 HTTP 演示路径仍要能登录）。

## Out of Scope

- 12 个 Controller 改用 `AccountPrincipal` 取 id：会让业务模块依赖 `auth` 的认证机制，与 ARCHITECTURE §1.3 冲突，不做。
- JaCoCo、知识 FAILED 重试端点、列表分页、两个千行 SFC 拆分、`BatchOneApiTest` 顺序依赖、`Thread.sleep` 测试重写。
- 五份 deliverables HTML 与 `TEST-REPORT.html`（快照日期不动）；正式评测、holdout、迁移文件一字不动。

## Constraints

- 不新增测试类；只同步既有测试中对英文文案的期望子串。
- 最终以 `ReviewOutputValidatorTest` 之外的受影响测试类定向运行 + 前端 lint/typecheck 为准，不重跑全量。

## Acceptance Criteria

- [ ] 畸形 JSON、类型错路径、未知路径、错误方法、错误 Content-Type、缺参数六种请求都返回 `{code,message,traceId}`。
- [ ] `grep` 后端主代码不再有传给 `ApiException`/`ApiError` 的英文句子。
- [ ] 仓库内不再出现 `T-00x` 引用。
- [ ] SCM 身份/绑定块每个类型都有中文类级 Javadoc。
- [ ] `API.md` 端点索引与 Controller 实际端点一一对应。
- [ ] `ReviewPipeline` 不再查询 `project_member_role`。
- [ ] 受影响测试类通过；前端 lint、typecheck 通过；`git diff --check` 通过。
