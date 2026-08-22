# Research: Fencing 与并发实测（Phase 6 + Phase 7）

- **Query**: fencing 的最小 schema 形态；旧 Worker 不得完成/失败/插入 Finding 的真实行为；lease 过期抢占；
  Phase 7 Decision 的「一次性」与并发唯一胜者；「同 head 的 REQUEST_CHANGES 只能由新 head 解除」的判定条件与反例；
  `finding_event` 的审计约束。
- **Scope**: internal（权威文档 + 现有代码）+ 真实 PostgreSQL 15 双连接实测
- **Date**: 2026-08-21
- **授权边界**: 本文是批次 3 的**规划输入**。它不授权实现，不替代 `prd.md` / `design.md` / `implement.md`，
  也不修改 `docs/v2/` 下任何权威文档。本文提出的三处 schema 增补**必须**先由设计阶段裁定（见 §1.3、§8）。

---

## 实测环境

```bash
docker run -d --name fp-fencing -e POSTGRES_PASSWORD=fp -e POSTGRES_DB=fp -p 55433:5432 \
  pgvector/pgvector:0.8.6-pg15-bookworm
```

```
PostgreSQL 15.19 (Debian 15.19-1.pgdg12+2) on x86_64-pc-linux-gnu, compiled by gcc 12.2.0, 64-bit
```

**底座是真实的仓库 schema**，不是手搓的近似：依次导入 `V1__foundation.sql` … `V5__scm.sql` 全部成功，
再在其上创建实验用的 `review` / `finding` / `finding_event`。种子数据用真实的
`user_account` / `project` / `project_member` / `requirement` / `requirement_revision` /
`acceptance_criterion` / `scm_repository` / `pull_request` 行。

并发实测用**两个持久 `psql` 连接**（FIFO 驱动，记为 A / B），可任意交错语句并用
`pg_stat_activity` 观察阻塞。脚本在 `/root/.claude/jobs/e84ffece/tmp/`（`exp1`…`exp12`），
**未在 `backend/src/test` 留下任何文件，未修改任何生产代码**。

隔离级别除显式标注外均为默认 `READ COMMITTED`。

---

## 结论速览

| # | 结论 | 依据 |
|---|---|---|
| 1 | 旧 Worker 的**完成、标记失败、续租**三条路径在 token/attempt 条件更新下均为 0 行 | **实测** §2 |
| 2 | 旧 Worker 插入 Finding **可以由数据库拒绝**，不必依赖应用层先查 attempt | **实测** §3 |
| 3 | 该 fence 是**预防性**的：Worker 写 Finding 期间，并发 re-claim 会阻塞而非抢走 | **实测** §3.4 §3.6 |
| 4 | 只靠应用层「先查 token 再插入」有**真实 TOCTOU 破口**，能把陈旧 Finding 落到活 Review 下 | **实测** §3.5 |
| 5 | 该 fence 的代价：**上一次 attempt 遗留的 Finding 会把 Review 钉死**，re-claim 必须同事务删除它们 | **实测** §3.7 |
| 6 | `ON UPDATE CASCADE` 是陷阱：它把陈旧 attempt 的 Finding **改标**成新 attempt 的产出 | **实测** §3.8 |
| 7 | lease 过期抢占：双连接下**只有一个成功**，输家 0 行（RR 下为 40001） | **实测** §4.1 §4.2 |
| 8 | `now()` 是**事务开始时间**；长事务里 `lease_until < now()` 会漏判过期（失活，不是越权） | **实测** §4.3 |
| 9 | 两个并发 APPROVE、APPROVE vs REQUEST_CHANGES：条件更新保证唯一胜者，输家 0 行 | **实测** §5.1 §5.2 |
| 10 | 「只能一次」靠**条件更新**，`CHECK` 做不到；`BEFORE UPDATE` 触发器可做**兜底**，两者互补 | **实测** §5.3 |
| 11 | §3.1 的六项前置**可以折进一条 UPDATE**，行数即闸门 | **实测** §5.4 |
| 12 | **PR 行锁不是冗余**：不加锁时一条语句的闸门会对着**陈旧的 PR 快照**放行 APPROVE | **实测** §5.5 ← 最高危 |
| 13 | 前置 5 必须写 `IS NOT DISTINCT FROM`；写 `=` 时两侧为 NULL 得 NULL，条件更新永远 0 行 | **实测** §5.6 |
| 14 | Decision Gate 判定条件 = `∃ review(pr, head_sha = PR 当前 head, decision='REQUEST_CHANGES')` | 推理（源自 §3.1 原文），**反例已实测** §6 |
| 15 | 三种常见的写错判定（看最新一条 / 看有无 APPROVE / 不看 head）都会**错误解除封锁** | **实测** §6.2 |
| 16 | 封锁必须**派生**而非在 `pull_request` 上存布尔位：force-push 回旧 head 时派生式会自动重新封锁 | **实测** §6.3 |
| 17 | `finding_event` 的 `is_a_change` 必须**按 action 分支**，因为它同时审计状态与指派两个维度 | **实测** §7.1 |
| 18 | 「只有继承的 SUPPRESSED 可重开」`CHECK` 表达不了（不能带子查询），**约束触发器可以** | **实测** §7.3 |
| 19 | 审计行的 `from_status` 只有配合**条件更新**才是真的；否则并发下会记录两条互相矛盾的转移 | **实测** §7.5 |
| 20 | 约束触发器要能被 `SET CONSTRAINTS ALL DEFERRED` 移动，**必须显式声明 `DEFERRABLE`** | **实测** §7.4 |

---

## 0. 本文没有测什么（先说清楚）

这些是真实的空白，不要当成"已验证"：

1. **完全没有经过 Hibernate / JPA。** 全部实测都是 `psql` 原生 SQL。考虑到 D013.1 的历史
   （Hibernate 7 在复合外键的自然写法上**拒绝启动**），`finding` 上再加一条三列复合外键
   （`project_id, review_id, review_attempt`）在实体映射层能否成立**未经验证**，这是本文最大的未知。
   条件更新要拿到影响行数，还必须是 `@Modifying` 的 bulk update 而非脏检查——同样未测。
2. **没有测 §2.3 要求的 Finding 父子上下文约束触发器**（`IS NOT DISTINCT FROM` 那条）与 25P02 行为。
   那是另一份研究的范围（PRD §8 的第一份），本文只在 §7.3 顺带证明了约束触发器机制本身可用。
3. **没有做性能或规模测量。** 复合外键增加的 RI 开销、`FOR KEY SHARE` 在并发 Review 下的锁竞争，
   都没有量。Phase 6 的并发上限（1 还是 2）是另一件事，必须按 D012/D014 在 4 GB 目标机上实测，本文不提供该数字。
4. **Finding 状态集与 PRD 不一致。** 实验里用了 `OPEN/ACCEPTED/REJECTED/FIXED` 简化集合，
   PRD §2.11 的真实生命周期是 `OPEN → CONFIRMED → IN_PROGRESS → FIXED → VERIFIED → CLOSED` 加旁路 `REJECTED`。
   §7 的**约束形状**与状态集无关，但落地时 `CHECK` 的取值列表必须换成 PRD 的真实集合。
5. **没有测 reconciliation 调度器、after-commit callback、事务内同步事件**——那是另一份研究的范围。
6. 双连接实测都是**人工交错**，不是压力测试。它证明「这个交错下会发生什么」，不证明「所有交错都安全」。

---

## 1. fencing 的最小 schema 形态

### 1.1 §2.1 给了什么，没给什么

`ARCHITECTURE.md:102`（`review` 行，原文）：

> project_id、不可变的 review_input_fingerprint/context_snapshot_json、status、decision、decision_by/at/comment、
> **execution_attempt/token/lease**、engine/prompt/model 审计列

这是一个**缩写斜杠列表**，不是列定义。三个列名里只有一个半是确定的：

- `execution_attempt` — §3.2 `ARCHITECTURE.md:301` 逐字写出：「每次领取递增 `execution_attempt`」。**确定**。
- `execution_token` — 同句逐字写出：「生成新 `execution_token`」。**确定**。
- lease 列 — §2.1 的斜杠列表展开应为 `execution_lease`，但 §3.2 同一句里逐字写的是
  「并写 `lease_until`」。**两处不一致**。（本次任务书里用的第三种拼法 `lease_expires_at` 在
  `docs/` 与 `.trellis/` 全文中**零命中**；`grep -rc "lease_until" docs/v2/*.md` 只在 `ARCHITECTURE.md`
  命中 1 行，其余提到 lease 的 9 行都是泛指，没有第二个具体列名。）

**裁定建议**：用 `lease_until`，因为它是文档中唯一被逐字写出的拼法。这属于命名裁定，不改变语义。

§2.1 **没有给**的三样，都是本文实测后认为必要的：

| 增补项 | 属于 | §2.1 现状 |
|---|---|---|
| `review` 上 `UNIQUE (project_id, id, execution_attempt)` | 同表补**约束** | 未列出（§2.1 只列了 `uq_review_identity` 与 `UNIQUE(project_id,id)`） |
| `finding.review_attempt` | 同表补**列** | `finding` 的列清单里没有它 |
| `finding` 上 FK `(project_id, review_id, review_attempt) → review(project_id, id, execution_attempt)` | 同表补**约束** | 未列出（§2.1 只强制了永久父 FK `(project_id,review_id)`） |

**三项都不是新增表**，因此不触发 §2.1「新增表必须有已发生的业务事实 + 新决策记录」那条，
也不违反「不建 `review_task/report/issue`、执行恢复不另建任务表」——fencing 元数据仍然全在 `review` 行上。
但**三项都超出了 §2.1 的列举**，按 D013 / D015 的先例，需要一条批次 3 的实现裁定把它们正式化。
永久父 FK `(project_id, review_id) → review(project_id, id)` 是 §2.1 明文强制的，**必须保留**，
新的三列 FK 是**追加**，不是替换（§3.6 的控制实验说明两条都有用）。

### 1.2 实测采用的 DDL（节选，完整文件见 `exp_schema.sql`）

```sql
CREATE TABLE review (
    ...
    status            VARCHAR(16) NOT NULL,
    decision          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decision_by       BIGINT, decision_at TIMESTAMPTZ, decision_comment TEXT,
    -- fencing triple
    execution_attempt INTEGER     NOT NULL DEFAULT 0,
    execution_token   UUID,
    lease_until       TIMESTAMPTZ,
    ...
    CONSTRAINT uq_review_identity UNIQUE NULLS NOT DISTINCT
        (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id),
    CONSTRAINT uq_review_project_id UNIQUE (project_id, id),
    -- fence 目标
    CONSTRAINT uq_review_project_id_attempt UNIQUE (project_id, id, execution_attempt)
);

CREATE TABLE finding (
    ...
    review_id      BIGINT NOT NULL,
    review_attempt INTEGER NOT NULL,
    ...
    CONSTRAINT fk_finding_review
        FOREIGN KEY (project_id, review_id) REFERENCES review (project_id, id),
    CONSTRAINT fk_finding_review_attempt
        FOREIGN KEY (project_id, review_id, review_attempt)
        REFERENCES review (project_id, id, execution_attempt)
);
```

类型选择的实测依据：

- `execution_attempt INTEGER NOT NULL DEFAULT 0` — PENDING 期即为 attempt 0，`NOT NULL` 让复合唯一键始终有值。
- `execution_token UUID` — `gen_random_uuid()` 在 PG15 是**内建**函数，无需 `pgcrypto` 扩展（实测直接可用）。
- `lease_until TIMESTAMPTZ`（可空）— PENDING 期为 NULL。

领取语句（全文实测都用这一条）：

```sql
UPDATE review SET status='RUNNING', execution_attempt = execution_attempt + 1,
       execution_token = gen_random_uuid(), lease_until = now() + interval '...'
 WHERE id = ? AND (status='PENDING' OR (status='RUNNING' AND lease_until < now()))
RETURNING id, status, execution_attempt, execution_token;
```

### 1.3 需要设计阶段裁定的项

见 §8 开放项 O1–O3。

---

## 2. 旧 Worker 不得完成、不得失败、不得续租

`exp1_complete_fail.sh`。A 领取得到 attempt=1 / token=`dfaf82c2-…`；lease 过期后 B 重新领取，attempt→2、新 token。
随后 A 用**旧 token / 旧 attempt** 尝试四种写入：

### 2a 完成（token fence）

```
UPDATE review SET status='COMPLETED', lease_until=NULL
        WHERE id=1 AND execution_token='dfaf82c2-c480-4abe-b2e3-ba61c13d7933' AND status='RUNNING'
        RETURNING id, status, execution_attempt;
 id | status | execution_attempt
----+--------+-------------------
(0 rows)

UPDATE 0
```

### 2b 完成（attempt fence）

```
 WHERE id=1 AND execution_attempt=1 AND status='RUNNING'
(0 rows)
UPDATE 0
```

### 2c 标记失败（**最常被漏掉的一条**）

```
UPDATE review SET status='FAILED', lease_until=NULL
        WHERE id=1 AND execution_token='dfaf82c2-c480-4abe-b2e3-ba61c13d7933' AND status='RUNNING'
        RETURNING id, status, execution_attempt;
 id | status | execution_attempt
----+--------+-------------------
(0 rows)

UPDATE 0
```

### 2d 续租

```
UPDATE review SET lease_until = now() + interval '30 seconds'
        WHERE id=1 AND execution_token='…' AND status='RUNNING'
(0 rows)
UPDATE 0
```

### 2e 对照：**去掉 fence 会怎样**

```
UPDATE review SET status='FAILED' WHERE id=1 RETURNING id, status, execution_attempt;
 id | status | execution_attempt
----+--------+-------------------
  1 | FAILED |                 2
(1 row)

UPDATE 1
```

旧 Worker 一句无条件 `UPDATE` 就把**新 attempt 正在跑的 Review** 标成了 FAILED。

### 2f 对照：活 Worker 不受影响

```
 WHERE id=1 AND execution_token='<B 的 token>' AND status='RUNNING'
 id |  status   | execution_attempt
----+-----------+-------------------
  1 | COMPLETED |                 2
UPDATE 1
```

**结论**：三条写路径（完成 / 失败 / 续租）用同一个 `review_id + execution_token + status='RUNNING'`
条件更新即可，全部 0 行。`execution_attempt` 与 `execution_token` 二选一都能挡住；
用 token 更好，因为它对「attempt 回绕 / 被手工改写」也免疫。
**PRD R2 要求的四条断言里，这三条到此为止都是纯条件更新能覆盖的。第四条（插入 Finding）不是。**

---

## 3. 旧 Worker 不得插入 Finding —— **数据库能表达，且是预防性的**

这是本文最有价值的一节。`exp2_finding_fence.sh` / `exp9a_decisive.sh` / `exp10_control.sh`。

### 3.1 顺序场景：陈旧 attempt 在父表里已经不存在

review 的 `execution_attempt` 已经是 2，旧 Worker 拿 attempt=1 插 Finding：

```
-- stale worker (attempt 1) inserts a Finding:
ERROR:  insert or update on table "finding" violates foreign key constraint "fk_finding_review_attempt"
DETAIL:  Key (project_id, review_id, review_attempt)=(1, 1, 1) is not present in table "review".
-- live worker (attempt 2) inserts a Finding:
INSERT 0 1
```

### 3.2 READ COMMITTED 竞态：A 先查过自己的 token，B 抢走并提交，A 再插

```
=== session A ===
SELECT execution_attempt, execution_token FROM review WHERE id=1;
 execution_attempt |           execution_token
-------------------+--------------------------------------
                 1 | 11111111-1111-1111-1111-111111111111      <- A 的前置检查通过

=== session B ===
UPDATE review SET execution_attempt=2, … WHERE id=1;   UPDATE 1
COMMIT;

=== session A ===
INSERT INTO finding (…) VALUES (1, 1, 1, …);
ERROR:  insert or update on table "finding" violates foreign key constraint "fk_finding_review_attempt"
DETAIL:  Key (project_id, review_id, review_attempt)=(1, 1, 1) is not present in table "review".
COMMIT;
ROLLBACK
-- rows actually stored:
(0 rows)
```

**RI 检查用的不是事务快照，而是最新快照**，所以 A「刚刚验证过」不构成漏洞。

### 3.3 REPEATABLE READ：失败方向仍然安全

A 的快照钉在 attempt=1，B 抢走并提交后 A 插入：

```
ERROR:  could not serialize access due to concurrent update
CONTEXT:  SQL statement "SELECT 1 FROM ONLY "public"."review" x WHERE "project_id" OPERATOR(pg_catalog.=) $1
           AND "id" OPERATOR(pg_catalog.=) $2 FOR KEY SHARE OF x"
(0 rows stored)
```

40001，事务整体失败。**不会**因为快照旧就放行。

### 3.4 交错：B 未提交时 A 插入 —— A **阻塞**，不是 TOCTOU

```
-- wait state while B holds the row (A should be blocked):
478 idle in transaction Client/ClientRead :: UPDATE review SET execution_attempt=2, …
479 active Lock/transactionid                :: INSERT INTO finding (project_id, review_id, review_attempt, …

-- B commits, then A's insert result:
ERROR:  insert or update on table "finding" violates foreign key constraint "fk_finding_review_attempt"
DETAIL:  Key (project_id, review_id, review_attempt)=(1, 1, 1) is not present in table "review".
```

RI 检查发出的是 `SELECT … FOR KEY SHARE`；B 改的是唯一索引里的键列，取的是 `FOR UPDATE` 强度锁，
两者冲突 → A 排队 → B 提交后 A 重查 → 23503。**没有可利用的时间窗**。

### 3.5 对照：只靠应用层检查的**真实破口**

删掉 `fk_finding_review_attempt`，其余不变，重跑 §3.2 的交错：

```
SELECT execution_attempt FROM review WHERE id=1;   -- app checks: 'still mine'  -> 1
(B 抢占并提交)
INSERT INTO finding (…) VALUES (1, 1, 1, …);
INSERT 0 1
COMMIT;

-- a stale Finding is now persisted under the live review:
 id | finding_attempt | review_attempt | finding_key
----+-----------------+----------------+-------------
  6 |               1 |              2 | k6
```

一条 attempt=1 的 Finding 挂在 attempt=2 的活 Review 下。UI 会把它当作本轮结果展示。
**「先查 attempt 再插入」是有竞态的写法，不能作为 PRD R2 第四条的实现。**

### 3.6 控制实验：到底是哪个对象在起作用（`exp10_control.sh`）

A 插入 Finding 未提交，B 尝试 re-claim：

| 用例 | 保留的对象 | B 是否阻塞 | B 的结果 | 最终 |
|---|---|---|---|---|
| 1 | 三列 FK + 三列 UNIQUE | **阻塞** | 23503：`Key (project_id, id, execution_attempt)=(1,1,1) is still referenced from table "finding"` | review_attempt=1，抢占**被拒** |
| 2 | 只有三列 UNIQUE（FK 删除） | **阻塞** | `UPDATE 1` | review_attempt=2，Finding 变孤儿 |
| 3 | 两者都删 | **不阻塞** | `UPDATE 1` | review_attempt=2，Finding 变孤儿 |

用例 3 的等待状态两个连接都是 `idle in transaction`，即 B 在 A 未提交时就已经跑完了 UPDATE：

```
2239 idle in transaction Client/ClientRead :: UPDATE review SET status='RUNNING', execution_attempt = …
2240 idle in transaction Client/ClientRead :: INSERT INTO finding (project_id, review_id, review_attempt, …
```

**读法**：
- **三列 UNIQUE** 让 `execution_attempt` 成为「键列」，于是改它需要 `FOR UPDATE`，与 Finding 插入持有的
  `FOR KEY SHARE` 冲突 → 产生**串行化（阻塞）**。
- **三列 FK** 才产生**拒绝**。
- 两者都要，缺一不可。只有 UNIQUE 没有 FK，得到的是「阻塞一下然后照样抢走」。

### 3.7 代价：上一次 attempt 遗留的 Finding 会**把 Review 钉死**（`exp3_fence_cost.sh`）

崩溃的 attempt 1 已经写了部分 Finding，reconciliation 直接 re-claim：

```
ERROR:  update or delete on table "review" violates foreign key constraint
        "fk_finding_review_attempt" on table "finding"
DETAIL:  Key (project_id, id, execution_attempt)=(1, 1, 1) is still referenced from table "finding".
```

**这条必须在设计里正面处理，否则一个崩溃的 Worker 会让该 Review 永远无法恢复。** 实测可行的解法是
同事务先丢弃被放弃 attempt 的产出，再领取：

```sql
BEGIN;
  DELETE FROM finding WHERE project_id=? AND review_id=?
    AND review_attempt = (SELECT execution_attempt FROM review WHERE project_id=? AND id=?);
  UPDATE review SET status='RUNNING', execution_attempt = execution_attempt + 1, … WHERE …;
COMMIT;
```

```
DELETE 1
 id | execution_attempt
----+-------------------
  1 |                 2
UPDATE 1
COMMIT
 findings_left
---------------
             0
```

语义上这是对的——re-claim 就意味着上一次的产出作废。同时实测确认**历史不受影响**：
`COMPLETED` 的 Review 根本领不到（领取条件只匹配 PENDING 或过期 RUNNING），其 Finding 一行不动：

```
-- reconciliation tries to re-claim a COMPLETED review:
(0 rows)   UPDATE 0
-- and the historical Finding is untouched:
  9 |              1 | partial-1
```

### 3.8 `ON UPDATE CASCADE` 是陷阱，**不要用**

```
-- 3d. ON UPDATE CASCADE instead?
 id | finding_attempt | review_attempt | finding_key
----+-----------------+----------------+-------------
 10 |               2 |              2 | partial-1
```

被放弃的 attempt-1 Finding 被**静默改标**成 attempt 2，看起来就像新一轮的产出。
这比没有 fence 更糟：它伪造了证据。FK 必须保持默认的 `NO ACTION`。

### 3.9 fence 是**预防性**的：活 Worker 写 Finding 期间不会被抢走（`exp9a_decisive.sh`）

A 插入 Finding（未提交），B 的 reconciliation 尝试 re-claim：

```
=== wait state: B queued behind A's in-flight Finding insert ===
2087 active            Lock/transactionid :: UPDATE review SET status='RUNNING', execution_attempt = …
2088 idle in transaction Client/ClientRead :: INSERT INTO finding (project_id, review_id, review_attempt, …
```

A 随后用自己的 token 完成并提交；B 解除阻塞后重新求值：

```
=== session A ===
 id |  status   | execution_attempt
----+-----------+-------------------
  1 | COMPLETED |                 1
UPDATE 1

=== session B ===
 id | execution_attempt
----+-------------------
(0 rows)
UPDATE 0

=== final state ===
 id |  status   | execution_attempt | review_attempt | finding_key
----+-----------+-------------------+----------------+-------------
  1 | COMPLETED |                 1 |              1 | k1
```

**一个正在写 Finding 的 Worker 不会在写到一半时被 reconciliation 抢走。** 这是 §3.7 那条代价换来的好处，
两者是同一个机制的两面。

### 3.10 SQLSTATE（D013.11 要求统一映射为 409/422）

```
(i)   陈旧 attempt 插 Finding        -> 23503  fk_finding_review_attempt
(ii)  重复 Review 身份（D003）        -> 23505  uq_review_identity
(iii) 非法 Decision 字段组合          -> 23514  ck_review_decision_fields
(iv)  被子行钉住的 re-claim           -> 23503  fk_finding_review_attempt
(v)   REPEATABLE READ 下的 RI 竞态    -> 40001  could not serialize access
```

注意 **(v) 是 40001，不是约束冲突**。D013.11 的「统一映射 409/422」没有覆盖它；
40001 语义上是「请重试」，映射成 409 可以，但它与约束冲突的处置不同（重试有意义）。
这是一个需要在错误映射里显式区分的点。

`UNIQUE NULLS NOT DISTINCT` 在无需求 Review 上按预期工作：

```
ERROR:  23505: duplicate key value violates unique constraint "uq_review_identity"
DETAIL:  Key (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)=(1, headN, fpN, null) already exists.
```

---

## 4. lease 过期的判定与抢占

`exp4_lease.sh`。

### 4.1 双连接抢同一个过期 lease —— 只有一个成功

初始：`status=RUNNING, execution_attempt=1, lease_until` 已过期（`expired = t`）。

```
--- A's result (uncommitted):
 id | status  | execution_attempt |           execution_token
----+---------+-------------------+--------------------------------------
  1 | RUNNING |                 2 | 87a95d89-e699-4a47-bb42-d8b256acdf55
UPDATE 1

--- wait state while A holds the row:
826 active            Lock/transactionid :: UPDATE review SET status='RUNNING', execution_attempt = …
833 idle in transaction Client/ClientRead :: UPDATE review SET status='RUNNING', execution_attempt = …

--- B's result, re-evaluated after A committed (READ COMMITTED EvalPlanQual):
 id | status | execution_attempt | execution_token
----+--------+-------------------+-----------------
(0 rows)
UPDATE 0

 id | status  | execution_attempt
----+---------+-------------------
  1 | RUNNING |                 2
```

B 阻塞在行锁上，A 提交后 B **对更新后的行版本重新求值** `WHERE` 子句：此时 `status='RUNNING'` 且
`lease_until` 已被 A 推到未来 → 谓词为假 → 0 行。**这就是唯一胜者的全部机制**，不需要额外的锁。

### 4.2 REPEATABLE READ 下输家也不会赢

```
ERROR:  could not serialize access due to concurrent update
 id | status  | execution_attempt
----+---------+-------------------
  1 | RUNNING |                 2
```

### 4.3 活 lease 不可抢占

```
--- 4a. a live lease cannot be preempted
(0 rows)  UPDATE 0
```

### 4.4 `now()` 是**事务开始时间**——一个失活陷阱（不是越权）

事务 A 开启后 sleep 5 秒，期间 lease 在墙钟意义上已过期：

```
        tx_start_still         |           wall_now            |          lease_until          | expired_per_now | expired_per_clock
-------------------------------+-------------------------------+-------------------------------+-----------------+-------------------
 2026-08-21 18:35:30.780995+00 | 2026-08-21 18:35:37.004395+00 | 2026-08-21 18:35:33.694144+00 | f               | t

--- claim using now() (transaction start):
(0 rows)   UPDATE 0
--- same claim using clock_timestamp():
 id | execution_attempt
----+-------------------
  1 |                 2
UPDATE 1
```

**方向是安全的**：`now() <= clock_timestamp()` 恒成立，所以 `lease_until < now()` 比
`lease_until < clock_timestamp()` **更难**满足——长事务只会**漏判**过期（该恢复的没恢复），
不会**误判**活 lease 为过期（把活 Worker 抢掉）。

但 reconciliation 如果跑在一个长事务里（例如一次扫描一批 Review），后面的 Review 会用越来越陈旧的 `now()`，
**表现为「停滞的 Review 恢复不了」而不是报错**。同理，`lease_until = now() + interval` 在长事务里
会锚在事务开始时刻，发出去的 lease 比预期短。

**建议**：领取语句里两处时间都用 `clock_timestamp()`，或保证 reconciliation 每个 Review 一个短事务。
这是一条推理 + 实测支撑的建议，不是文档要求。

---

## 5. Phase 7：Review Decision 的并发

`ARCHITECTURE.md:277-286` 规定：`SELECT … FOR UPDATE` 锁 `pull_request` 行 + 逐条校验六项前置 +
`WHERE decision='PENDING'` 条件更新，影响行数必须为 1，否则 409。以下逐件实测。
`exp5_decision.sh` / `exp11_six_preconditions.sh` / `exp12_pr_lock_necessity.sh`。

### 5.1 两个并发 APPROVE

A 与 B 都走「锁 PR → 读前置 → 条件更新」：

```
--- wait state: B is queued on the PR row lock
955 idle in transaction Client/ClientRead :: SELECT id, head_sha FROM pull_request WHERE project_id=1 AND id=1 FOR …
956 active            Lock/transactionid :: SELECT id, head_sha FROM pull_request WHERE project_id=1 AND id=1 FOR …

=== session A ===
SELECT decision …            -> PENDING
UPDATE … WHERE … AND decision='PENDING' RETURNING …
 id | decision | decision_by
----+----------+-------------
  1 | APPROVE  |           1
UPDATE 1
COMMIT

=== session B ===
SELECT decision …            -> APPROVE      <- 拿到锁之后才读，看到的是 A 的已提交结果
UPDATE … WHERE … AND decision='PENDING' RETURNING …
(0 rows)
UPDATE 0
```

注意 B 的前置读**在拿到锁之后**才发出，因此 READ COMMITTED 给了它一个新快照，读到 `APPROVE`。
**先锁后读这个顺序是承重的**（反例见 §5.5）。

### 5.2 `PENDING → APPROVE` 与 `PENDING → REQUEST_CHANGES` 并发

```
--- A (APPROVE):            1 | APPROVE | 1      UPDATE 1
--- B (REQUEST_CHANGES):    (0 rows)             UPDATE 0
最终:                       1 | APPROVE | 1
```

谁赢取决于谁先拿到锁，**这是正确的**：两者都是合法的人工终局，先到先得，后到者拿 0 行 → 409。

### 5.3 「只能一次」：条件更新 vs 约束

**第二次尝试**（已 APPROVE 的 Review 上再 REQUEST_CHANGES）：

```
-- 5c. 条件更新形式
(0 rows)   UPDATE 0
最终仍是 APPROVE
```

**`CHECK` 做不到**。`ck_review_decision_fields`（§2.3 明文那条）在场时，一句**无条件** UPDATE 照样覆盖：

```
-- 5f. an UNCONDITIONAL overwrite of an already-decided review, ck_review_decision_fields present:
 id |    decision     | decision_by
----+-----------------+-------------
  1 | REQUEST_CHANGES |           2
UPDATE 1
```

原因是 `CHECK` 只能看 `NEW` 行，看不到 `OLD.decision`。而 `APPROVE + decision_by + decision_at`
这个组合本身是合法的，所以 `REQUEST_CHANGES` 覆盖 `APPROVE` 通过了检查。

**`BEFORE UPDATE` 触发器可以**：

```sql
IF OLD.decision <> 'PENDING' AND NEW.decision IS DISTINCT FROM OLD.decision THEN
  RAISE EXCEPTION 'review % already decided as %', OLD.id, OLD.decision USING ERRCODE='23514';
END IF;
```

```
--- the same unconditional overwrite as 5f, now with the trigger:
ERROR:  review 1 already decided as APPROVE
--- and the conditional form returns 0 rows rather than raising:
(0 rows)   UPDATE 0
--- unrelated updates to a decided review still work (status, lease):
 id | decision
----+----------
  1 | APPROVE
UPDATE 1
```

**哪个更可靠？两者角色不同，应该都要：**

- **条件更新是并发控制**，也是**唯一**能给出「影响行数 = 1 / 0」从而映射 409 的东西。它必须是主路径：
  正常的并发竞争走这里，得到 0 行，返回 409，**不产生异常**——这符合 D013.11（不捕获约束冲突后继续）。
- **触发器是防绕过兜底**。它挡的不是并发，是「有人日后写了一句无条件 `save()`」。§3.1 原文
  「禁止用普通 `EXISTS` 查询或无条件 save 代替」正是在防这件事，而**只有触发器能在数据库层强制这句话**。
- 如果只能选一个：**选条件更新**，因为没有它就没有 409 的判据；但那样 §3.1 的「禁止无条件 save」
  就只剩下代码评审在守。

### 5.4 六项前置可以折进一条语句，行数即闸门

```sql
UPDATE review r SET decision=?, decision_by=?, decision_at=now()
  FROM pull_request p
 WHERE r.project_id=? AND r.id=?
   AND p.project_id=r.project_id AND p.id=r.pull_request_id
   AND r.status='COMPLETED'                                            -- 1
   AND r.decision='PENDING'                                            -- 2
   AND r.head_sha = p.head_sha                                         -- 3
   AND r.review_input_fingerprint = p.review_input_fingerprint         -- 4
   AND r.requirement_revision_id IS NOT DISTINCT FROM                  -- 5
       (SELECT req.current_revision_id FROM requirement req
         WHERE req.project_id=p.project_id AND req.id=p.requirement_id)
   AND NOT EXISTS (SELECT 1 FROM review b                              -- 6
                    WHERE b.project_id=r.project_id AND b.pull_request_id=r.pull_request_id
                      AND b.head_sha = p.head_sha AND b.decision='REQUEST_CHANGES')
RETURNING r.id, r.decision;
```

逐条实测拒绝：

```
--- review 3 is still RUNNING (precondition 1 fails):        (0 rows)  UPDATE 0
--- review 2's fingerprint is stale (precondition 4 fails):  (0 rows)  UPDATE 0
--- review 1 satisfies all six:                              1 | REQUEST_CHANGES     UPDATE 1
--- gate now closed on head1; review 2 tries to APPROVE (6):  (0 rows)  UPDATE 0
--- the head moves; a new review on head2 can be approved:    4 | APPROVE            UPDATE 1
```

同一 Review 上两个并发决定，单语句闸门仍然唯一胜者：

```
--- A:  UPDATE 1
--- B:  (0 rows)  UPDATE 0
 id | decision | decision_by
----+----------+-------------
 10 | APPROVE  |           1
```

### 5.5 【最高危】**PR 行锁不是冗余的**（`exp12`）

先记录一个结构性事实：**前置 3+4+5 把 Review 钉死到唯一一行。**
`uq_review_identity` 是 `(pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`，
而 3/4/5 恰好把后三项固定为 PR 的当前值：

```
--- a SECOND review with the PR's current head, fingerprint AND revision:
ERROR:  23505: duplicate key value violates unique constraint "uq_review_identity"
--- rows that pass 3+4+5 right now:
 id
----
  1
```

所以「同一 PR 的两条不同 Review 被同时决定」这种竞态，**只有在实现漏掉前置 4 或 5 时才可达**。
（漏掉时确实会发生，`exp6` 6e 实测两条 Review 各自条件更新**都成功**，同一 head 上同时出现
APPROVE 与 REQUEST_CHANGES。）

**但 PR 行锁挡的是另一件事：闸门与 SCM webhook 的竞态。**

不加锁，B（SCM）正在把 head 从 head1 推到 head2（未提交），A 发出上面那条单语句闸门：

```
--- did A block, or did it read the pre-update PR row?
2557 idle in transaction Client/ClientRead :: UPDATE pull_request SET head_sha='head2', … -- SCM
2558 idle in transaction Client/ClientRead :: UPDATE review r SET decision='APPROVE', …   -- 闸门，没有阻塞

--- A:
  1 | APPROVE  | head1
UPDATE 1

 id | review_head | decision | pr_head_now
----+-------------+----------+-------------
  1 | head1       | APPROVE  | head2
```

**A 对着陈旧的 PR 快照放行了 APPROVE。** 条件更新的 EvalPlanQual 只重查**目标行**（`review`），
**不重查连接进来的 `pull_request` 行**——对 `p` 的读就是一次普通快照读，不阻塞、不重算。
于是前置 3/4/5 全部基于旧 head 求值，而它们正是「这条 Review 还当前有效吗」的全部依据。

加上 `SELECT … FOR UPDATE`：

```
--- A is now waiting for the SCM transaction:
2615 idle in transaction Client/ClientRead :: UPDATE pull_request SET head_sha='head2', …
2616 active            Lock/transactionid :: SELECT id, head_sha FROM pull_request WHERE project_id=1 AND id=1 FOR …

--- A:
  1 | head2
(0 rows)
UPDATE 0

 id | review_head | decision | pr_head_now
----+-------------+----------+-------------
  1 | head1       | PENDING  | head2
```

A 排队等 SCM 提交，读到 head2，闸门 0 行，**正确拒绝**。

**这就是 §3.1 为什么写「写入事务必须 `SELECT … FOR UPDATE` 锁 `pull_request` 行」。
它不是给条件更新加保险，它是让前置 3/4/5 在并发下成立的唯一手段。去掉它 = 允许对陈旧 diff 做终局放行。**

### 5.6 前置 5 必须写 `IS NOT DISTINCT FROM`

```
 with_equals | with_is_not_distinct | equals_in_a_where_clause
-------------+----------------------+--------------------------
             | t                    | f
```

放进真实条件更新（PR 无关联需求、Review 无需求版本，两侧都是 NULL）：

```
--- precondition 5 written with '=' :               (0 rows)  UPDATE 0
--- precondition 5 written with 'IS NOT DISTINCT FROM' :   1 | APPROVE   UPDATE 1
```

写 `=` 的后果是**无需求关联的 PR 永远无法被 APPROVE**（失活，不是越权），
而且症状是「点了没反应 / 一直 409」，很难归因。§3.1 第 5 条的括号「NULL 亦须相等」就是这条。

---

## 6. 「同 head 的 REQUEST_CHANGES 只能由新 head 解除」

`exp6_block_predicate.sh`。

### 6.1 翻译成行状态与判定条件

`ARCHITECTURE.md:272` 与 `:275` 原文：

> Decision Gate = pull_request_id + head_sha 上是否已有 REQUEST_CHANGES
> 同一 head 一旦出现 `REQUEST_CHANGES`，任何需求版本或 Diff fingerprint 都不能再 APPROVE，解除只能靠新 head SHA。

```sql
-- 封锁判定（派生，不落库）
blocked(pr) :=
  EXISTS (SELECT 1 FROM review r
           WHERE r.project_id      = pr.project_id
             AND r.pull_request_id = pr.id
             AND r.head_sha        = pr.head_sha        -- PR 的【当前】head，不是 review 的历史 head
             AND r.decision        = 'REQUEST_CHANGES')
```

三个必须同时成立的性质：

1. **粘附在 head 上**，不在 Review 上。同一 head 上任何一条 REQUEST_CHANGES 就封锁。
2. **不可被同 head 的 APPROVE 抵消**。Review 行永不覆盖（§3.5「旧 Review 永不失效、永不覆盖」），
   所以 REQUEST_CHANGES 那一行始终存在，谓词始终为真。
3. **只随 `pull_request.head_sha` 变化而解除**，因为 head_sha 是谓词里唯一会变的项。

还要与另外两个概念分开（§3.1 明确列为三件事）：`blocked` 只回答「是否被封锁」，
「是否已获批准」是另一个谓词（当前 head 上是否存在 APPROVE）。把两者合成一个「可合并」布尔量，
就等于假设「没有 REQUEST_CHANGES = 可以合并」，那是第四种写错的判定。

### 6.2 反例：三种写错的判定，实测何时错误解除封锁

四个判定并排求值。构造序列：head1 上 review 1 = REQUEST_CHANGES，
然后在**同一个 head1** 上再造两条 Review（D003 的身份是四元组，head 不动也能造新行）：

- review 2：base 分支移动 → 新 `review_input_fingerprint`，**head 不变**
- review 3：需求产生新 Revision → 新 `requirement_revision_id`，**head 与 fingerprint 都不变**

```
--- 6a. review 1 on head1 requests changes
 pr_head | correct_blocked | wrong_latest_blocked | wrong_any_approve_blocked | wrong_no_head_blocked
---------+-----------------+----------------------+---------------------------+-----------------------
 head1   | t               | t                    | t                         | t

--- after approving review 2 (same head1, different fingerprint):
 head1   | t               | f                    | f                         | t

--- after approving review 3 (same head1, same fingerprint, older requirement revision):
 head1   | t               | f                    | f                         | t

 id | head_sha | review_input_fingerprint | requirement_revision_id |    decision
----+----------+--------------------------+-------------------------+-----------------
  1 | head1    | fp1                      |                       2 | REQUEST_CHANGES
  2 | head1    | fp2                      |                       2 | APPROVE
  3 | head1    | fp1                      |                       1 | APPROVE
```

**操作序列（不需要改一行代码就能越权）**：

1. Reviewer 对 head1 做 REQUEST_CHANGES，PR 被封锁。
2. 作者**不改代码**，改动 base 分支（或让 LEADER 发一个新的需求 Revision）。
3. 系统按 D003 生成一条新的 Review 身份（**head_sha 仍是 head1**）。
4. 作者/同伙对这条新 Review 点 APPROVE。
5. 判定若写成 `wrong_latest`（看最新一条 Decision）或 `wrong_any_approve`（看有无 APPROVE）
   → **封锁被解除，一行代码都没改**。

第三种写错 `wrong_no_head`（完全不看 head_sha）方向相反：head 换到 head2 之后仍然为 `t`，
**永远解除不了**——不是越权，是产品死锁，但同样是错的。

### 6.3 head 移动与 force-push 回退

```
--- 6c. the head finally moves to head2
 head2   | f               | f                    | f                         | t

--- 6d. FORCE-PUSH BACK to head1: the block must come back
 head1   | t               | f                    | f                         | t
```

**这条是派生式判定优于存储布尔位的决定性证据。** 如果在 `pull_request` 上存一个 `changes_requested`
布尔列并在 head 变化时清掉，那么 force-push 回 head1 之后它仍然是 false，
而 head1 上那条 REQUEST_CHANGES 依然有效——**封锁被一次 force-push 洗掉了**。
派生式判定自动重新封锁。**结论：不要给 `pull_request` 加封锁状态列**
（这也与 D016.1「`pull_request` 上没有可以承载标记的列」的既有立场一致）。

### 6.4 跨 Review 的并发决定（判定漏掉前置 4/5 时才可达）

两个人同时决定同一 PR 的两条不同 Review，**不加 PR 锁**：

```
--- both read 'not blocked':   A: f      B: f
--- A: 10 | REQUEST_CHANGES    UPDATE 1
--- B: 11 | APPROVE            UPDATE 1
 id | head_sha |    decision     | decision_by
----+----------+-----------------+-------------
 10 | head1    | REQUEST_CHANGES |           1
 11 | head1    | APPROVE         |           2
```

同一 head 上同时存在 APPROVE 与 REQUEST_CHANGES。正确判定仍然把 PR 判为封锁（`correct_blocked = t`），
所以**授权后果被 §6.1 的判定兜住了**；但审计上出现了一条不该存在的 APPROVE。

**加上 PR 行锁**，B 拿到锁之后才读前置：

```
--- B is queued:
1232 active Lock/transactionid :: SELECT id, head_sha FROM pull_request WHERE project_id=1 AND id=1 FOR …

--- B:  blocked_now
        -------------
         t
```

B 看到 `t`，可以正确拒绝。**配合 §5.5，PR 行锁在 Phase 7 有两个独立理由，都不可省。**

---

## 7. `finding_event` 的审计约束

`exp7_finding_event.sh` / `exp8_deferrable_and_race.sh`。

### 7.0 列形态：`from/to` 不能是一对泛型列

§2.1 给的是「project_id、finding_id（复合 FK）、actor_id（→ `user_account`）、action、**from/to**、comment、created_at」。
但 Phase 7 要求同时审计**人工状态**与**指派**两个维度。若 `from/to` 是一对泛型 `varchar`，
指派变更就得把 user id 塞进 varchar，于是**拿不到外键**：

```
ERROR:  foreign key constraint "fk_generic_from" cannot be implemented
DETAIL:  Key columns "from_value" and "user_id" are of incompatible types: character varying and bigint.
```

这正是 §2.1「不建通用 `audit_event`（多态 entity_id 无法被 D006 复合外键约束）」拒绝的形态，只是换了个尺度。
因此实测采用**两对类型化列**：`from_status/to_status`（varchar）与 `from_assignee_id/to_assignee_id`（bigint，
带 `(project_id, *) → project_member(project_id, user_id)` 复合 FK）。**这是对 §2.1 的解释性增补，需裁定**（O4）。

### 7.1 `is_a_change`：必须**按 action 分支**

对照批次 2 `V5__scm.sql:114` 的 `ck_pr_requirement_event_is_a_change` 同型写法，但那张表只审计一个维度，
这张表是两个，所以必须分支：

```sql
CONSTRAINT ck_finding_event_is_a_change CHECK (
    (action = 'ASSIGN'
        AND from_status IS NULL AND to_status IS NULL
        AND (from_assignee_id IS NOT NULL OR to_assignee_id IS NOT NULL)
        AND from_assignee_id IS DISTINCT FROM to_assignee_id)
 OR (action <> 'ASSIGN'
        AND from_assignee_id IS NULL AND to_assignee_id IS NULL
        AND from_status IS NOT NULL AND to_status IS NOT NULL
        AND from_status IS DISTINCT FROM to_status))
```

八个用例实测：

| 用例 | 结果 |
|---|---|
| (i) 真实状态变更 `OPEN→REJECTED` | `INSERT 0 1` |
| (ii) 空转 `REJECTED→REJECTED` | `ERROR: … violates check constraint "ck_finding_event_is_a_change"` |
| (iii) 两侧都 NULL | 拒绝 |
| (iv) 状态动作只写单侧（`from` 为 NULL） | 拒绝 |
| (v) 真实指派 `NULL→2` | `INSERT 0 1` |
| (vi) 指派空转 `2→2` | 拒绝 |
| (vii) 取消指派 `2→NULL` | `INSERT 0 1`（是真实变更） |
| (viii) 一行里同时写状态和指派 | 拒绝 |

(iv) 值得单独说：状态类事件要求 `from_status` 与 `to_status` **都非空**，
比 `pull_request_requirement_event`（允许一侧为 NULL，因为「原来没有关联」是合法起点）更严。
理由是 Finding 一诞生就有状态，不存在「原来没有状态」。取消指派 (vii) 则相反，NULL 是合法目标。

### 7.2 项目隔离与 actor

```
--- (i) an event pointing at a finding in another project:
ERROR:  … violates foreign key constraint "fk_finding_event_finding"
DETAIL:  Key (project_id, finding_id)=(2, 1) is not present in table "finding".
--- (ii) assignee that is not a member of this project:
ERROR:  … violates foreign key constraint "fk_finding_event_to_assignee"
DETAIL:  Key (project_id, to_assignee_id)=(1, 3) is not present in table "project_member".
--- (iii) actor who has left the project but still exists as an account (must be allowed):
INSERT 0 1
```

(iii) 正是 §2.3 那句「审计表的 `actor_id` 指向 `user_account`，因为退出项目不能抹掉既成事实」的实测证明。

### 7.3 「只有继承的 SUPPRESSED 可重开」——`CHECK` 不行，约束触发器行

```
--- 7c. can a CHECK express the reopen rule?
ERROR:  cannot use subquery in check constraint
```

约束触发器（读父 Finding 的 `continuity`）：

```
--- (i) reopen a plain REJECTED finding (id 2, continuity NEW) -- must be refused:
ERROR:  only an inherited SUPPRESSED finding may be reopened (continuity=NEW)
 id |  status  | continuity
----+----------+------------
  2 | REJECTED | NEW                    <- 事务回滚，状态没变

--- (ii) reopen an inherited SUPPRESSED finding (id 3) -- must be allowed:
 id | status | continuity
----+--------+------------
  3 | OPEN   | SUPPRESSED

--- (iii) an event that LIES about the resulting status:
ERROR:  event says status is now ACCEPTED, finding says OPEN
```

(iii) 是「每条事件真的记录了一次变化」的**后半条**：`is_a_change` 只保证行内自洽（from ≠ to），
只有触发器能保证**事件与 Finding 的实际状态一致**。两者都要。

### 7.4 写入顺序（D013.12 领域）：约束触发器必须显式 `DEFERRABLE`

`exp7` 里第一次测这条时得出了「`SET CONSTRAINTS ALL DEFERRED` 也救不了」的结论，
**那是错的**——触发器建的时候没写 `DEFERRABLE`，所以 `SET CONSTRAINTS` 根本移动不了它：

```
         tgname          | tgdeferrable | tginitdeferred
-------------------------+--------------+----------------
 tr_finding_event_agrees | f            | f
```

改成 `DEFERRABLE INITIALLY IMMEDIATE` 后重测（`exp8` 8a）：

```
 tr_finding_event_agrees | t            | f

--- event BEFORE the finding update, IMMEDIATE:
ERROR:  event says status is now OPEN, finding says REJECTED
--- event BEFORE the finding update, SET CONSTRAINTS ALL DEFERRED:
INSERT 0 1
UPDATE 1
COMMIT
 id | status
----+--------
  3 | OPEN
--- and deferring still catches a transaction that never makes the change:
ERROR:  event says status is now ACCEPTED, finding says OPEN
```

**读法**：
- 若约定「先改 Finding，再插 event」，`INITIALLY IMMEDIATE` 就够，不需要 deferred。
- 若写入顺序不受控（例如 Hibernate 的 flush 顺序把 INSERT 排在 UPDATE 前——D013.10 记录过这正是它的天然顺序），
  **必须** `DEFERRABLE` + `SET CONSTRAINTS ALL DEFERRED`。
- 延迟**不削弱**约束：那个「只插事件、从不真正改 Finding」的事务照样在提交时被抓住。
- 这与 D013.12 的记录一致，并补上了它没说的一句：**`CREATE CONSTRAINT TRIGGER` 默认 NOT DEFERRABLE。**

### 7.5 审计行的 `from_status` 只有配合条件更新才是真的

**无条件更新**，两人并发处置同一 Finding：

```
--- both read OPEN
=== A ===  UPDATE finding SET status='REJECTED' …   -> REJECTED   UPDATE 1
           INSERT … ('REJECT', 'OPEN', 'REJECTED')                INSERT 0 1
=== B ===  UPDATE finding SET status='ACCEPTED' …   -> ACCEPTED   UPDATE 1
           INSERT … ('ACCEPT', 'OPEN', 'ACCEPTED')                INSERT 0 1

--- the audit trail now claims two different transitions out of OPEN:
 id | action | from_status | to_status | actor_id |             comment
----+--------+-------------+-----------+----------+---------------------------------
 20 | REJECT | OPEN        | REJECTED  |        1 | A rejects
 21 | ACCEPT | OPEN        | ACCEPTED  |        2 | B accepts, from_status is stale
```

两条事件都声称自己是从 `OPEN` 出发的，但真实的第二次转移是 `REJECTED → ACCEPTED`。
**§7.3 的触发器抓不到它**，因为它只校验 `to_status` 与 Finding 现状一致，而 B 的 `to_status` 确实是对的。

**把 `from_status` 放进 WHERE**：

```
=== A ===  UPDATE finding SET status='REJECTED' WHERE … AND status='OPEN'  -> REJECTED  UPDATE 1
--- wait state:
1647 active Lock/transactionid :: UPDATE finding SET status='ACCEPTED' WHERE project_id=1 AND id…
=== B, re-evaluated after A committed ===
 id | status
----+--------
(0 rows)
UPDATE 0

 id | action | from_status | to_status | actor_id
----+--------+-------------+-----------+----------
 22 | REJECT | OPEN        | REJECTED  |        1
```

**结论**：Finding 的每一次人工转移都必须是
`UPDATE finding SET status=:to WHERE project_id=? AND id=? AND status=:from`，行数为 1 才写事件，否则 409。
这与 Review Decision 是同一个模式，也是 §7.3 触发器覆盖不到的那一半。

---

## 8. 未回答的问题、假设、必须裁定的开放项

### 未能测出 / 未测

- **U1｜Hibernate 层全部未验证**（见 §0.1）。三列复合 FK 的实体映射、`@Modifying` bulk update 的行数、
  flush 顺序与 §7.4 的相互作用，都必须在设计阶段用真实 Spring 上下文验证。**这是本文最大的风险敞口。**
- **U2｜没有测约束触发器在 §2.3 父子上下文一致性上的行为与 25P02**（另一份研究的范围）。
- **U3｜没有量 fence 的性能代价**：三列 FK 让每次 Finding 插入多一次 RI 查询并对 `review` 行加
  `FOR KEY SHARE`。在 Phase 6 决定并发 Review 为 1 或 2 时这大概率无关紧要，但没有数据。
- **U4｜没有测 `finding_key` / `evidence_hash` / `basis_hash` 的连续性规则**（§3.6 五条），
  也没有测 `carried_from_finding_id` 的同 PR 不变式。
- **U5｜没有测三个以上并发连接**。所有竞态都是 A/B 两方。三方竞争（两个 Worker + 一个 reconciliation）未测。
- **U6｜没有测 40001 的重试策略**。§3.10 指出 REPEATABLE READ 下会出现序列化失败，
  但本项目实际用什么隔离级别、要不要重试，未定。

### 假设

- **A1**：假设应用使用默认 `READ COMMITTED`。所有「输家 0 行」的结论依赖 EvalPlanQual 重查语义。
  若某处改成 REPEATABLE READ，输家会收到 40001 异常而不是 0 行，**错误映射必须能处理这两种形态**。
- **A2**：假设 Finding 与终局状态在**同一事务**内写入。§3.7 的钉死问题在这个假设下只影响崩溃恢复路径。
- **A3**：假设 `pull_request.head_sha` 只由 SCM 写入，且写入时持有该行锁（批次 2 的乱序保护逻辑未复核）。
  §5.5 的结论依赖「决定方能通过 `FOR UPDATE` 与 SCM 串行化」。**若 SCM 侧更新 PR 时不走该行锁，需另行确认。**
- **A4**：假设「PR 当前关联需求版本」= `requirement.current_revision_id`（沿 `pull_request.requirement_id`）。
  §3.1 第 5 条只写了「pull_request 当前关联需求版本」，没有指明取值路径。**若取值路径不同，§5.6 的实测仍成立，
  但前置 5 的 SQL 要改。**

### 必须由设计阶段裁定的开放项

| # | 开放项 | 备选 | 本文倾向 |
|---|---|---|---|
| **O1** | lease 列名：§2.1 斜杠列表暗示 `execution_lease`，§3.2 逐字写 `lease_until` | 二选一 | `lease_until`（唯一被逐字写出的） |
| **O2** | 是否接受 `review UNIQUE(project_id,id,execution_attempt)` + `finding.review_attempt` + 三列 FK | 接受 / 只保留应用层检查 | **接受**；否则 PRD R2 第四条只能靠有竞态的应用层检查（§3.5 实测破口） |
| **O3** | 接受 O2 后，re-claim 必须同事务删除被放弃 attempt 的 Finding | 删除 / 改用 token 作 fence 列 / 放弃 | **删除**（§3.7 实测可行）；**绝不用 `ON UPDATE CASCADE`**（§3.8） |
| **O4** | `finding_event` 的 `from/to` 用两对类型化列还是一对泛型列 | 类型化 / 泛型 | **类型化**；泛型列拿不到 `project_member` 外键（§7.0 实测） |
| **O5** | Decision 一次性是否加 `BEFORE UPDATE` 触发器兜底 | 条件更新 only / 二者都要 | **二者都要**（§5.3）；只有触发器能在 DB 层强制 §3.1「禁止无条件 save」 |
| **O6** | 领取语句用 `now()` 还是 `clock_timestamp()` | 二选一 | `clock_timestamp()`，或保证 reconciliation 每 Review 一个短事务（§4.4） |
| **O7** | 封锁状态派生还是落库 | 派生 / `pull_request` 加列 | **派生**（§6.3 force-push 实测；亦与 D016.1 立场一致） |
| **O8** | 前置 5 中「PR 当前关联需求版本」的取值路径 | 未定义 | 需明确（A4） |
| **O9** | 40001 映射为哪个 HTTP 状态，是否自动重试 | 未定义 | 需明确；它与约束冲突（409/422）语义不同（§3.10） |

---

## 附：复现

```bash
docker run -d --name fp-fencing -e POSTGRES_PASSWORD=fp -e POSTGRES_DB=fp -p 55433:5432 \
  pgvector/pgvector:0.8.6-pg15-bookworm
cd backend/src/main/resources/db/migration
for f in V1__foundation.sql V2__auth_project.sql V3__requirement.sql V4__knowledge_ai.sql V5__scm.sql; do
  docker exec -i fp-fencing psql -U postgres -d fp -v ON_ERROR_STOP=1 -q < "$f"
done
docker exec -i fp-fencing psql -U postgres -d fp -q < /root/.claude/jobs/e84ffece/tmp/exp_schema.sql
docker exec -i fp-fencing psql -U postgres -d fp -q < /root/.claude/jobs/e84ffece/tmp/exp_seed.sql

bash /root/.claude/jobs/e84ffece/tmp/exp1_complete_fail.sh        # 2   完成/失败/续租
bash /root/.claude/jobs/e84ffece/tmp/exp2_finding_fence.sh        # 3.1-3.5 Finding fence
bash /root/.claude/jobs/e84ffece/tmp/exp3_fence_cost.sh           # 3.7-3.8 代价与 CASCADE 陷阱
bash /root/.claude/jobs/e84ffece/tmp/exp4_lease.sh                # 4   lease
bash /root/.claude/jobs/e84ffece/tmp/exp5_decision.sh             # 5.1-5.3 Decision
bash /root/.claude/jobs/e84ffece/tmp/exp6_block_predicate.sh      # 6   封锁判定与反例
bash /root/.claude/jobs/e84ffece/tmp/exp7_finding_event.sh        # 7.1-7.3 finding_event
bash /root/.claude/jobs/e84ffece/tmp/exp8_deferrable_and_race.sh  # 7.4-7.5 DEFERRABLE 与审计竞态
bash /root/.claude/jobs/e84ffece/tmp/exp9a_decisive.sh            # 3.9 预防性
bash /root/.claude/jobs/e84ffece/tmp/exp9b.sh                     # 3.10 SQLSTATE
bash /root/.claude/jobs/e84ffece/tmp/exp10_control.sh             # 3.6 控制实验
bash /root/.claude/jobs/e84ffece/tmp/exp11_six_preconditions.sh   # 5.4 5.6 六项前置
bash /root/.claude/jobs/e84ffece/tmp/exp12_pr_lock_necessity.sh   # 5.5 PR 行锁必要性

docker rm -f fp-fencing
```

脚本按顺序执行，每个脚本自带 setup；单独跑某一个需要前面的 schema 与种子数据已就位。
`exp10` 会临时增删约束并在结尾恢复，`exp2` 结尾会留下一行违约数据导致 FK 重建失败——`exp3` 开头负责清理。
