# 实施记录（2026-09-21）

## 改动清单

- R1 `common/ApiExceptionHandler`：新增 6 组框架异常映射（400 `bad_request`、404 `not_found`、405、415、422 `invalid_request` 扩到 `HandlerMethodValidationException`）与 `Exception → 500 internal_error` 兜底。
- R2 后端主代码 104 处英文文案改中文（含 `SecurityConfig` 三条静态响应体、`RateLimitFilter`、`ApiException` 两个工厂、质量检查三条规则文案、GitHub/GitLab 权威字段缺失消息保留字段名）。同步 7 个测试类的期望子串；`ProjectMembersPage.vue` 行号解析改为「第 N 行成员」并换算为 0 基索引。
- R3 三处 T 编号注释改直述；SCM 身份/绑定 12 个类型补中文类级 Javadoc；9 个英文一行类注释改中文并写出取舍；`ReviewDecisionService` 类注释补「持锁调远端合并」的代价段；两份 spec index 语言规范改为与仓库现状一致。
- R4 `API.md` 加框架错误码说明与 70 端点索引表；`PRD.md` §7 加两条已知限制；`frontend/README.md` 门禁数字更新。
- R5 `KnowledgeService.searchAsEngine` + `ReviewPipeline` 删除 `retrievalActor`；`ScmPullRequestDecisionService` 两处 `orElseThrow(ApiException::notFound)`；`nginx.conf` 加 HSTS、Referrer-Policy 与 `proxy_cookie_flags ~ samesite=lax`。

## 不做（与 PRD Out of Scope 一致）

Controller 改用 `AccountPrincipal`（违反 ARCHITECTURE §1.3）、JaCoCo、FAILED 重试端点、分页、SFC 拆分、deliverables HTML。

## 验证

- 前端：lint、typecheck、Vitest 63/18 全过。
- nginx：`nginx -t` 通过（upstream 用回环地址替代）。
- 后端：12 个受影响测试类定向运行 112 项，首轮 1 失败（`KnowledgeIngestionProcessor` 失败原因的收尾括号漏译），修正后该类 4/4 通过；其余 108 项通过，含 ArchUnit 8 项与 BatchOneApiTest 7 项。未重跑全量。
- `git diff --check` 通过；仓库内 `T-00x` 仅剩 `V10__resource_removal.sql`（迁移文件按 checksum 冻结，不动）。
