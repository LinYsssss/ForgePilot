# 批次 3 API 契约

**这份文件在派发实现之前冻结端点形状。** 批次 1 的经验：三个代理各写各的切片，
`BatchOneApiTest` 在**第一次运行**就通过了——因为契约先写死。批次 3 的切片更多，这一步更重要。

权限一律以 `PRD.md` §3 的角色矩阵为准，本文只是把它翻译成端点。矩阵没覆盖的转换见 §5，**属规格缺口**。

## 1. 通用约定（延续批次 1、2）

- 路径前缀 `/api/projects/{projectId}/...`；跨项目一律 **404**，与「不存在」不可区分。
- 权限不足：若资源本身对该用户不可见 → **404**；可见但动作越权 → **403**。
- 并发冲突 / 状态机非法转换 → **409**。输入不合法 → **422**。
- 所有写端点需 CSRF（批次 1 已确立）。
- 错误体 `{code, message}`，`code` 为稳定短串。

## 2. Review

### 2.1 请求一次 Review（自动、人工、失败重试**共用同一条服务路径**）

```
POST /api/projects/{projectId}/pull-requests/{pullRequestId}/reviews
body: {}                                     无请求体字段
→ 202 {reviewId, status, executionAttempt}
```

权限：**LEADER ✅ / REVIEWER ✅ / DEVELOPER 仅本人 PR**（PRD §3「触发/重试 Review（含版本过期后的重审）」）。
「本人」由 `pull_request.author_external_user_id` 对 `project_member.scm_external_user_id` 判定，
**禁止按用户名**（P11 / [D010](../../../../../docs/v2/DECISIONS.md#d010)）。

语义按 Review Identity 四元组（**取 PR 当前值**）find-or-create：

| 既有行状态 | 结果 |
|---|---|
| 无此身份 | 新建 `PENDING`，202 |
| `FAILED` | **复用同一行**回到 `PENDING`；随后 Worker 原子领取时递增 attempt，202 |
| `PENDING` / `RUNNING` | 幂等返回既有行，202（不重复入队） |
| `COMPLETED` | **409** —— `COMPLETED` 永不重跑或覆盖（ARCHITECTURE §3.2） |

这条端点同时承担「需求版本变化后的人工重审」：新版本 → 新 `requirement_revision_id` → **新身份** → 新建行，
旧 Review 保留。**服务端不自动重审**（§3.1 禁止补建）。

### 2.2 Review 详情（Phase 6 退出条件：只读页可用）

```
GET /api/projects/{projectId}/reviews/{reviewId}
→ 200 {
    id, pullRequestId, headSha, reviewInputFingerprint,
    requirementId, requirementRevisionId,          // 同空或同非空
    status,                                        // PENDING|RUNNING|COMPLETED|FAILED
    decision, decisionBy, decisionAt, decisionComment,
    isCurrent,                                     // 派生：四项匹配 PR 当前值
    contextSnapshot: {...},                        // 不可变快照
    coverage: {truncated: bool, files: [...], notReviewed: [...]},   // D002：必须显式呈现
    acVerdicts: [{acId, acKey, verdict}],          // COVERED|NOT_FOUND|AT_RISK，每条 AC 必有
    findings: [ …见 3.1… ],
    engine, promptVersion, model, executionAttempt
  }
```

权限：项目成员即可读（`requireMember`）。
`isCurrent` **是派生的，不是列**——不设 `INVALIDATED` 状态（ARCHITECTURE §3.5）。
`notReviewed` 为空数组与「字段缺失」必须可区分：未审查文件禁止静默截断（[D002](../../../../../docs/v2/DECISIONS.md#d002)）。

### 2.3 一个 PR 的 Review 历史

```
GET /api/projects/{projectId}/pull-requests/{pullRequestId}/reviews
→ 200 [{id, headSha, requirementRevisionId, status, decision, isCurrent, createdAt}]
```

按 `(created_at, id)` 确定性排序（§3.6 第 3 条要求这个排序是确定的）。**旧 Review 全部保留。**

### 2.4 终局 Decision（一次性）

```
POST /api/projects/{projectId}/reviews/{reviewId}/decision
body: {decision: "APPROVE"|"REQUEST_CHANGES", comment?: string}
→ 200 {decision, decisionBy, decisionAt}
→ 409 并发冲突或前置不满足
```

权限：**LEADER ✅ / REVIEWER ✅ / DEVELOPER ❌**。

实现**必须**是：`SELECT ... FOR UPDATE` 锁 `pull_request` 行 → 逐条校验六项前置 →
`WHERE decision='PENDING'` 条件更新 → **影响行数必须为 1，否则 409**。
禁止用普通 `EXISTS` 查询或无条件 save 代替（ARCHITECTURE §3.1 原文）。

六项前置（缺一不可）：

1. `review.status = COMPLETED`
2. `review.decision = PENDING`
3. `review.head_sha = pull_request.head_sha`
4. `review.review_input_fingerprint = pull_request.review_input_fingerprint`
5. `review.requirement_revision_id IS NOT DISTINCT FROM` PR 当前关联需求版本（**NULL 亦须相等**）
6. 该 `head_sha` 上**不存在任何** `REQUEST_CHANGES`

第 6 条就是 Decision Gate。**改 Base、需求关联、需求版本或重新同步 Diff 都不能解除它**，只能靠新 head SHA。

## 3. Finding

### 3.1 Finding 在 Review 详情内返回

```
{
  id, findingType, path, line, evidence,
  status,                    // OPEN|CONFIRMED|IN_PROGRESS|FIXED|VERIFIED|CLOSED|REJECTED
  continuity,                // NEW|PERSISTING|SUPPRESSED   —— 与 status 正交
  requirementId, requirementRevisionId, acId, acKey,
  assigneeId, carriedFromFindingId, findingKey, evidenceHash, basisHash
}
```

**本文早先写的 `severity` 与 `title` 已作废。** `ARCHITECTURE.md` §2.1 的 `finding` 列清单里
没有这两列，加列属扩充 §2.1，本批次不做——展示内容由 `evidence` 承载。
这是我在写契约时凭直觉加的字段，与 16 表 schema 冲突，由代理 B 发现并指出。

**`status` 与 `continuity` 不得混入同一字段或同一 UI 标签**（PRD §5）。
`NOT_REPORTED` **不在这里**——它是查询派生的，不落库（§3.6 第 3 条）。

### 3.2 人工状态流转

```
POST /api/projects/{projectId}/findings/{findingId}/status
body: {status: "<目标状态>", comment?: string}
→ 200 {status}
→ 409 非法转换
```

权限按 PRD §3 逐转换判定：

| 转换 | LEADER | DEVELOPER | REVIEWER | 依据 |
|---|:--:|:--:|:--:|---|
| `OPEN → CONFIRMED` | ✅ | ❌ | ✅ | 「Finding 确认 / 拒绝」 |
| `OPEN → REJECTED` | ✅ | ❌ | ✅ | 同上 |
| `CONFIRMED → REJECTED` | ✅ | ❌ | ✅ | 同上 |
| `CONFIRMED → IN_PROGRESS` | ❌ | ✅ | ❌ | 「Finding 认领」——**LEADER 也不行** |
| `IN_PROGRESS → FIXED` | ❌ | ✅ | ❌ | 「标记已修复」——**LEADER 也不行** |
| `FIXED → VERIFIED` | ✅ | ❌ | ✅ | 「验证通过」 |
| `FIXED → IN_PROGRESS` | ✅ | ❌ | ✅ | 「打回」 |
| `VERIFIED → CLOSED` | — | — | — | **矩阵未覆盖，见 §5.1** |
| `REJECTED → OPEN` | — | — | — | **矩阵未覆盖，见 §5.2** |

「LEADER 也不行」这两条不是笔误：PRD §3 那两行 LEADER 列写的就是 ❌。
**照矩阵实现，不要因为「LEADER 应该什么都能做」而擅自放宽**——放宽是越权，收窄才是安全方向。

每次成功流转**同事务**写一条 `finding_event`（actor、from、to、comment、created_at）。
`finding_event` 需与批次 2 的 `pull_request_requirement_event` 同型，带
「必须真的记录了一次变化」的 CHECK（`ck_finding_event_is_a_change`）。

### 3.3 指派

```
POST /api/projects/{projectId}/findings/{findingId}/assignee
body: {userId: number|null}
→ 200 {assigneeId}
```

权限：与「认领」同侧（DEVELOPER 认领自己）——**具体规则见 §5.3，属待裁定项**。
`assignee_id → project_member`（成员退出后活权限必须失效，ARCHITECTURE §2.3）。
置空是合法操作，同样留痕。

### 3.4 审计

```
GET /api/projects/{projectId}/findings/{findingId}/events
→ 200 [{id, actorId, action, fromStatus, toStatus, comment, createdAt}]
```

权限：项目成员即可读。

## 4. 需求质量检查（Phase 6）

```
POST /api/projects/{projectId}/requirements/{requirementId}/quality
→ 200 {revisionId, rules: [...], ai: {...}}
```

权限：**仅 LEADER**（PRD §3「运行需求质量检查」）。
结果归属**具体 Revision**；DRAFT 期正文一改即失效——批次 1 已实现「同事务清空 `quality_json`」，
本批次填入真实内容，**不得改动那条清空逻辑**。

## 5. 规格缺口（PRD §3 矩阵未覆盖，设计阶段必须裁定）

这三条**不是**可以随手决定的实现细节，它们都是授权语义：

### 5.1 `VERIFIED → CLOSED` 由谁执行

矩阵有「验证通过」（LEADER/REVIEWER）但没有单独的「关闭」。候选：
(a) `CLOSED` 与 `VERIFIED` 合并，验证通过即终态——但状态机明确列了两个状态；
(b) 由 LEADER/REVIEWER 执行，视作「验证通过」的第二步；
(c) 由 LEADER 独占，与 P9「需求 DONE 由 LEADER 执行」同侧。
**倾向 (b)**，但需在 `design.md` 明确裁定并写明理由。

### 5.2 `REJECTED → OPEN`（重开）由谁执行

PRD §5 只说「**仅** `continuity=SUPPRESSED` 的继承驳回项，须留审计」，没说谁。
候选：LEADER/REVIEWER（与「确认/拒绝」同侧）或 LEADER 独占。
**注意**：重开的是**被抑制的继承项**，即上一轮人工判定为 REJECTED 而本轮自动沿用的那些。
放宽这条等于让人绕过 P10 的抑制机制。**倾向与「确认/拒绝」同侧（LEADER/REVIEWER）**，需裁定。

### 5.3 Finding 指派权

矩阵有「Finding 认领」（DEVELOPER）但没有「指派他人」。
若只支持自我认领，则 `assignee` 端点可以并入 `CONFIRMED → IN_PROGRESS` 转换，**不单独开端点**。
**倾向：本批次不开独立指派端点**，认领即指派自己——少一个端点、少一处授权面。需裁定。

## 6. 待研究回填

- **Review activity 的值域已核实为两层**（见 `research/review-activity-matrix.md`，我已逐字核对 `DECISIONS.md:122`）：
  单 PR 只有 6 个值 `REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/APPROVED`；
  `NO_PR` 与 `MIXED` **只属于需求级聚合**。
  `IMPLEMENTATION-PLAN.md:73` 那个 8 值清单是两层值域的**并集**，不是一层的值域。
  **把它实现成一个 8 值枚举按 PR 算是错的。** 具体字段与计算归属见 `design.md`。
- **三个一级页面已核实**（我自己读了 `frontend/src/app/routes.ts`）：
  `项目 / 研发需求 / 代码审查`。**`/reviews` 就是第三个一级页面本身**，不是挂在别人下面的子页面——
  本文早先的相反说法已作废。Finding 确实无独立路由，必须长在 `/reviews/:id` 内。
  `/reviews`、`/reviews/:id`、`/projects/:id/settings` 目前**三个都是 `FoundationPlaceholderPage`**。
- fencing 相关列的确切名字与类型——等 `research/fencing-and-concurrency-measured.md`。
- 约束触发器的确切形态——等 `research/finding-constraint-trigger-measured.md`。
- after-commit 调度形态——等 `research/after-commit-scheduling-measured.md`。
