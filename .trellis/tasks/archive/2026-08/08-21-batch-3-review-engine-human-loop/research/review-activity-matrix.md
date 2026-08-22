# Research: Review Activity 判定矩阵（Phase 6 + Phase 7）

- **Query**: 把 `REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/MIXED/APPROVED/NO_PR` 八个值的判定条件完全写死；判定顺序；`MIXED` 的确切含义；Review 身份四元组的「当前有效」判定与唯一约束；Review 通过后需求 `DONE` 由谁在什么条件下推进
- **Scope**: internal（权威文档 + 已落地代码 + 已应用迁移）
- **Date**: 2026-08-21

---

## 0. 三条最重要的结论（先读这个）

| # | 结论 | 性质 |
|---|---|---|
| C1 | **`MIXED` 与 `NO_PR` 不是单 PR 的取值。** 单 PR 值域只有 6 个：`REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/APPROVED`。`NO_PR` 与 `MIXED` 只出现在**需求级聚合**。PLAN:73 的 8 值清单是两个层级值域的并集，不是一个层级的值域。 | **原文明确规定**（DEC:122 逐字列出单 PR 值域且其中没有 MIXED / NO_PR） |
| C2 | **`MIXED` 是残差值，不是优先级值。** 只有在「≥2 个 PR、无任何 FAILED、无任何 CHANGES_REQUESTED、且子状态不全相同」时才返回。`{CHANGES_REQUESTED, APPROVED}` 的结果是 **`CHANGES_REQUESTED` 而不是 `MIXED`**。 | **原文明确规定**（PRD:117、DEC:122） |
| C3 | **单 PR 的六个条件在可达数据上互斥**，因此「判定顺序」在正确数据上不影响结果。但原文**没有**说「首个匹配胜出」，互斥性是我从 `review` 状态机 + Decision 前置条件推导出来的，**依赖两条实现必须真的成立的约束**（见 §4.2）。 | **推导**（推导链见 §4.2） |

---

## 1. 原文出处（逐字，后文只引编号）

> 引用格式：`文件:行号`。行号对应 2026-08-21 工作树（`git rev-parse HEAD` = `2cd09fb`）。

### 1.1 PRD.md

- **PRD:106**
  > 无 `IN_REVIEW`：评审进展是**只读派生量** `review_activity`，按关联 PR 的**当前 head + 当前 Diff fingerprint + 当前需求版本**计算，不落表。单 PR 映射如下：

- **PRD:108–115**（单 PR 映射表，逐行）

  | 条件 | Activity |
  |---|---|
  | 当前关联下没有匹配 head/fingerprint/revision 的 Review | `REVIEW_REQUIRED` |
  | 当前 Review 执行失败 | `FAILED` |
  | 当前 Review 的 Decision 为 `REQUEST_CHANGES` | `CHANGES_REQUESTED` |
  | 当前 Review 正在 RUNNING，或已 COMPLETED 但仍等待人工 Decision | `REVIEWING` |
  | 当前 Review 为 PENDING | `PENDING` |
  | 当前 Review 的 Decision 为 `APPROVE` | `APPROVED` |

- **PRD:117**
  > Requirement 没有关联 PR 时为 `NO_PR`。多 PR 聚合先让 `FAILED`、`CHANGES_REQUESTED` 两类风险状态依次占优；否则全部子状态相同就返回该状态，全部 `APPROVED` 才返回 `APPROVED`，其余组合返回 `MIXED` 并在 UI 展示各状态计数。需求状态与评审活动并列展示，不得合并。

- **PRD:103**
  > `DRAFT → READY` 由 LEADER 确认；`READY → IN_DEVELOPMENT` 与**首次指派**同事务完成，后续更换负责人不再改变状态；`→ DONE` 由 LEADER 确认全部关联工作完成。**AI、Webhook、PR、Review 一律不得推进这些状态。**

- **PRD:119**
  > 需求版本变更**不自动重审**，关联 PR 显示"审查已过期"，由人工按上表权限触发。

- **PRD:135–136**（Review Decision）
  > `PENDING | APPROVE | REQUEST_CHANGES`。**AI 置信度、Finding 状态、Review Decision 三者不互相替代**，UI 上必须分开呈现。
  > 终局 Decision 只能从 `PENDING` **写入一次**，目标 Review 必须已完成，且 head、Diff fingerprint 与需求版本均等于 PR 当前值；同一 head 出现 REQUEST_CHANGES 后必须有新 head SHA 才能再次 APPROVE

- **PRD:145**（P4）、**PRD:150**（P9）、**PRD:174–176**（Phase 7 E2E 三条）

### 1.2 ARCHITECTURE.md

- **ARCH:102**（`review` 表）
  > `UNIQUE NULLS NOT DISTINCT (pull_request_id,head_sha,review_input_fingerprint,requirement_revision_id)`（D003）；`UNIQUE(project_id,id)` 供 Finding 父 FK；CHECK 保证 requirement_id 与 requirement_revision_id 同空或同非空、Decision 字段组合合法；一次性 Decision 由 PR 行锁 + `WHERE decision='PENDING'` 条件更新保证；project_id、不可变的 review_input_fingerprint/context_snapshot_json、status、decision、decision_by/at/comment、execution_attempt/token/lease、engine/prompt/model 审计列

- **ARCH:267–273**
  ```text
  Review Identity  = pull_request_id + head_sha + review_input_fingerprint + requirement_revision_id
  Current Validity = Review 的 head/fingerprint/requirement revision 均等于 PR 当前值
  Decision Gate    = pull_request_id + head_sha 上是否已有 REQUEST_CHANGES
  ```

- **ARCH:275**
  > Base、changed files、patch 或纳入指纹的稳定 Diff version 改变时，即使 head SHA 不变，也必须形成新的 Review Identity；旧 Review 保留但不再当前有效。

- **ARCH:279–284**（Decision 六条前置，逐条）
  > 1. 目标 Review 的 `status = COMPLETED`；2. 目标 Review 的 `decision = PENDING`；3. `review.head_sha = pull_request.head_sha`；4. `review.review_input_fingerprint = pull_request.review_input_fingerprint`；5. `review.requirement_revision_id IS NOT DISTINCT FROM pull_request` 当前关联需求版本（**NULL 亦须相等**）；6. 该 `head_sha` 上不存在任何 `REQUEST_CHANGES`

- **ARCH:290–299**（执行状态机，值域即 `PENDING/RUNNING/COMPLETED/FAILED`）
  ```text
  [*] --> PENDING     : PullRequestChanged / 手动触发
  PENDING --> RUNNING : 原子领取并生成 attempt/token/lease
  RUNNING --> COMPLETED : 输出通过校验
  RUNNING --> FAILED  : AI 失败或非法 JSON 修复无效
  FAILED --> PENDING  : 人工重试（复用同一行）
  RUNNING --> PENDING : lease 过期后 reconciliation 恢复
  COMPLETED --> [*]
  ```

- **ARCH:303**
  > 失败重试复用原行并产生新 attempt/token；`COMPLETED` 永不重跑或覆盖。Decision 与执行状态正交：`PENDING | APPROVE | REQUEST_CHANGES`。

- **ARCH:261**
  > 按 `(pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)` **幂等创建或取得** Review(PENDING)

- **ARCH:346–347**
  > 页面以当前 PR 的 head/fingerprint/revision 对比快照派生"当前/已过期"，不写 `INVALIDATED` 状态。
  > 旧 Review **永不失效、永不覆盖**，上下文变更后构成新的 Review 身份

- **ARCH:206–207**（行内 CHECK）
  ```sql
  CHECK ((requirement_id IS NULL AND requirement_revision_id IS NULL)
      OR (requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL));
  ```

- **ARCH:170–173**（`review` 复合外键）
  ```text
  review
    (project_id, pull_request_id) -> pull_request(project_id, id)
    (project_id, requirement_id, requirement_revision_id)
      -> requirement_revision(project_id, requirement_id, id)
  ```

- **ARCH:424**
  > 需求状态与派生的评审活动（D011）同样是两个正交维度，不得合并为一个标签。

### 1.3 DECISIONS.md

- **DEC:122**（D011，**单 PR 值域的唯一权威枚举**）
  > 单 PR activity 取值：`REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/APPROVED`。Requirement 无 PR 为 `NO_PR`；多 PR 时 FAILED、CHANGES_REQUESTED 依次占优，否则全部相同返回该状态、全部 `APPROVED` 才 `APPROVED`，其余为 `MIXED` 并显示明细计数。

- **DEC:38**（D003）
  > Decision 只允许 `PENDING → APPROVE | REQUEST_CHANGES` 一次。……同 head 的 REQUEST_CHANGES 只能由新 head 解除，改 Base、关联或需求版本均不能绕过。
- **DEC:42**（D003 后果）
  > 旧 Review 永久保留但可被判定为已过期。
- **DEC:51**（D004 后果）
  > Requirement DONE 始终由 LEADER 人工确认，**不自动聚合多个 PR 的 Review 结果**。
- **DEC:78**（D007）
  > 关联变化不覆盖或取消历史 Review，也不自动重审；新上下文由有权限的人手动触发。
- **DEC:113**（D010 后果）：PG15 的两条硬依赖之一就是 Review 唯一键的 `NULLS NOT DISTINCT`。
- **DEC:429–438**（D016.2）：批次 3 建 `review` 后**必须**补上 P1 的 DEVELOPER 半条授权。

### 1.4 IMPLEMENTATION-PLAN.md

- **PLAN:70**
  > Review 身份为 `(pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`，当前有效性同时匹配四项输入；旧 Review 保留。
- **PLAN:73**
  > Review activity 覆盖 `REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/MIXED/APPROVED/NO_PR`。
- **PLAN:83**（Phase 7 退出）
  > 三角色可重复演示"需求→PR→Finding→退回→修复→新 Review→通过→DONE"；Revision/Diff 变化显示 `REVIEW_REQUIRED`。

### 1.5 已落地代码（批次 1 / 批次 2）

| 位置 | 事实 |
|---|---|
| `backend/src/main/java/com/forgepilot/requirement/RequirementStatus.java:4-10` | `DONE` **确实是** `requirement.status` 的枚举值（`DRAFT/READY/IN_DEVELOPMENT/DONE/CANCELED`），不是 Review 的值 |
| `.../requirement/RequirementService.java:42-47` | `ALLOWED_TARGETS` 把状态机**以数据形式编码**——批次 3 的 activity 矩阵应沿用同一形态 |
| `.../requirement/RequirementService.java:164-175` | `changeStatus` 当前只校验 `requireRole(LEADER)` + 转换表，**没有任何 Review 前置条件** |
| `.../requirement/RequirementDetail.java:14-19` | `reviewActivity` 字段已存在，批次 1 恒为常量 `"NO_PR"`，注释写明「derived, never stored and never writable」 |
| `.../requirement/RequirementSummary.java:11,17` | 列表接口同样已带 `reviewActivity` |
| `frontend/src/features/requirement/status.ts:9-10,20-22` | 前端 `ReviewActivity` 类型目前只有 `"NO_PR"`，标签表只有一项——批次 3 必须扩到 8 个 |
| `backend/src/test/java/com/forgepilot/requirement/RequirementLifecycleTest.java:352-360` | `refuse(...)` 逐对断言的写法（`as("%s -> %s", from, target)`），矩阵测试照此写 |
| `backend/src/main/resources/db/migration/V5__scm.sql:43-85` | `pull_request` 的**全部列**：无 `state` / `merged` / `closed` 列（见 §9 G4） |
| `backend/src/main/resources/db/migration/V5__scm.sql:49,54` | `head_sha` 与 `review_input_fingerprint` 均 `NOT NULL`；`requirement_id`（:63）可空 |
| `backend/src/main/java/com/forgepilot/requirement/Requirement.java:45-46` | `current_revision_id` 是**可空** `Long`（D013.10 的直接后果） |

---

## 2. 值域分两层：这是整份矩阵的地基

**原文明确规定**（DEC:122 + PRD:117）：

```text
单 PR 层（per pull_request）  : REVIEW_REQUIRED | FAILED | CHANGES_REQUESTED | REVIEWING | PENDING | APPROVED
需求层（per requirement）     : NO_PR | MIXED | 以及上面 6 个中的任意一个（当聚合坍缩到单值时）
```

- `NO_PR` 的判定输入是**关联 PR 的条数**，不是任何 `review` 行 —— PRD:117「Requirement **没有关联 PR** 时为 `NO_PR`」。
- `MIXED` 的判定输入是**多个 PR 子状态的集合**，同样不是任何单条 `review` 行。
- PLAN:73 把 8 个值写在一行，是**覆盖率清单**（"activity 覆盖这 8 个值"），不是"单 PR 有 8 种可能"。DEC:122 用「单 PR activity 取值：」逐字给出了 6 个，是消歧的权威句。

> **推导（标注）**：PLAN:73 与 DEC:122 的关系是「并集清单 vs 分层枚举」。这是我的推导，但两句原文不冲突，且 DEC:122 更具体（D011 是 review_activity 的专属决策），按「一件事实只在一个权威文档定义」（PLAN:13）应以 D011 为准。

---

## 3. 单 PR：六个值的充分必要条件

### 3.1 先定义两个记号

设待判定的 pull request 行为 `PR`，其**当前需求版本**记为：

```text
PR_REV := (PR.requirement_id IS NULL)
          ? NULL
          : (SELECT current_revision_id FROM requirement
             WHERE project_id = PR.project_id AND id = PR.requirement_id)
```

**当前有效 Review**（`CUR`）定义为满足下式的 `review` 行：

```sql
review.project_id       = PR.project_id
AND review.pull_request_id          = PR.id
AND review.head_sha                 = PR.head_sha
AND review.review_input_fingerprint = PR.review_input_fingerprint
AND review.requirement_revision_id IS NOT DISTINCT FROM PR_REV   -- NULL 亦须相等
```

- 前四行的等值比较是**原文明确规定**（ARCH:271 + PLAN:70）。
- 最后一行的 `IS NOT DISTINCT FROM` 是**原文明确规定**（ARCH:283 逐字写出该运算符并注明"NULL 亦须相等"）。
- `CUR` **至多一行**：**推导**，链条见 §6.3。

### 3.2 六条充要条件（列级表达）

| Activity | 充分必要条件 | 原文依据 | 性质 |
|---|---|---|---|
| `REVIEW_REQUIRED` | `COUNT(CUR) = 0` | PRD:110 "当前关联下没有匹配 head/fingerprint/revision 的 Review" | 规定 |
| `FAILED` | `CUR.status = 'FAILED'` | PRD:111 "当前 Review 执行失败" + ARCH:295 的 `FAILED` 执行态 | 规定（"执行失败"↔`status='FAILED'` 的映射为推导，见下） |
| `CHANGES_REQUESTED` | `CUR.decision = 'REQUEST_CHANGES'` | PRD:112 | 规定 |
| `REVIEWING` | `CUR.status = 'RUNNING'` **OR** (`CUR.status = 'COMPLETED'` **AND** `CUR.decision = 'PENDING'`) | PRD:113 "正在 RUNNING，或已 COMPLETED 但仍等待人工 Decision" | 规定 |
| `PENDING` | `CUR.status = 'PENDING'` | PRD:114 "当前 Review 为 PENDING" | 规定（"为 PENDING"指执行态而非 Decision，见 §3.4 陷阱） |
| `APPROVED` | `CUR.decision = 'APPROVE'` | PRD:115 | 规定 |

**唯一一处映射推导**：PRD:111 的中文是"执行失败"而不是 `status='FAILED'`。ARCH:288–299 的执行状态机里 `FAILED` 是唯一表示执行失败的状态，且 ARCH:295 写明其入口是"AI 失败或非法 JSON 修复无效"。因此 `执行失败 ≡ status='FAILED'`。推导链短且无竞争解释，但仍是推导。

### 3.3 具体行状态例子（每个值一组）

记号：`H1/H2` = head sha，`F1/F2` = fingerprint，`RA1/RA2` = 需求 A 的第 1/2 版 revision id。

| Activity | `pull_request` 行 | `review` 行（`head_sha, fingerprint, revision_id, status, decision`） |
|---|---|---|
| `REVIEW_REQUIRED` | `head=H1, fp=F1, requirement_id=A`（`A.current_revision_id=RA1`） | 该 PR 下**零行**，或只有 `(H1,F1,RA1)` 之外的行 |
| `FAILED` | 同上 | `(H1, F1, RA1, FAILED, PENDING)` |
| `CHANGES_REQUESTED` | 同上 | `(H1, F1, RA1, COMPLETED, REQUEST_CHANGES)` |
| `REVIEWING` (a) | 同上 | `(H1, F1, RA1, RUNNING, PENDING)` |
| `REVIEWING` (b) | 同上 | `(H1, F1, RA1, COMPLETED, PENDING)` |
| `PENDING` | 同上 | `(H1, F1, RA1, PENDING, PENDING)` |
| `APPROVED` | 同上 | `(H1, F1, RA1, COMPLETED, APPROVE)` |

### 3.4 三个分界线（题目点名的那三组）

#### (a) `PENDING` vs `REVIEWING` —— 分界线是**原子领取**

- `PENDING`：`status='PENDING'`，任务已落库但**尚未被任何 Worker 原子领取**（ARCH:293、ARCH:301 "领取必须是单条原子条件更新"）。
- `REVIEWING`：`status='RUNNING'`（已领取、持 token/lease），**或** `status='COMPLETED' AND decision='PENDING'`（跑完了但等人）。
- 分界线是那条 `PENDING → RUNNING` 的条件更新，不是时间、不是 lease。

> **⚠️ 派生陷阱（推导，且原文未规定 — 见 §9 G1）**：lease **已过期但 reconciliation 尚未跑**的 RUNNING 行，`status` 仍然是 `'RUNNING'`。按字面读法它显示 `REVIEWING`，而实际上没有任何 Worker 在跑。原文只按 `status` 定义 activity（PRD:113 只提 RUNNING/COMPLETED），**没有**说要看 `lease_until`。

> **⚠️ 三个 `PENDING` 的命名撞车（本条最容易写错）**：
> ```text
> activity        = 'PENDING'   ← 派生量的值
> review.status   = 'PENDING'   ← 执行状态
> review.decision = 'PENDING'   ← 人工决策未写入
> ```
> `activity='PENDING'` **只**由 `review.status='PENDING'` 决定；而 `review.decision='PENDING'` 出现在 `REVIEWING` 的第二个分支里。写成 `if (review.decision == PENDING) return PENDING;` 会让**所有**待决策的 COMPLETED Review 错报为 `PENDING`，把 `REVIEWING` 整个吃掉。建议在实现里用**不同的 Java 类型**（`ReviewStatus.PENDING` / `ReviewDecision.PENDING` / `ReviewActivity.PENDING`）而不是字符串。

#### (b) `MIXED` vs `CHANGES_REQUESTED` —— 分界线是**层级**，不是集合内容

见 §5。一句话：`CHANGES_REQUESTED` 在聚合里是**占优项**，只要有一个 PR 是它就赢；`MIXED` 是把占优项都排除之后的**残差**。二者永远不会在同一次比较里竞争。

#### (c) `REVIEW_REQUIRED` vs `NO_PR` —— 分界线是**有没有 PR 行**

| | `NO_PR` | `REVIEW_REQUIRED` |
|---|---|---|
| 层级 | 需求级 | 单 PR 级（也可能坍缩成需求级） |
| 判定输入 | `COUNT(pull_request WHERE requirement_id = 本需求) = 0` | 该 PR 存在，但 `COUNT(CUR) = 0` |
| 典型场景 | 需求刚建、还没人开 PR；或关联被清除 | Revision 刚发布 / head 刚推 / diff 刚变，还没重审（PRD:176、PLAN:83 要的就是这一格） |

> **⚠️ 易错**：`pull_request.requirement_id IS NULL` 的 PR（P1 的"未关联需求"）**不属于任何需求**，因此它**不会**让某个需求变成 `REVIEW_REQUIRED`，也**不会**让某个需求脱离 `NO_PR`。但它自己作为一个 PR 仍然有单 PR activity（`PR_REV = NULL`，与 `requirement_revision_id IS NULL` 的 Review 匹配）。这两件事必须分开算。

---

## 4. 判定顺序与互斥性

### 4.1 原文对"顺序"到底说了什么

| 层级 | 原文是否给出顺序 | 出处 |
|---|---|---|
| 单 PR 六值 | **没有**。PRD:108–115 是一张表，原文**从未**说"自上而下首个匹配胜出" | PRD:108–115 |
| 需求级聚合 | **给出了**，而且是逐字的："先让 `FAILED`、`CHANGES_REQUESTED` 两类风险状态**依次占优**" | PRD:117、DEC:122 |

### 4.2 单 PR：互斥性的推导链（这是 C3，请核对）

**主张**：在**可达**的 `(status, decision)` 组合上，六个条件两两互斥，因此判定顺序不影响结果。

推导链：

1. Decision 只能写到 `status='COMPLETED'` 的行上 —— ARCH:279 前置条件 1「目标 Review 的 `status = COMPLETED`」，且 ARCH:277 明令"禁止用普通 `EXISTS` 查询或无条件 save 代替"。
2. `COMPLETED` 是执行状态机的终态 —— ARCH:298 `COMPLETED --> [*]`，ARCH:303「`COMPLETED` 永不重跑或覆盖」。因此一旦 `decision ≠ PENDING`，`status` 恒为 `COMPLETED`。
3. `FAILED` 只能回到 `PENDING`（ARCH:296，复用同一行），期间 `decision` 从未被写过（由 1）。
4. 于是可达组合恰好是这 6 种：

   | # | `status` | `decision` | Activity | 命中的条件行 |
   |---|---|---|---|---|
   | 1 | `PENDING` | `PENDING` | `PENDING` | 仅 PRD:114 |
   | 2 | `RUNNING` | `PENDING` | `REVIEWING` | 仅 PRD:113 |
   | 3 | `COMPLETED` | `PENDING` | `REVIEWING` | 仅 PRD:113 |
   | 4 | `COMPLETED` | `APPROVE` | `APPROVED` | 仅 PRD:115 |
   | 5 | `COMPLETED` | `REQUEST_CHANGES` | `CHANGES_REQUESTED` | 仅 PRD:112 |
   | 6 | `FAILED` | `PENDING` | `FAILED` | 仅 PRD:111 |

   每一行**只**命中一条。第 7 种情况 `COUNT(CUR)=0` 与前 6 种在定义上互斥。

5. 不可达组合（必须由数据库拒绝或由实现显式炸掉，见 §9 G2）：
   `(PENDING|RUNNING|FAILED, APPROVE)`、`(PENDING|RUNNING|FAILED, REQUEST_CHANGES)` —— 共 6 种。

**这条推导会在什么情况下失效**（实现阶段必须守住）：
- 如果实现允许对非 `COMPLETED` 的 Review 写 Decision → 第 1 步崩，`FAILED` 与 `CHANGES_REQUESTED` 立刻可以同时成立。
- 如果实现允许 `COMPLETED → FAILED`（例如"重跑覆盖"）→ 第 2 步崩，同上。
- 如果 `CUR` 不唯一（唯一键写错，见 §6.3）→ 整个"当前 Review"是单数的前提崩。

### 4.3 建议的确定性顺序（**推导 / 防御性**，不是原文规定）

原文推不出唯一顺序，但可以推出「任何顺序在可达数据上等价」。为了让**不可达数据不产生静默错判**，建议：

```text
0. COUNT(CUR) = 0                                  -> REVIEW_REQUIRED
1. CUR.status = FAILED                             -> FAILED
2. CUR.decision = REQUEST_CHANGES                  -> CHANGES_REQUESTED
3. CUR.status = RUNNING
   OR (CUR.status = COMPLETED AND decision=PENDING) -> REVIEWING
4. CUR.status = PENDING                            -> PENDING
5. CUR.decision = APPROVE                          -> APPROVED
6. 以上皆不中                                       -> 抛异常（数据不可能到这里）
```

**为什么是这个顺序**：它就是 PRD:108–115 表格的行序。选它的唯一理由是"与权威文档的书写顺序一致，便于逐行对照"，**不是**因为原文规定了优先级。

**这个顺序在不可达数据上的后果**（供裁定时对比）：
- 上述顺序：`(FAILED, APPROVE)` 这种脏行会显示 `FAILED`（**风险优先**，fail-closed）。
- 反过来把 5 提到 1 之前：同一脏行会显示 `APPROVED`（**放宽**，fail-open）。
- **本项目一贯取向是 fail-closed**（D016.2:436「收窄授权范围永远比放宽安全」），所以表格顺序恰好也是安全的那一个。但请注意这是**巧合支持**，不是原文授权。

**开放项 O1**：是否在第 6 步抛异常（还是记 WARN 后返回某个兜底值）。抛异常会让一条脏数据打挂整个需求列表页；返回兜底值会掩盖不变式破坏。**不裁定**，见 §9。

### 4.4 需求级聚合：顺序是原文规定的

```text
if PRs.isEmpty()                          -> NO_PR                     (PRD:117 第一句)
else if ∃ p: sub(p) = FAILED              -> FAILED                    (PRD:117 "依次占优" 第一位)
else if ∃ p: sub(p) = CHANGES_REQUESTED   -> CHANGES_REQUESTED         (PRD:117 "依次占优" 第二位)
else if |distinct sub(p)| = 1             -> 该唯一值                   (PRD:117 "全部子状态相同就返回该状态")
else                                      -> MIXED + 各状态计数          (PRD:117 "其余组合返回 MIXED 并在 UI 展示各状态计数")
```

- 前三步的顺序：**原文明确规定**（"先……依次占优"）。
- 第 4、5 步：**原文明确规定**（"否则……其余组合"）。
- "全部 `APPROVED` 才返回 `APPROVED`"这句**在逻辑上被第 4 步蕴含**，是冗余强调。批次 1 的研究已记录过这处冗余（`archive/.../08-21-batch-1-.../research/requirement-contracts.md:533`）。两种读法结果相同，**不构成歧义**。

---

## 5. `MIXED` 到底是什么

### 5.1 全仓库出现位置（已穷举）

`grep -rn "MIXED"` 在 `docs/` + `.trellis/` + 代码中共命中 5 处：

| # | 位置 | 内容 | 权威性 |
|---|---|---|---|
| M1 | `docs/v2/PRD.md:117` | "其余组合返回 `MIXED` 并在 UI 展示各状态计数" | **权威（产品）** |
| M2 | `docs/v2/DECISIONS.md:122` | "其余为 `MIXED` 并显示明细计数" | **权威（决策）** |
| M3 | `docs/v2/IMPLEMENTATION-PLAN.md:73` | 8 值覆盖率清单 | 权威（只列名，不定义语义） |
| M4 | `.trellis/tasks/archive/2026-08/08-19-v2-plan-review-r2/prd.md:102` | "取值 `FAILED / CHANGES_REQUESTED / REVIEWING / PENDING / MIXED / APPROVED / NO_PR`，多 PR 按**该顺序**确定性归并" | **已废止的早期草案** |
| M5 | `.trellis/tasks/archive/2026-08/08-21-batch-1-.../research/requirement-contracts.md:364` | 转述，非一手 | 非权威 |

代码与前端：`MIXED` **零命中**（前端 `status.ts:10` 的 `ReviewActivity` 目前只有 `"NO_PR"`）。

### 5.2 确切含义（**可以推出来，不需要猜**）

结合 M1 + M2，并用 DEC:122 的单 PR 值域枚举作为消歧：

> **`MIXED` 是需求级聚合的残差值：当且仅当该需求关联的 PR 数 ≥ 2、没有任何 PR 处于 `FAILED`、没有任何 PR 处于 `CHANGES_REQUESTED`、且剩余子状态不全相同时返回。**

形式化（可直接翻译成断言）：

```text
MIXED  ⟺  |PRs| ≥ 2
       ∧  FAILED ∉ {sub(p)}
       ∧  CHANGES_REQUESTED ∉ {sub(p)}
       ∧  |distinct {sub(p)}| ≥ 2
```

推论（全部可直接写成测试）：
- **单个 PR 的需求永远不会是 `MIXED`**（`|distinct| = 1` 必然成立）。
- `MIXED` 的成分只可能来自 `{REVIEW_REQUIRED, REVIEWING, PENDING, APPROVED}` 这 4 个值 —— 另外两个已被占优规则吃掉。因此**最小的 `MIXED` 例子是两个 PR、两个不同的非风险状态**，例如 `{PENDING, APPROVED}`。
- `MIXED` **必须**伴随各状态计数一起返回（M1「在 UI 展示各状态计数」、M2「显示明细计数」）。这是对 API 响应体的硬要求，不是 UI 自由发挥：响应里必须有一个 `counts` 映射，否则前端无法满足这条。

### 5.3 M4 是一个真实的踩坑源，必须显式作废

`08-19-v2-plan-review-r2/prd.md:102` 把 `MIXED` 写在一条**线性优先级链**里（`FAILED > CHANGES_REQUESTED > REVIEWING > PENDING > MIXED > APPROVED > NO_PR`，"多 PR 按该顺序确定性归并"）。按那个读法：

- `{PENDING, APPROVED}` → `PENDING`（不是 `MIXED`）
- `{REVIEWING, APPROVED}` → `REVIEWING`（不是 `MIXED`）
- `MIXED` 几乎永远不可达

这与现行 PRD:117 的结果**直接冲突**。M4 已被 R2.3 契约加固任务取代——该任务的 `prd.md:16` 逐字要求"明确单 PR 映射、多 PR 聚合及 MIXED 测试矩阵"，`design.md:42` 要求"为单 PR 状态和多 PR 聚合定义确定性映射"，产出即现行 PRD:117。

> **⚠️ 给实现者**：`.trellis/tasks/archive/` 下的 M4 仍然可被 grep 到。如果有人按它实现，测试矩阵里的 A8/A9 会失败而且看起来"像是矩阵写错了"。**M4 已废止，以 PRD:117 / DEC:122 为准。**

### 5.4 `MIXED` 有没有推不出来的部分？

有一处，**规格缺口 G3**：`MIXED` 的**计数明细覆盖哪些值**。M1/M2 说"各状态计数 / 明细计数"，但没说是"仅出现的状态"还是"全部 6 个单 PR 值（含 0）"。两种都能满足字面。后果差异见 §9 G3。**不裁定。**

---

## 6. Review 身份四元组、当前有效性与唯一约束

### 6.1 「当前有效」四项各自匹配什么

| 身份分量 | 与什么比较 | 比较运算 | 依据 |
|---|---|---|---|
| `pull_request_id` | 就是这个 PR 行的 `id` | `=` | ARCH:270 |
| `head_sha` | `pull_request.head_sha`（PR 行**当前值**） | `=` | ARCH:271、ARCH:281 |
| `review_input_fingerprint` | `pull_request.review_input_fingerprint`（PR 行**当前值**） | `=` | ARCH:271、ARCH:282 |
| `requirement_revision_id` | PR **当前关联需求**的 `current_revision_id` | **`IS NOT DISTINCT FROM`** | ARCH:283（逐字给出运算符） |

注意第 4 行的两跳：`pull_request.requirement_id` → `requirement.current_revision_id`。`pull_request` 表上**没有** `requirement_revision_id` 列（V5__scm.sql:43-85 已核实），所以"PR 当前需求版本"必须 join 出来。这一跳是**推导**，但唯一可能：PRD:106 说"当前需求版本"，而"当前版本"在数据模型里只有 `requirement.current_revision_id` 一个表示（ARCH:93）。

### 6.2 `requirement_revision_id` 为 NULL 时怎么算（NULL 陷阱）

**原文明确规定**，不需要推导：ARCH:283 逐字写着

> `review.requirement_revision_id IS NOT DISTINCT FROM pull_request` 当前关联需求版本（**NULL 亦须相等**）

因此：

```sql
-- 正确
AND review.requirement_revision_id IS NOT DISTINCT FROM :prCurrentRevisionId

-- 错误（经典陷阱）：PR 未关联需求时 NULL = NULL 求值为 UNKNOWN，
-- 整行过滤为假，COUNT(CUR) 恒为 0，"未关联的 PR" 永远显示 REVIEW_REQUIRED，
-- 无论已经跑过多少次 Review。而且它会一直"看起来像是没审"，人工触发也无法消除。
AND review.requirement_revision_id = :prCurrentRevisionId
```

Java/JPA 侧的等价陷阱同样要防：`Objects.equals(a, b)` 是 NULL-safe 的（对），`a.equals(b)` 在 `a == null` 时 NPE（错），`a == b` 对 `Long` 装箱对象在大数值上为假（错）。**建议在 Repository 里用原生/JPQL 的 `IS NOT DISTINCT FROM` 让数据库判定，而不是把行捞回来在 Java 里比。**

同一条 NULL 语义在**写入侧**由 `UNIQUE NULLS NOT DISTINCT` 保证（ARCH:102），这是 PG15 硬依赖的两条之一（DEC:113、ARCH:452）。**读写两侧用的是配对的语义**，这不是巧合，实现时不能只做一侧。

### 6.3 「至多一条当前有效 Review」的推导（C3 的前提）

1. 当前有效性把四个分量**全部**钉死在 PR 的当前值上（§6.1）。
2. 唯一约束 `UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`（ARCH:102）覆盖的正是**同一组四列**。
3. `NULLS NOT DISTINCT` 让 NULL 也参与唯一性判定，因此 `requirement_revision_id IS NULL` 的行同样只能有一条。
4. ⇒ **满足当前有效性的行至多一条。**

这条推导使 PRD 表格里的"当前 Review"作为单数名词成立。**如果唯一键写成了默认的 `NULLS DISTINCT`，第 3 步崩**：未关联需求的 PR 可以堆积任意多条同四元组的 Review，"当前 Review"变成集合，整张单 PR 映射表失去意义，且 ARCH:261 的"幂等创建或取得"也一并失效。

### 6.4 「旧 Review 保留」与唯一约束如何共存

**问题**：同一个 PR 会有多行 review，唯一约束怎么加才不挡住合法的新 Review？

**答案（原文明确规定，ARCH:102）**：唯一键是**四元组**，不是 `(pull_request_id)`，也不是 `(pull_request_id, head_sha)`。因此：

| 变化 | 四元组是否改变 | 新 Review 能否插入 |
|---|---|---|
| 推了新 head SHA | `head_sha` 变 → 变 | ✅ 能 |
| head 不变但 base/changed files/patch 变 | `review_input_fingerprint` 变 → 变 | ✅ 能（ARCH:275 明确要求这条必须成立） |
| LEADER 发布了新 Revision | `requirement_revision_id` 变 → 变 | ✅ 能 |
| 关联从需求 A 改到需求 B | `requirement_revision_id` 变 → 变 | ✅ 能 |
| 关联被清除 | `requirement_revision_id` → NULL，仍是"变" | ✅ 能（靠 `NULLS NOT DISTINCT` 保证不会重复插） |
| 同一组输入重复触发 / Webhook 重放 | 不变 | ❌ 挡住 —— **这正是 ARCH:261 要的幂等** |
| FAILED 后人工重试 | 不变，且**复用同一行**（PRD:145 的 P4、ARCH:296、ARCH:303） | ❌ 不插新行，`UPDATE` 原行 |

**结论：唯一约束照 ARCH:102 原样落地即可，不需要放宽，也不能收紧。**收紧成 `(pull_request_id, head_sha)` 会让 D003 的 fingerprint 维度失效（ARCH:480 的风险行就白写了）；放宽成加 `id` 之类会毁掉幂等。

### 6.5 三种不同的查询形状（实现时别混用同一个索引假设）

| 用途 | 谓词 | 依据 |
|---|---|---|
| 幂等创建 / 当前有效性 | 四元组全等（NULL-safe） | ARCH:261、ARCH:271 |
| **Decision Gate** | `pull_request_id = ? AND head_sha = ? AND decision = 'REQUEST_CHANGES'`，**忽略 fingerprint 与 revision** | ARCH:272、ARCH:284「该 `head_sha` 上不存在**任何** `REQUEST_CHANGES`」 |
| 历史列表 / 已过期展示 | `pull_request_id = ?`，全部行 | ARCH:346–347 |

Decision Gate 只认两列，这是 D003 反复强调的"改 Base、关联或需求版本都不能解除退回"（DEC:38、PRD:136）。**它与当前有效性是不同的谓词，不要复用同一个方法。**

### 6.6 `review.requirement_id` 要不要进当前有效性判定？（**开放项 O2**）

ARCH:271 / PLAN:70 只列了 head / fingerprint / revision 三项 + PR 本身。`requirement_id` 没有单列。

- **推导**：`requirement_revision_id` 是 `requirement_revision` 的主键，全局唯一，且 `review` 的复合外键 `(project_id, requirement_id, requirement_revision_id) -> requirement_revision(project_id, requirement_id, id)`（ARCH:172–173）强制两者一致。所以匹配了 revision 就蕴含匹配了 requirement。**在不变式成立时，加不加等价。**
- **但不变式有一个已知裂缝**：`requirement.current_revision_id` 是**可空**列（D013.10:223 明确写着"数据库无法证明每个已提交的需求都有 current revision"，`Requirement.java:45-46` 也确实是可空 `Long`）。若某需求的 `current_revision_id` 意外为 NULL，则 `PR_REV = NULL`，于是一条 `requirement_revision_id IS NULL`（即"未关联需求"）的旧 Review 会被判为**当前有效**，进而把一个已关联需求的 PR 显示成一次"无需求上下文的审查"的结论。
- 候选方案：
  - **(a) 严格照抄原文三项**：实现最简，与 ARCH:271 逐字一致；裂缝暴露时静默错判。
  - **(b) 三项 + `review.requirement_id IS NOT DISTINCT FROM pull_request.requirement_id`**：多一列比较，堵住裂缝；但比原文多了一个条件，属**未授权的加严**。
  - **(c) 三项 + 在读路径断言 `PR.requirement_id IS NOT NULL ⟹ PR_REV IS NOT NULL`，否则显式失败**：把裂缝变成响亮的失败而不是错判；符合 D015.5 / P6「绝不生成假成功」的取向。
- **不裁定。** 设计阶段必须选一个并写进 `design.md`。

---

## 7. 与 `requirement.status` 的关系：`DONE` 由谁、在什么条件下

### 7.1 `DONE` 确实是 `requirement.status` 的值

- `RequirementStatus.java:4-10` 逐字：`DRAFT, READY, IN_DEVELOPMENT, DONE, CANCELED`。
- PRD:99 的状态图、DEC:118 的 D011 一致。
- `review` 侧**没有** `DONE`：Review 的执行状态是 `PENDING/RUNNING/COMPLETED/FAILED`（ARCH:290–299），Decision 是 `PENDING/APPROVE/REQUEST_CHANGES`（PRD:135）。
- ⇒ PLAN:83 的"→通过→**DONE**"是**跨维度**的一句话：`通过` 是 `review.decision = APPROVE`，`DONE` 是 `requirement.status = DONE`。两者之间**没有**自动边。

### 7.2 由谁：**LEADER**，四处原文一致

| 出处 | 原文 |
|---|---|
| PRD:103 | `→ DONE` 由 LEADER 确认全部关联工作完成。**AI、Webhook、PR、Review 一律不得推进这些状态。** |
| PRD:150（P9） | 单个 PR APPROVE 只结束当前 Review；Requirement DONE **必须由 LEADER** 在确认全部关联工作完成后执行 |
| PRD:174 | Reviewer APPROVE 只完成当前 Review，**LEADER 确认后**再将需求置 DONE |
| DEC:51（D004 后果） | Requirement DONE 始终由 LEADER 人工确认，**不自动聚合多个 PR 的 Review 结果** |
| PRD:47–61 角色表 | 需求状态相关动作整列只有 LEADER 打勾 |

**这一条没有任何歧义。** 代码侧也已成立：`RequirementService.changeStatus:167` 是 `access.requireRole(projectId, actorId, ProjectRole.LEADER)`。

### 7.3 在什么条件下：**规格缺口 G5**

原文给的条件是 **"确认全部关联工作完成"** —— 这是一句**人的判断**，不是可机检的谓词。原文**从未**规定 `DONE` 需要任何 review 前置条件。

现状（已落地，`RequirementService.java:42-47` + `:164-175`）：

```text
DONE 的可达条件 = actor 是 LEADER  ∧  当前 status = IN_DEVELOPMENT
```

`RequirementLifecycleTest.java:216-217` 已经在**没有任何 PR / Review**的情况下成功把需求置为 DONE，也就是说批次 1 就已经把"无前置条件"这一读法固化进了测试。

**批次 3 是否要加前置条件，是必须裁定的开放项。** 候选：

| 候选 | 内容 | 支持它的原文 | 反对它的原文 | 后果 |
|---|---|---|---|---|
| **D-a 不加**（保持现状） | LEADER 在 IN_DEVELOPMENT 时可随时置 DONE | PRD:103「Review 一律不得推进这些状态」；DEC:51「不自动聚合」；ARCH:424「两个正交维度」 | PLAN:83 的演示链"通过→DONE"读起来像有因果 | 演示链靠**流程**而非**约束**成立；LEADER 可以在 CHANGES_REQUESTED 未解决时置 DONE |
| **D-b 软提示** | 不拦截，但 activity ≠ APPROVED 时在 UI 上警告 | ARCH:424 正交；PRD:117 并列展示 | 无 | 零结构改动，保留 LEADER 权威；提示强度是 UI 决策 |
| **D-c 硬闸门** | 要求 `review_activity = APPROVED` 才允许 DONE | PLAN:83 的演示链 | **PRD:103「Review 一律不得推进这些状态」**、DEC:51「不自动聚合多个 PR 的 Review 结果」、ARCH:424「不得合并为一个标签」 | 让派生量反向约束持久状态，与 D011 的正交性设计**直接冲突**；且 `NO_PR` 的需求将永远无法 DONE（例如纯文档需求） |

> **我的读法（标注为推导，不是裁定）**：D-c 与 PRD:103 冲突得比较硬 —— 但要注意"推进（advance）"与"约束（guard）"严格说不是一回事，一个闸门并不推进状态。所以 D-c 不是**被字面禁止**，只是**与 D011 的设计意图相悖**。D-a/D-b 与全部原文相容。**请设计阶段裁定并落成决策，不要由实现自行选择。**

### 7.4 Phase 7 演示链的完整状态轨迹（可直接当验收脚本）

| 步骤 | `requirement.status` | 该 PR 的 activity | 需求级 activity | 推进者 |
|---|---|---|---|---|
| 1. LEADER 建需求 + AC | `DRAFT` | — | `NO_PR` | LEADER |
| 2. `DRAFT → READY` | `READY` | — | `NO_PR` | LEADER |
| 3. 首次指派 | `IN_DEVELOPMENT` | — | `NO_PR` | LEADER（同事务，PRD:103） |
| 4. 开发者开 `feat/REQ-<n>-*` PR，自动解析关联 | `IN_DEVELOPMENT` | `PENDING` | `PENDING` | SCM（同事务建 PENDING Review，ARCH:261） |
| 5. 执行器领取 | `IN_DEVELOPMENT` | `REVIEWING` | `REVIEWING` | 执行器（ARCH:293） |
| 6. 引擎产出通过校验 | `IN_DEVELOPMENT` | `REVIEWING`（COMPLETED 等人） | `REVIEWING` | 执行器 |
| 7. Reviewer 确认部分 Finding 并 `REQUEST_CHANGES` | `IN_DEVELOPMENT` | `CHANGES_REQUESTED` | `CHANGES_REQUESTED` | REVIEWER/LEADER |
| 8. 开发者推新 head SHA | `IN_DEVELOPMENT` | `PENDING`（**新** Review 行） | `PENDING` | SCM |
| 9. 新 Review 完成 | `IN_DEVELOPMENT` | `REVIEWING` | `REVIEWING` | 执行器 |
| 10. Reviewer `APPROVE` | `IN_DEVELOPMENT` ← **不变** | `APPROVED` | `APPROVED` | REVIEWER/LEADER |
| 11. LEADER 确认全部工作完成 | **`DONE`** | `APPROVED`（不变） | `APPROVED` | **LEADER**（PRD:174） |

**第 10 步 status 不变**是 PRD:150 / DEC:51 的直接要求，也是这条链上最容易被实现"顺手优化掉"的一格。

**PLAN:83 的第二半**"Revision/Diff 变化显示 `REVIEW_REQUIRED`"对应在任意时刻插入：
- 10' LEADER 发布新 Revision → activity 由 `APPROVED` 立刻变 `REVIEW_REQUIRED`（`requirement_revision_id` 不再匹配）；
- 10'' PR 的 base 或 patch 变化但 head 不变 → `review_input_fingerprint` 变 → 同样 `REVIEW_REQUIRED`（这是 D003 最想证明的那一格，ARCH:480）。

---

## 8. 测试矩阵（以数据形式编码，逐行可断言）

写法参照 `RequirementService.java:42-47` 的 `ALLOWED_TARGETS`（状态机即数据）与 `RequirementLifecycleTest.java:352-360` 的 `refuse(...)`（逐对断言、失败信息里带上这一对）。

### 8.1 单 PR 矩阵（`SinglePullRequestActivityTest`）

固定 PR 行：`head_sha=H1`, `review_input_fingerprint=F1`, `requirement_id=A`，且 `A.current_revision_id = RA1`（除非该行另注）。
`review` 行写作 `(head, fp, rev, status, decision)`。

| ID | PR 行偏离 | `review` 行 | 期望 | 这行在证明什么 |
|---|---|---|---|---|
| **S1** | — | **无任何行** | `REVIEW_REQUIRED` | PRD:110 正例 |
| **S2** | — | `(H1,F1,RA1,FAILED,PENDING)` | `FAILED` | PRD:111 正例 |
| **S3** | — | `(H1,F1,RA1,COMPLETED,REQUEST_CHANGES)` | `CHANGES_REQUESTED` | PRD:112 正例 |
| **S4** | — | `(H1,F1,RA1,RUNNING,PENDING)` | `REVIEWING` | PRD:113 前半 |
| **S5** | — | `(H1,F1,RA1,COMPLETED,PENDING)` | `REVIEWING` | PRD:113 后半 —— **不是** `PENDING` |
| **S6** | — | `(H1,F1,RA1,PENDING,PENDING)` | `PENDING` | PRD:114 正例 |
| **S7** | — | `(H1,F1,RA1,COMPLETED,APPROVE)` | `APPROVED` | PRD:115 正例 |

### 8.2 边界矩阵（**最容易写错的那些**）

| ID | PR 行 | `review` 行 | 期望 | 写错会怎样 |
|---|---|---|---|---|
| **B1** ⭐ | `head=H1, fp=F1, requirement_id=NULL` | `(H1,F1,**NULL**,COMPLETED,APPROVE)` | `APPROVED` | 用 `=` 而非 `IS NOT DISTINCT FROM` → 恒为 `REVIEW_REQUIRED`，**未关联需求的 PR 永远算不出当前有效 Review**（§6.2） |
| **B2** ⭐ | `head=H1, fp=**F2**, requirement_id=A→RA1` | `(H1,F1,RA1,COMPLETED,APPROVE)` | `REVIEW_REQUIRED` | 只比 head 不比 fingerprint → head 没变但 diff 变了却仍显示 `APPROVED`，D003/ARCH:480 那条风险直接复发 |
| **B3** ⭐ | `head=H1, fp=F1, requirement_id=A→**RA2**` | `(H1,F1,RA1,COMPLETED,APPROVE)` | `REVIEW_REQUIRED` | 不比 revision → PLAN:83「Revision 变化显示 REVIEW_REQUIRED」失效 |
| **B4** ⭐ | `head=**H2**, fp=F1, A→RA1` | `(H1,F1,RA1,COMPLETED,REQUEST_CHANGES)` | `REVIEW_REQUIRED` | 新 head 后仍显示 `CHANGES_REQUESTED` → 开发者以为修复没生效 |
| **B5** ⭐ | `head=H1, fp=F1, requirement_id=**NULL**` | `(H1,F1,**RA1**,COMPLETED,APPROVE)` | `REVIEW_REQUIRED` | NULL-safe 比较写反（把 `NULL` 当通配）→ 关联被清除后仍沿用旧结论 |
| **B6** | `head=H1, fp=F1, A→RA1` | `(H1,F1,**NULL**,COMPLETED,APPROVE)` | `REVIEW_REQUIRED` | B5 的镜像：Review 当时未关联需求，现在 PR 关联了 |
| **B7** | `head=H1, fp=F1, A→RA1` | **两行**：`(H1,F1,RA1,COMPLETED,APPROVE)` + `(H1,F1,RA2,COMPLETED,REQUEST_CHANGES)` | `APPROVED` | 「旧 Review 保留」（ARCH:275）不得污染当前判定；若按 `created_at DESC LIMIT 1` 取最新会得到 `CHANGES_REQUESTED` |
| **B8** | `head=H1, fp=F1, A→RA1` | 先 `(H1,F1,RA1,FAILED,PENDING)`，人工重试后**同一行**变 `(H1,F1,RA1,PENDING,PENDING)` | 重试前 `FAILED`，重试后 `PENDING`，且该 PR 的 review **行数仍为 1** | 重试若插新行会撞唯一键（ARCH:102），也违反 P4「FAILED 重试复用同一行」 |
| **B9** | `head=H1, fp=F1, A→RA1`；同一 head 上另有一条 `(H1,F2,RA1,COMPLETED,REQUEST_CHANGES)` | `(H1,F1,RA1,COMPLETED,PENDING)` | activity = `REVIEWING`，**但 Decision Gate 必须拒绝 APPROVE**（ARCH:284） | 把 activity 判定和 Decision Gate 复用同一个谓词 → 允许了本该被闸门挡住的 APPROVE |
| **B10** | 另一个项目的 PR / review 行使用相同 id | — | 本项目查询结果不受影响 | 读路径漏带 `projectId`（ARCH:137「禁止裸 id 查询后再补权限判断」） |

⭐ = 题目要求的「最容易写错的 5 组边界」（B1–B5）。B9 额外重要，因为它是**唯一一格 activity 与 Decision Gate 给出不同答案**的合法数据。

### 8.3 需求级聚合矩阵（`RequirementActivityAggregationTest`）

| ID | 关联 PR 的子状态多重集 | 期望 | 依据 / 这行在证明什么 |
|---|---|---|---|
| **A1** | `{}`（零个 PR） | `NO_PR` | PRD:117 第一句 |
| **A2** | `{APPROVED}` | `APPROVED` | 单 PR 直接坍缩，**永不 MIXED** |
| **A3** | `{REVIEW_REQUIRED}` | `REVIEW_REQUIRED` | 单 PR 坍缩到非终局值 |
| **A4** | `{FAILED, APPROVED}` | `FAILED` | `FAILED` 占优第一位 |
| **A5** | `{FAILED, CHANGES_REQUESTED}` | `FAILED` | 两个风险值同时在场时 `FAILED` 先 |
| **A6** ⭐ | `{CHANGES_REQUESTED, APPROVED}` | **`CHANGES_REQUESTED`** | **不是 `MIXED`** —— 题目点名的混淆点，也是 M4 废案与现行规格的分歧点 |
| **A7** | `{CHANGES_REQUESTED, REVIEW_REQUIRED, PENDING}` | `CHANGES_REQUESTED` | 占优规则吃掉一切残差 |
| **A8** | `{APPROVED, APPROVED}` | `APPROVED` | "全部 APPROVED 才 APPROVED" |
| **A9** | `{PENDING, PENDING}` | `PENDING` | "全部子状态相同就返回该状态"，**不是 MIXED** |
| **A10** ⭐ | `{PENDING, APPROVED}` | `MIXED` + counts `{PENDING:1, APPROVED:1}` | **最小的 MIXED 例子** |
| **A11** | `{REVIEW_REQUIRED, REVIEWING, APPROVED}` | `MIXED` + counts `{REVIEW_REQUIRED:1, REVIEWING:1, APPROVED:1}` | MIXED 的三元例，验证计数明细 |
| **A12** | `{REVIEW_REQUIRED, REVIEW_REQUIRED}` | `REVIEW_REQUIRED` | 全同的非终局值同样坍缩 |
| **A13** | 该需求有 2 个 PR，另有 1 个 `requirement_id IS NULL` 的 PR | 只按那 2 个算 | 未关联 PR 不进入任何需求的聚合（§3.4c） |
| **A14** | B 项目某需求有 3 个 PR；A 项目同 id 需求有 0 个 | A 项目看到 `NO_PR` | 跨项目隔离（ARCH:137） |

⭐ = 聚合层最容易写错的两格。

### 8.4 建议的编码形态（与 `ALLOWED_TARGETS` 同型）

```java
// 单 PR：把 (status, decision) -> activity 写成数据，而不是 if 链。
// 只列可达组合；不可达组合的处理由 §9 O1 裁定后补。
private static final Map<ReviewStatus, Map<ReviewDecision, ReviewActivity>> SINGLE = ...

// 聚合：把 A1..A14 写成一张 List<record Case(List<ReviewActivity> subs,
//       ReviewActivity expected, Map<ReviewActivity,Integer> expectedCounts)>，
//       测试里 for-each 并用 as("%s -> %s", subs, expected) 命名，
//       失败信息直接指出是哪一组（照抄 RequirementLifecycleTest:352-360 的写法）。
```

---

## 9. 规格缺口清单（**原文没规定的，一律不裁定**）

| # | 缺口 | 原文为什么不够 | 候选方案与后果 |
|---|---|---|---|
| **G1** | **lease 已过期但未被 reconciliation 回收的 RUNNING 行显示什么** | PRD:113 只说"正在 RUNNING"，只提 `status`，未提 `lease_until`。ARCH:297 承认这种行存在（`RUNNING --> PENDING: lease 过期后 reconciliation 恢复`） | (a) 字面：仍显示 `REVIEWING`。实现最简、与原文逐字一致；用户看到"正在审查"但其实没人在跑，窗口长度 = reconciliation 周期。<br>(b) lease-aware：`lease_until < now()` 时显示 `PENDING`。更诚实；但引入了原文没有的判定输入，且让 activity 依赖时钟（同一行在不同时刻返回不同值，测试要冻结 Clock）。 |
| **G2** | **不可达 `(status, decision)` 组合的处理** | 原文只保证这些组合写不进去（ARCH:279），没说读到了怎么办 | (a) 加数据库 CHECK：`decision='PENDING' OR status='COMPLETED'`。把不可达变成不可存，最彻底；但 ARCH:214–220 的 CHECK 清单里**没有**这一条，加它属**扩充 §2.1 的约束清单**，按 PLAN:116 需要决策。<br>(b) 只在读路径抛异常（§4.3 第 6 步）。零 schema 改动；脏行会打挂页面。<br>(c) 记 WARN + 返回 `FAILED`（fail-closed 兜底）。页面不挂；不变式破坏被静默。 |
| **G3** | **`MIXED` 的 counts 覆盖哪些键** | PRD:117「展示各状态计数」/ DEC:122「显示明细计数」两种读法都成立 | (a) 只含出现过的状态（稀疏 map）。响应小；前端要处理缺键。<br>(b) 6 个单 PR 值全给，未出现的为 0（稠密 map）。前端简单、类型稳定；响应略大。<br>**附加约束**：无论选哪个，`MIXED` 时 counts **必须**存在，否则违反 PRD:117。是否在非 `MIXED` 时也返回 counts，同样未规定。 |
| **G4** ⚠️ | **`pull_request` 没有 state/merged/closed 列 —— 已合并或已关闭的 PR 永远计入聚合** | ARCH:100 的 `pull_request` 列清单与 `V5__scm.sql:43-85` 都没有这一列；PRD:117 说"关联 PR"，没说"开着的关联 PR" | (a) 照字面：全部关联 PR 计入。零改动；**后果是一个被关闭（未合并）的 PR 会把需求永久钉在 `CHANGES_REQUESTED` 或 `REVIEW_REQUIRED`，LEADER 无法通过任何操作让它变绿**，除非清除关联。<br>(b) 加 `state` 列并只算 open。是**扩充 §2.1 列清单**，按 PLAN:116 / D016.1 的先例需要正式决策。<br>(c) 允许清除关联作为逃生口（P1 已授权 LEADER 随时改关联，D007）。零改动，但审计上把"这个 PR 曾属于该需求"抹成了一条 event。<br>**这条对 Phase 7 演示的影响是真实的**：演示里若开了一个废弃 PR 又关掉，需求会一直不显示 `APPROVED`。 |
| **G5** | **`DONE` 是否需要 review 前置条件** | 见 §7.3 完整分析 | D-a 不加 / D-b 软提示 / D-c 硬闸门，三者的原文支持与后果见 §7.3 表 |
| **G6** ⚠️ | **关联"改走再改回"会让旧 Review 复活为当前有效，可能与 Decision Gate 互相矛盾** | 当前有效性是**纯比较**（ARCH:271），没有任何"一旦失效就永久失效"的措辞；D007（DEC:78）明确"关联变化不覆盖或取消历史 Review" | 场景：PR head `H1`、fp `F1`。① 关联需求 A(rev `RA1`) → Review `R1` `APPROVE`。② LEADER 改关联到 B(rev `RB1`) → activity 变 `REVIEW_REQUIRED` → 人工重审得 `R2` `REQUEST_CHANGES`。③ LEADER 又改回 A。<br>**结果**：`R1` 重新满足当前有效性 → activity 显示 `APPROVED`；但 Decision Gate 看 `(PR, H1)` 上存在 `REQUEST_CHANGES`（来自 `R2`），**该 head 已被永久锁死，不能再 APPROVE**（ARCH:284、DEC:38）。页面说"已通过"，闸门说"这个 head 不能通过"。<br>候选：(a) 承认这是两个维度、照字面实现，UI 同时展示"该 head 已有退回"标记；(b) 让 activity 也吸收 Decision Gate（若 `(PR, head)` 上有任何 `REQUEST_CHANGES` 则 activity 至少为 `CHANGES_REQUESTED`）——**这会改变 PRD:112 的充要条件，属改规格**；(c) 限制关联回改。<br>**不裁定。** |
| **G7** | **activity 是否也出现在 PR / Review 的响应体上** | PRD:106 只定义了需求侧；ARCH:412–418 的路由里有 `/reviews` 与 `/reviews/:id`；PLAN:76 要求"Review 详情只读页可用"；批次 1 只把 `reviewActivity` 放在 `RequirementDetail`/`RequirementSummary` | (a) 只在需求侧：与批次 1 契约一致，`/reviews` 页自己展示 `status`+`decision`+"已过期"标记（ARCH:346 的原生说法）。<br>(b) PR 侧也返回单 PR activity：一个概念一处计算，但需要在 `PullRequestResponse` 加字段。<br>**注意**：`NO_PR` 与 `MIXED` **不得**出现在 PR 级字段上（§2）。 |
| **G8** | **activity 的计算位置与模块依赖** | 需求侧要展示 activity，但 `requirement` **不依赖** `scm`/`review`（ARCH:56–62，D015.6 只放开了 `scm → requirement` 的反方向） | (a) 由 `review` 计算并经 Query facade 提供，`requirement` 的 Controller 组装 —— 但 `RequirementDetail.of(...)` 目前在 `requirement` 内部填 `NO_PR`（`RequirementDetail.java:16-20`），需要重构。<br>(b) 由一个 `review` 侧的读接口直接返回"需求 id → activity"映射，前端两次请求。<br>**这条是模块边界问题，不是本研究的范围，但它会决定 §8 的测试放在哪个包**，必须在 `design.md` 里定。 |

---

## 10. 未回答的问题 / 假设 / 必须裁定的开放项

### 10.1 我做的假设（若不成立，本文结论要重算）

| # | 假设 | 依赖它的结论 |
|---|---|---|
| H1 | `review` 表的执行状态值域恰为 `PENDING/RUNNING/COMPLETED/FAILED` | §3.2 全部；§4.2 的可达组合表 |
| H2 | `review` 的唯一键会**逐字**按 ARCH:102 写成 `UNIQUE NULLS NOT DISTINCT (...)` | §6.3「至多一条当前有效 Review」→ §4.2 互斥性 → 整张单 PR 矩阵 |
| H3 | Decision 只能写到 `status='COMPLETED'` 的行上，且 `COMPLETED` 是终态 | §4.2 互斥性 |
| H4 | "PR 当前需求版本" = `requirement.current_revision_id`（两跳 join） | §6.1、B3、B5、B6 |
| H5 | 需求级聚合的 PR 集合 = `pull_request WHERE project_id=? AND requirement_id=?`，不作任何其它过滤 | §4.4、A13、G4 |

### 10.2 设计阶段必须裁定的开放项（按重要性排序）

| # | 开放项 | 为什么必须在写代码前定 |
|---|---|---|
| **O1** | G5：`DONE` 是否加 review 前置条件 | 直接决定 `RequirementService.changeStatus` 要不要跨模块问 `review`，进而决定 `requirement` 的依赖方向（G8） |
| **O2** | G8：activity 在哪个模块算、放哪个响应体 | 决定 §8 的测试落在哪个包、`RequirementDetail` 要不要改签名（前端 `api.ts` 一并受影响） |
| **O3** | G4：已关闭 / 已合并的 PR 是否计入聚合 | 若要加 `state` 列，就是**扩充 §2.1 列清单**，必须先出决策（D016.1 是先例） |
| **O4** | G6：关联回改导致的 activity / Decision Gate 矛盾 | 影响 PRD:112 的充要条件是否需要修订；也影响 B9 的期望值 |
| **O5** | §6.6 O2：`review.requirement_id` 是否进当前有效性 | 影响 `CUR` 的 SQL 谓词形状，写完再改要动索引 |
| **O6** | G1：lease 过期的 RUNNING 显示什么 | 影响 activity 是否依赖时钟，进而影响测试是否需要冻结 `Clock` |
| **O7** | G2：不可达组合的处理（CHECK / 抛异常 / 兜底） | 若选 CHECK 就要改迁移，必须与 `review` 建表同一份 `V6` 一起写，不能追加 |
| **O8** | G3：`MIXED` 的 counts 是稀疏还是稠密 | 影响 API 契约与前端类型（`status.ts` 要同步扩到 8 值 + counts 类型） |

### 10.3 本研究**没有**回答的（超出委托范围）

- `review` / `finding` / `finding_event` 三张表的**完整列定义与迁移写法**（fencing 列的确切名字/类型另有研究）。
- Finding 的 `continuity` / `evidence_hash` / `basis_hash` 判定 —— D009 与本矩阵**正交**（PRD:131「两者不得混入同一字段或同一 UI 标签」），本文一律未涉及。
- Decision 写入的并发实现（PR 行锁 + 条件更新）—— 本文只用到它的**前置条件清单**（ARCH:279–284）来推互斥性。
- D016.2 要求批次 3 补的 P1 DEVELOPER 半条授权 —— 它依赖 `(PR, head)` 上"是否已有人工终局 Decision"，谓词形状与 §6.5 的 Decision Gate **相同但不完全相同**（Gate 只找 `REQUEST_CHANGES`，P1 找**任何**终局 Decision，即 `decision <> 'PENDING'`）。**这两个谓词不能复用同一个方法**，此处仅作提示，未展开。
- 前端 8 值的标签文案、色彩与 `prefers-reduced-motion` 契约。

---

## 11. Caveats

- 全部行号基于 `git rev-parse HEAD = 2cd09fb`（2026-08-21）的工作树。`docs/v2/` 若被修改，需重新核对。
- 本文**没有**运行任何数据库实测。§6.3 的唯一性推导、§4.2 的互斥性推导都是**从文本推的**，不是像 `pg15-hibernate-constraints.md` / `pgvector-hibernate-measured.md` 那样从真实 PostgreSQL 实测的。`UNIQUE NULLS NOT DISTINCT` 与 `IS NOT DISTINCT FROM` 在 PG15 上的实际行为**建议实测确认**（成本很低：两条 DDL + 四条 INSERT）。
- 本文只写了 activity 的**判定**，没有写它的**计算成本**。需求列表页要为每个需求聚合其全部 PR 的全部 review，N+1 风险真实存在，属 `design.md` 范围。
