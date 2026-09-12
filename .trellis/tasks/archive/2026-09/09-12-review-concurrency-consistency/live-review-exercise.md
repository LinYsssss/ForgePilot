# Live sequential review exercise — 2026-09-12

Completed **15/15 PRs sequentially** on the deployed application. The reviewer confirmed **55** findings and rejected **2**. All 15 ForgePilot reviews received REQUEST_CHANGES; developer `dev01` claimed the confirmed issues. Requirements remain IN_DEVELOPMENT, and the remote PRs remain open for real fixes.

The source branches deliberately contain defects. No repair or verification state was fabricated. This is a fresh application workflow exercise, independent of the immutable formal evaluation.

## Per-PR results

| Order | Scenario | GitHub PR | ForgePilot review | Confirmed / rejected | Decision |
|---|---|---|---|---:|---|
| 1 | mall: 仓库发货前置校验 | [#7](https://github.com/LinYsssss/forgepilot-demo-mall-order-service/pull/7) | [Review 2](https://yasinlin.com/reviews/2?project=1), REQ-3 | 4 / 0 | 退回修改 |
| 2 | mall: 管理端强制发货授权与审计 | [#8](https://github.com/LinYsssss/forgepilot-demo-mall-order-service/pull/8) | [Review 3](https://yasinlin.com/reviews/3?project=1), REQ-4 | 3 / 0 | 退回修改 |
| 3 | mall: 订单搜索参数绑定与数据保护 | [#9](https://github.com/LinYsssss/forgepilot-demo-mall-order-service/pull/9) | [Review 4](https://yasinlin.com/reviews/4?project=1), REQ-5 | 4 / 0 | 退回修改 |
| 4 | mall: 大促批量发货权限与幂等 | [#10](https://github.com/LinYsssss/forgepilot-demo-mall-order-service/pull/10) | [Review 5](https://yasinlin.com/reviews/5?project=1), REQ-6 | 5 / 0 | 退回修改 |
| 5 | mall: 用户订单详情归属校验与脱敏 | [#11](https://github.com/LinYsssss/forgepilot-demo-mall-order-service/pull/11) | [Review 6](https://yasinlin.com/reviews/6?project=1), REQ-7 | 3 / 0 | 退回修改 |
| 6 | tenant: 运营后台令牌认证与角色授权 | [#1](https://github.com/LinYsssss/forgepilot-demo-tenant-user-center/pull/1) | [Review 7](https://yasinlin.com/reviews/7?project=2), REQ-8 | 3 / 0 | 退回修改 |
| 7 | tenant: 运营用户查询隔离与分页 | [#2](https://github.com/LinYsssss/forgepilot-demo-tenant-user-center/pull/2) | [Review 8](https://yasinlin.com/reviews/8?project=2), REQ-9 | 3 / 1 | 退回修改 |
| 8 | tenant: 用户数据导出范围与审计 | [#3](https://github.com/LinYsssss/forgepilot-demo-tenant-user-center/pull/3) | [Review 9](https://yasinlin.com/reviews/9?project=2), REQ-10 | 4 / 0 | 退回修改 |
| 9 | tenant: 密码重置授权与安全存储 | [#4](https://github.com/LinYsssss/forgepilot-demo-tenant-user-center/pull/4) | [Review 10](https://yasinlin.com/reviews/10?project=2), REQ-11 | 5 / 0 | 退回修改 |
| 10 | tenant: 运营前端安全渲染与配置合并 | [#5](https://github.com/LinYsssss/forgepilot-demo-tenant-user-center/pull/5) | [Review 11](https://yasinlin.com/reviews/11?project=2), REQ-12 | 3 / 0 | 退回修改 |
| 11 | payment: 即时结算金额与业务校验 | [#1](https://github.com/LinYsssss/forgepilot-demo-payment-settlement-service/pull/1) | [Review 12](https://yasinlin.com/reviews/12?project=3), REQ-13 | 5 / 0 | 退回修改 |
| 12 | payment: 人工退款幂等授权与日志保护 | [#2](https://github.com/LinYsssss/forgepilot-demo-payment-settlement-service/pull/2) | [Review 13](https://yasinlin.com/reviews/13?project=3), REQ-14 | 3 / 1 | 退回修改 |
| 13 | payment: 银行回调验签与事件幂等 | [#3](https://github.com/LinYsssss/forgepilot-demo-payment-settlement-service/pull/3) | [Review 14](https://yasinlin.com/reviews/14?project=3), REQ-15 | 4 / 0 | 退回修改 |
| 14 | payment: 结算运营查询租户隔离 | [#4](https://github.com/LinYsssss/forgepilot-demo-payment-settlement-service/pull/4) | [Review 15](https://yasinlin.com/reviews/15?project=3), REQ-16 | 3 / 0 | 退回修改 |
| 15 | payment: 商户费率预览配置与舍入 | [#5](https://github.com/LinYsssss/forgepilot-demo-payment-settlement-service/pull/5) | [Review 16](https://yasinlin.com/reviews/16?project=3), REQ-17 | 3 / 0 | 退回修改 |

## Rejected findings

- Case 7, finding 33: 演练核验驳回：当前函数没有排序字段或方向输入，也没有 ORDER BY 或原始排序片段，无法据此认定绕过排序白名单。AC-4 约束排序输入的使用方式，并未明确要求新增可配置排序功能；已有关键字 SQL 拼接风险由发现 31 单独确认。
- Case 12, finding 53: 演练核验驳回：当前差异仅新增普通 refund 方法，没有强制退款入口、force 参数或对 forceRefund 的调用；无法把 AC-3 对强制操作的授权条件直接认定为此路径已绕过 ADMIN 校验。AC-3 仍需在实际强制退款路径核验，未因此判为已覆盖。

## Verified workflow

- Logged into the real public frontend as the existing owner, reviewer, and developer. Created, published, and assigned each requirement through the browser; used reviewer and developer page actions for the finding lifecycle and decision.
- Configured the missing repository connections and memberships through the application, synchronized the real GitHub webhooks, and verified 12 READY project specification documents across the three projects.
- Each real GitHub PR creation generated a successful signed `pull_request / opened` webhook delivery. All 15 reached COMPLETED in the existing Review Engine.
- Before each decision, compared GitHub head/base and every returned patch with the immutable review snapshot, fingerprint, and requirement revision. All 15 matched; all 30 changed-file entries were covered without truncation.
- Each finding has an explicit evidence-based reviewer disposition. Confirmed findings have a developer claim. Event actor IDs, comments, persisted decisions, unchanged PR heads, and requirement status/version were checked.
- The next case started only after the prior case finished its decision and developer actions. Non-overlap is asserted from the recorded start and finish timestamps.
- A final read through the public browser verified all three project review lists and each exercise review, finding disposition, developer assignment, requirement status, and remote PR head. Each project has five exercise reviews, no pending/running reviews, and four READY project knowledge documents.
- A read-only database check confirmed 112 finding events, matching exactly two events per confirmed finding and one per rejected finding. Review input and immutable snapshot mismatch counts are zero. Public and loopback health checks passed, the deployed JavaScript still matches the verified build, and a private post-exercise database backup passed its restore-list check.

## Observed operational issues

Uploading tenant `auth-policy.md` returned HTTP 504 at the public proxy while the backend was retrying an embedding call. Read-back confirmed a single READY document after about 70 seconds, so it was not uploaded again. The preparation driver was adjusted to reconcile ambiguous gateway failures by reading stored state. This live observation is separate from the four concurrency fixes.

During case 5, a browser response wait exceeded the initial 20-second automation timeout. A read-only database check showed that finding 24 had been confirmed while findings 25 and 26 remained OPEN and the review decision remained PENDING. The driver resumed from that persisted state, verified the audit comments, and completed the remaining actions without duplicating events. Its response wait was increased to 45 seconds; no production code was changed for this recovery.

The first case 9 confirmation click timed out while browser scrolling left the button beneath the sticky header. Read-back showed all five findings still OPEN, the decision PENDING, and no event for the first finding. Centering the button with native instant scrolling put it visibly below the header; the driver resumed with ordinary pointer clicks and their normal visibility checks. Screenshots and the state check are retained in the local evidence directory. This adjustment affected only the browser driver.

Case 12 saved the reviewer decision successfully, then an element screenshot timed out waiting for layout stability. The resumed driver checked the existing finding events and exact decision comment, captured a centered viewport screenshot with animations disabled for the capture, and completed the pending developer claims without repeating the decision.

The first final-audit attempt rounded GitHub delivery IDs beyond the JavaScript safe-integer range, producing an incorrect detail URL. The audit helper now preserves each unsafe integer from the original JSON text, and the recorded delivery IDs are exact strings. The audit was read-only; no webhook was redelivered and no review was rerun.

Some model line numbers pointed to adjacent lines: case 9 findings 38 and 40 used line 4 while quoting the signature on line 3, and case 13 used line 5 for the callback method on line 4. The complete changed functions support the confirmed gaps. Reviewer comments record the precise context and limit each confirmation to what the code shows; the original model output remains preserved. A reported line number or an unsupported secondary claim was not treated as sufficient evidence by itself.

Case 14 finding 60 was confirmed only for the explicit AC requiring parameter binding. Its additional SQL-injection claim was not accepted: the shown Java Long argument does not admit arbitrary SQL text. This qualification is visible in both the finding audit comment and the review decision. The confirmation count therefore counts valid findings, not agreement with every sentence of model prose.

## Evidence and boundaries

- [Machine-readable results, findings, human comments, audit events, timestamps, and webhook delivery metadata](live-review-results.json).
- [Case requirements and acceptance criteria](exercise-cases.json).
- [Deployment and prior-review cleanup](deployment.md).
- Local browser screenshots and full review payloads: `/tmp/forgepilot-sequential-pr-20260912/evidence/`.
- Credentials, cookies, and database backups remain private and outside Git.

The three fixture repositories contain small demonstration implementations. This exercise verifies the deployed ForgePilot workflow with real GitHub/AI calls; it does not establish production readiness of those fixture applications or replace the frozen evaluation.
