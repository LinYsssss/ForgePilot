# Result — 顶部导航、界面统一与演示数据重置

## Outcome

本任务的 7 条验收标准全部完成。已登录 Shell 使用顶部居中的六入口导航，登录页只显示应用图标；工作台突出 AI 辅助链路，项目知识突出真实向量索引元数据，主要页面共享统一的选择器、面板、记录与空状态布局。既有 API、路由、权限和数据源未改变。

更新后的 `fp-demo` 已部署。PostgreSQL 卷未删除；15 张业务表已在一个事务中清空，`user_account` 未进入清理语句，唯一启用账号 `ysainlin` 的 ID、启用状态和 session version 保持不变。

## Frontend validation

| Command | Result |
|---|---|
| `npm run lint` | PASS — `Frontend foundation policy checks passed.` |
| `npm run typecheck` | PASS — strict `vue-tsc` exited 0 |
| `npm run test -- --run` | PASS — 11 files, 35/35 tests |
| `npm run build` | PASS — 85 modules; JS 211.92 kB, CSS 64.78 kB |
| `git diff --check` | PASS |

Static audit confirmed no `package.json` or lockfile changes, no route additions, no sidebar reference in production source, one Login image, one signed-in Shell lockup, and the unchanged six top-level navigation entries. The existing `navigationTarget` project-query behavior was not modified.

## Deployment evidence

`docker compose -p fp-demo up --build --detach --wait` rebuilt the application images without recreating or deleting the named PostgreSQL volume. Final state:

- `fp-demo-postgres-1`: healthy
- `fp-demo-backend-1`: healthy
- `fp-demo-frontend-1`: healthy
- backend `/actuator/health`: HTTP 200, `status=UP`
- frontend `/healthz`: HTTP 200, `ok`
- `/`, all six product routes, `logo-app.png`, and `logo-lockup.png`: HTTP 200

## Irreversible data cleanup record

Preflight proved that `user_account` contained exactly:

```text
id=4, username=ysainlin, enabled=true, session_version=0
```

The deployed database initially contained 4 projects, 4 project memberships, 3 requirements, 4 revisions, 8 acceptance criteria and 2 AI call logs; the other target tables were already empty.

After deployment health passed, one PostgreSQL transaction executed `TRUNCATE ... RESTART IDENTITY` against the explicit 15-table list from the task design. The transaction returned `BEGIN`, `TRUNCATE TABLE`, `COMMIT`.

Post-cleanup proof:

- `user_account`: still exactly one row — `id=4, username=ysainlin, enabled=true, session_version=0`
- `project`, `project_member`, `requirement`, `requirement_revision`, `acceptance_criterion`, `knowledge_document`, `requirement_attachment`, `knowledge_chunk`, `scm_repository`, `pull_request`, `pull_request_requirement_event`, `review`, `finding`, `finding_event`, `ai_call_log`: each has 0 rows

This deletion is intentional and not recoverable from the application. The schema, Flyway history, PostgreSQL volume and preserved account password hash were untouched.

## Boundaries and remaining gate

- No backend code, migration, reset API, global-admin role, dependency, new route, fake metric, chat/Agent or second AI runtime was added.
- D018 and the frontend specs now record centered top navigation and single-visible-Logo placement.
- Code is deployed but not committed or pushed in this task turn. Commit/push remains behind explicit user authorization; the root `logo-app.png` and `logo-lockup.png` originals remain untracked and excluded from the proposed commits.

## Proposed commit groups

1. `docs(product): define centered top navigation` — D018, Architecture, frontend specs and execution gate.
2. `feat(frontend): move product shell to centered top navigation` — Shell, login, workspace, Knowledge, Repository, shared CSS and focused tests.
3. `chore(trellis): record top navigation and data reset` — task artifacts and this result.
