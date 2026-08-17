# P5 Finding 闭环与门禁扩展 — Design

> 恢复日期: 2026-08-17。父设计基线: `../08-16-forgepilot-upgrade/design.md` §8/§9。

## 1. Scope and compatibility

- 仅处理 Agent PR run 产生的 `agent_finding`;交互式/临时审查继续保持报告制。
- 既有 `Finding.status` 继续表示 pipeline 校验态(`candidate/verified/rejected`),新增 `lifecycle_status` 表示人工处置生命周期,两轴正交。
- 复用既有 `fingerprint` 作为跨 run 身份指纹;本任务不新增 `requirement_id` 冗余列,留给 P7 工作台评估。
- `AgentFindingResponse` 只追加字段,不删除或改名;SCM Conclusion 映射保持 `BLOCK -> ACTION_REQUIRED`,`PASS/WARN -> SUCCESS`。
- 自动化只能写 `resolution_suggestion`,不得自动修改 lifecycle,不得自动 CLOSED。

## 2. Persistence model

Flyway `V35__finding_lifecycle.sql`:

- `agent_finding.lifecycle_status varchar(32) not null default 'OPEN'`
- `assignee_id`, `fix_commit_sha`, `verified_by`, `verified_at`, `resolution_suggestion`
- `agent_run.gate_verdict varchar(16)`
- `idx_agent_finding_lifecycle`

实体层:

- `FindingLifecycle`: `OPEN -> CONFIRMED -> IN_PROGRESS -> FIXED -> VERIFIED -> CLOSED`
- 旁路: `OPEN/CONFIRMED -> REJECTED`;验证打回: `FIXED -> IN_PROGRESS`
- `CLOSED/REJECTED` 为终态。
- `Finding` 封装指派、流转、验证人/验证时间和建议写入;服务层负责授权与合法性校验。
- `AgentRun` 需追加 `gateVerdict` 的持久化与响应读取接缝。

## 3. Authorization and transitions

| Operation | Allowed role/user |
|---|---|
| list/read | project member |
| assign | LEADER only; assignee must be a project member |
| confirm/reject/verify/close | REVIEWER or LEADER |
| start fix/mark fixed | assigned user or LEADER |
| reopen FIXED to IN_PROGRESS | assigned user or LEADER |

Illegal edges return `FINDING_TRANSITION_ILLEGAL` (HTTP 409). Cross-project finding IDs are hidden as `FINDING_NOT_FOUND`.

API `action` uses target lifecycle names: `CONFIRMED`, `REJECTED`, `IN_PROGRESS`, `FIXED`, `VERIFIED`, `CLOSED`. `FIXED` may carry `fixCommitSha`.

## 4. API contracts

- `GET /api/projects/{projectId}/findings?lifecycle=&page=&size=`
  - project-wide paginated Agent findings; optional lifecycle filter.
- `POST /api/projects/{projectId}/findings/{findingId}/lifecycle`
  - body `{ "action": "FIXED", "fixCommitSha": "..." }`.
- `POST /api/projects/{projectId}/findings/{findingId}/assign`
  - body `{ "userId": 123 }`.
- Existing Agent finding responses append:
  - `lifecycle`, `assigneeId`, `fixCommitSha`, `verifiedBy`, `verifiedAt`, `resolutionSuggestion`.

Controller delegates to `FindingLifecycleService`; DTO validation rejects missing action/userId before domain logic.

## 5. Automatic resolution suggestion

`FindingResolutionSuggester` runs best-effort after a new PR run has persisted verified findings:

1. Resolve `(installationId, pullRequestNumber)` from `AgentScmContext`.
2. Load earlier runs for the same PR and their active findings (`lifecycle not in REJECTED/CLOSED`).
3. Build the new run's verified fingerprint set.
4. For every historical active finding:
   - fingerprint present -> `STILL_PRESENT`
   - fingerprint absent -> `RESOLVED_SUGGESTED`
5. Persist suggestions only; log/debug failures and never fail publication.

P5 deliberately implements exact fingerprint matching only. LLM drift matching and `UNKNOWN` remain a later enhancement unless acceptance scope is expanded.

## 6. Run gate verdict

`RunGateVerdictService` calculates a run-level `PASS/WARN/BLOCK` after coverage and lifecycle inputs are available:

- `BLOCK`: any blocking decision whose finding lifecycle is not `REJECTED/CLOSED`.
- `WARN`: no BLOCK and at least one of:
  - coverage contains `NOT_FOUND` or `AT_RISK`;
  - active `HIGH/CRITICAL` finding remains unclosed;
  - a finding is `FIXED` but not `VERIFIED`.
- `PASS`: none of the above.

Publication persists `agent_run.gate_verdict`, appends one summary line to SCM notes, and preserves the existing Conclusion mapping.

## 7. Frontend quality center

Add `/quality` and navigation label `质量中心` using the existing 墨境 visual system:

- lifecycle filter + paginated project findings;
- row/detail view with lifecycle, severity, assignee, fix SHA and resolution suggestion badge;
- role-aware action controls;
- member selector sourced from the existing project-member API;
- explicit loading/error/empty states and refresh after mutation.

Frontend role filtering is usability only; backend remains authoritative.

## 8. Test strategy

- Unit: transition matrix, terminal states, role guards, assignee membership.
- MVC/integration: list/filter/page, DTO fields, 403/404/409 negative cases.
- Unit: suggester hit/miss/failure-no-throw/no-lifecycle-change.
- Unit: gate BLOCK/WARN/PASS matrix and Conclusion compatibility.
- Migration/application: backend `mvn verify`.
- Frontend: API composable/component tests, `npm test`, `npm run build`.

## 9. Rollback

- Code rollback is commit-level.
- Flyway V35 is forward-only; rollback code must tolerate additive columns remaining.
- New response fields and `/quality` are additive, so older clients remain compatible.
