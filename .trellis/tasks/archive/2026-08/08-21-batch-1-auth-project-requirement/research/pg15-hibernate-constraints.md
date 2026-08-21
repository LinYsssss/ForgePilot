# Research: PostgreSQL 15 + Hibernate 约束可行性实测

- **Query**: 用真实 PG15 + Hibernate 实测 ForgePilot 数据模型最危险的约束（D006 自标风险：约束触发器与 ORM 可能不兼容）
- **Scope**: internal（实测，非文献）
- **Date**: 2026-08-21

## 实测环境

| 项 | 值 |
|---|---|
| 数据库 | `pgvector/pgvector:0.8.6-pg15-bookworm` → **PostgreSQL 15.19 (Debian 15.19-1.pgdg12+2)** |
| JDK | `eclipse-temurin:21-jdk` → Java 21.0.11 |
| 构建 | `maven:3.9.11-eclipse-temurin-21`，挂载 `/root/.m2`（离线 `-o`） |
| Spring Boot | 4.1.0（与 `backend/pom.xml` 一致） |
| Hibernate ORM | **7.4.1.Final**（Boot 4.1.0 管理版本） |
| JDBC 驱动 | PostgreSQL 42.7.11 |
| Hibernate 配置 | `ddl-auto=validate`、`batch_size=10`、`order_inserts=true`、`order_updates=true` |

所有探针在临时容器 `fp-research-pg15` / 临时网络 `fp-research-net` 中执行，未触碰仓库文件与既有容器。

---

## 结论速览

| # | 项 | 结论 |
|---|---|---|
| 1 | 部分唯一索引 `WHERE role='LEADER'` | **可行**，但**不能 DEFERRABLE**，LEADER 转移必须"先降后升 + 中间 flush" |
| 2 | 复合自引用 FK 回填 | **可行且无需 DEFERRABLE**；反而**必须**保持 `MATCH SIMPLE`（`MATCH FULL` 会让回填不可能） |
| 3 | 可空复合 FK 的 MATCH SIMPLE 跳过语义 | **确认成立**，ARCHITECTURE §2.3 论断正确；L206 的 `ck(ac_id...)` 是堵洞的关键，不可删 |
| 4 | 约束触发器 + `IS NOT DISTINCT FROM` | **可行**，父表更新也能拦住；但 `INITIALLY IMMEDIATE` 下父子协同改写在**任何顺序都不可能** |
| 5 | `UNIQUE NULLS NOT DISTINCT` | **可行**，行为完全符合 D003 |
| 6 | Hibernate 兼容性 | **(a) 可行但有强制映射约定；(b) 可映射为 `DataIntegrityViolationException`，但事务不可续用；(c) 异常类型已测定** |

**D006 的风险条款是否触发？** 约束触发器本身**与 ORM 兼容**，不需要因"触发器不可用"新增决策。但发现了一个**独立的 ORM 映射硬限制**（见 6a-0），它约束所有 16 张表的实体写法，需主会话判断是落 design.md/spec 还是升级为决策。详见 [与现有文档的冲突](#与现有文档的冲突)。

---

## 1. 部分唯一索引：每项目至多一个 LEADER

### DDL

```sql
CREATE TABLE project_member (
  id            bigserial PRIMARY KEY,
  project_id    bigint NOT NULL REFERENCES project(id),
  user_id       bigint NOT NULL,
  role          text   NOT NULL CHECK (role IN ('LEADER','DEVELOPER','REVIEWER')),
  CONSTRAINT uq_member UNIQUE (project_id, user_id)
);
CREATE UNIQUE INDEX uq_project_single_leader
  ON project_member (project_id) WHERE role = 'LEADER';
```

### 实测结果

| 用例 | SQL | 实际输出 |
|---|---|---|
| 1a 首个 LEADER | `INSERT ... (1, 10, 'LEADER')` | `INSERT 0 1` |
| 1b 同项目第二个 LEADER | `INSERT ... (1, 11, 'LEADER')` | **拒绝**，见下 |
| 1c 同项目 DEVELOPER | `INSERT ... (1, 11, 'DEVELOPER')` | `INSERT 0 1` |
| 1d 再加 DEVELOPER + REVIEWER | `INSERT ... (1,12,'DEVELOPER')` / `(1,13,'REVIEWER')` | `INSERT 0 1` ×2（非 LEADER 无上限） |
| 1e 另一项目的 LEADER | `INSERT ... (2, 20, 'LEADER')` | `INSERT 0 1` |
| 1f 先降后升（两条语句） | `UPDATE ... SET role='DEVELOPER' WHERE user_id=10;` 然后 `UPDATE ... SET role='LEADER' WHERE user_id=11;` | `UPDATE 1` / `UPDATE 1` |

1b 的原始错误：

```
ERROR:  23505: duplicate key value violates unique constraint "uq_project_single_leader"
DETAIL:  Key (project_id)=(1) already exists.
SCHEMA NAME:  public
TABLE NAME:  project_member
CONSTRAINT NAME:  uq_project_single_leader
LOCATION:  _bt_check_unique, nbtinsert.c:664
```

**SQLState = 23505**，约束名可用。

### 1g–1i 关键发现：单语句交换是"物理扫描顺序依赖"的

单条 `UPDATE ... SET role = CASE ...` 交换 LEADER 时，结果**取决于行的物理顺序**：

```sql
-- 1h: 旧 LEADER 的 ctid 更小（先被扫描）
--     (0,1)=user100 LEADER, (0,2)=user200 DEVELOPER
UPDATE pm2 SET role = CASE WHEN user_id=100 THEN 'DEVELOPER' ELSE 'LEADER' END WHERE project_id=1;
-- => UPDATE 2   （成功）

-- 1i: 新 LEADER 的 ctid 更小（先被扫描），中途出现瞬时双 LEADER
--     (0,1)=user200 DEVELOPER, (0,2)=user100 LEADER
UPDATE pm3 SET role = CASE WHEN user_id=100 THEN 'DEVELOPER' ELSE 'LEADER' END WHERE project_id=1;
```

1i 实际输出：

```
ERROR:  23505: duplicate key value violates unique constraint "uq_pm3_leader"
DETAIL:  Key (project_id)=(1) already exists.
CONSTRAINT NAME:  uq_pm3_leader
```

**同一条语义等价的 SQL，因行物理顺序不同，一个成功一个失败。**

### 1j 部分唯一索引不能 DEFERRABLE（三种写法全部被拒）

```sql
CREATE UNIQUE INDEX uq_try ON pm2 (project_id) WHERE role='LEADER' DEFERRABLE INITIALLY DEFERRED;
-- ERROR: 42601: syntax error at or near "DEFERRABLE"

ALTER TABLE pm2 ADD CONSTRAINT uq_try_c UNIQUE (project_id) WHERE (role='LEADER') DEFERRABLE INITIALLY DEFERRED;
-- ERROR: 42601: syntax error at or near "WHERE"

ALTER TABLE pm2 ADD CONSTRAINT uq_try_using UNIQUE USING INDEX uq_pm2_leader DEFERRABLE INITIALLY DEFERRED;
-- ERROR: 42809: "uq_pm2_leader" is a partial index
-- DETAIL: Cannot create a primary key or unique constraint using such an index.
```

PostgreSQL 中 `DEFERRABLE` 只属于**约束**，而 `UNIQUE` 约束不支持 `WHERE`；反过来部分索引无法提升为约束。**"可延迟的部分唯一索引"在 PG15 中不存在。**

### 结论

**可行**。但附带两条硬性实现要求：

1. LEADER 转移**必须**拆成两条语句：先 `UPDATE` 降旧 LEADER → **flush/执行** → 再 `UPDATE` 升新 LEADER。不能靠单条 `CASE` 语句，也不能靠 `DEFERRABLE`。
2. 事务中途会出现"零个 LEADER"的瞬间。D004 的"Service 事务保证至少一个"只能在**事务结束时**校验，无法用 immediate 数据库约束表达。

---

## 2. 复合自引用外键回填（`requirement.current_revision_id`）

### DDL（**非 DEFERRABLE**）

```sql
CREATE TABLE requirement (
  id                  bigserial PRIMARY KEY,
  project_id          bigint NOT NULL,
  title               text   NOT NULL,
  current_revision_id bigint NULL,
  CONSTRAINT uq_req_project_id UNIQUE (project_id, id)
);
CREATE TABLE requirement_revision (
  id             bigserial PRIMARY KEY,
  project_id     bigint NOT NULL,
  requirement_id bigint NOT NULL,
  body           text   NOT NULL,
  CONSTRAINT uq_rev_proj_req_id UNIQUE (project_id, requirement_id, id),
  CONSTRAINT fk_rev_requirement FOREIGN KEY (project_id, requirement_id)
    REFERENCES requirement (project_id, id)
);
ALTER TABLE requirement ADD CONSTRAINT fk_req_current_revision
  FOREIGN KEY (project_id, id, current_revision_id)
  REFERENCES requirement_revision (project_id, requirement_id, id);
```

确认约束属性（`condeferrable=f`、`confmatchtype=s` 即 MATCH SIMPLE）：

```
         conname         | condeferrable | condeferred | confmatchtype
-------------------------+---------------+-------------+---------------
 fk_rev_requirement      | f             | f           | s
 fk_req_current_revision | f             | f           | s
```

### 2b 三步回填（非 DEFERRABLE）— **成功**

```sql
BEGIN;
INSERT INTO requirement (id, project_id, title, current_revision_id) VALUES (1, 1, 'REQ-1', NULL);
INSERT INTO requirement_revision (id, project_id, requirement_id, body) VALUES (1, 1, 1, 'v1 body');
UPDATE requirement SET current_revision_id = 1 WHERE id = 1;
COMMIT;
```

```
BEGIN
INSERT 0 1
INSERT 0 1
UPDATE 1
COMMIT
 id | project_id | current_revision_id
----+------------+---------------------
  1 |          1 |                   1
```

**为什么不需要 DEFERRABLE**：第一步 `current_revision_id IS NULL`，`MATCH SIMPLE` 直接跳过整个复合 FK 检查（见第 3 项）。这不是巧合，而是设计上依赖的机制。

### 2f 版本推进（新 Revision → 重指）— 成功

```sql
BEGIN;
INSERT INTO requirement_revision (id, project_id, requirement_id, body) VALUES (3, 1, 1, 'v2 body');
UPDATE requirement SET current_revision_id = 3 WHERE id = 1;
COMMIT;   -- INSERT 0 1 / UPDATE 1 / COMMIT
```

### 拒绝用例（全部 SQLState 23503）

| 用例 | SQL | 实际输出 |
|---|---|---|
| 2a 悬空 revision | `INSERT INTO requirement (id,project_id,title,current_revision_id) VALUES (500,1,'R-bad',999);` | `ERROR: 23503 ... DETAIL: Key (project_id, id, current_revision_id)=(1, 500, 999) is not present in table "requirement_revision".` |
| 2d 回填**别的 requirement** 的 revision | `UPDATE requirement SET current_revision_id = 2 WHERE id = 1;` | `ERROR: 23503 ... Key (project_id, id, current_revision_id)=(1, 1, 2) is not present ...` |
| 2e 跨项目回填 | `UPDATE requirement SET current_revision_id = 1 WHERE id = 3;`（req 3 在 project 9） | `ERROR: 23503 ... Key (project_id, id, current_revision_id)=(9, 3, 1) is not present ...` |
| 2g 删除在用 revision | `DELETE FROM requirement_revision WHERE id = 3;` | `ERROR: 23503 ... Key (project_id, requirement_id, id)=(1, 1, 3) is still referenced from table "requirement".` |

全部错误的 `CONSTRAINT NAME: fk_req_current_revision`。**2d 正是任务要求验证的"回填别的 requirement 的 revision 会被拒"，已确认。**

### 2i 反证：`MATCH FULL` 会让整个回填设计不可能

```sql
ALTER TABLE req_mf ADD CONSTRAINT fk_mf FOREIGN KEY (project_id,id,current_revision_id)
  REFERENCES rev_mf(project_id,requirement_id,id) MATCH FULL;
INSERT INTO req_mf (id,project_id,current_revision_id) VALUES (1,1,NULL);
```

```
ERROR:  23503: insert or update on table "req_mf" violates foreign key constraint "fk_mf"
DETAIL:  MATCH FULL does not allow mixing of null and nonnull key values.
LOCATION:  RI_FKey_check, ri_triggers.c:305
```

因为引用列里 `project_id`、`id` 恒为 NOT NULL，只有 `current_revision_id` 可空，`MATCH FULL` 永远不允许这种混合。**`MATCH SIMPLE`（默认）不是将就，是必需项。**

### 结论

**可行，且无需 DEFERRABLE。** 反向要求：这条 FK **禁止**写成 `MATCH FULL`，也**不建议**改成 `DEFERRABLE`（没有必要，且会把错误推迟到 COMMIT，见 6b-6 的坏处）。

---

## 3. 可空复合外键的 `MATCH SIMPLE` 跳过语义

### 最小反例

```sql
CREATE TABLE ms_parent (project_id bigint NOT NULL, id bigint NOT NULL, PRIMARY KEY (project_id,id));
INSERT INTO ms_parent VALUES (1,1);
CREATE TABLE ms_child (
  tag        text PRIMARY KEY,
  project_id bigint,
  parent_id  bigint,
  CONSTRAINT fk_ms FOREIGN KEY (project_id, parent_id) REFERENCES ms_parent (project_id, id)
);
-- pg_constraint.confmatchtype = 's'  (MATCH SIMPLE, 默认)
```

| 用例 | 值 | 结果 |
|---|---|---|
| 3a | `(1, 1)` 合法 | `INSERT 0 1` |
| 3b | `(1, 999)` 两列非空、parent 不存在 | `ERROR: 23503 ... Key (project_id, parent_id)=(1, 999) is not present in table "ms_parent".` |
| 3c | `(1, NULL)` | `INSERT 0 1`（跳过） |
| 3d | **`(99999, NULL)`** project 99999 根本不存在 | **`INSERT 0 1` — 被接受** |
| 3e | **`(NULL, 999)`** parent 999 根本不存在 | **`INSERT 0 1` — 被接受** |
| 3f | `(NULL, NULL)` | `INSERT 0 1`（跳过） |

存活行：

```
 tag | project_id | parent_id
-----+------------+-----------
 a   |          1 |         1
 c   |          1 |
 d   |      99999 |            <-- 不存在的 project
 e   |            |       999  <-- 不存在的 parent
 f   |            |
```

**3d/3e 是决定性证据**：只要复合外键中**任一**列为 NULL，PostgreSQL 就跳过整个约束检查，另一列可以是完全不存在的垃圾值。

### 3h–3l ForgePilot 形态验证（Finding）

用 ARCHITECTURE 的真实形状建表：`finding` 带 `(project_id,review_id) → review(project_id,id)` 父 FK，以及可空的 `(project_id, requirement_revision_id, ac_id) → acceptance_criterion(...)`。

| 用例 | 结果 |
|---|---|
| 3h `review_id=1`, rev=1, ac=100（合法） | `INSERT 0 1` |
| 3i rev=1 非空 + `ac_id=777` 不存在 | `ERROR: 23503 ... fk_finding_ac ... Key (project_id, requirement_revision_id, ac_id)=(1, 1, 777) is not present` |
| 3j **rev=NULL + `ac_id=777` 不存在** | **`INSERT 0 1` — AC 外键被完全跳过，悬空 ac_id 落库** |
| 3k 去掉父 FK 的假设（`review_id=4242` 不存在） | `ERROR: 23503 ... fk_finding_review ... Key (project_id, review_id)=(1, 4242) is not present` |

**3j 精确复现了 ARCHITECTURE §2.3 担心的场景**，3k 证明只有那条永久父 FK 真正挡住了悬空父引用。

### 3m–3n ARCHITECTURE L206 的 CHECK 是堵洞关键

加上 ARCHITECTURE 第 206 行已经规定的 CHECK 后重试 3j：

```sql
ALTER TABLE finding ADD CONSTRAINT ck_finding_ac_needs_rev
  CHECK (ac_id IS NULL OR requirement_revision_id IS NOT NULL);

INSERT INTO finding VALUES (3,1,2, NULL,NULL, 777);
```

```
ERROR:  23514: new row for relation "finding" violates check constraint "ck_finding_ac_needs_rev"
DETAIL:  Failing row contains (3, 1, 2, null, null, 777).
CONSTRAINT NAME:  ck_finding_ac_needs_rev
```

### 结论

**ARCHITECTURE §2.3 的论断完全正确，实测无异议。** 附加确认：L206 的 `CHECK (ac_id IS NULL OR requirement_revision_id IS NOT NULL)` **不是装饰性约束**，删掉它就会漏进悬空 `ac_id`。design.md 应把它标为不可省略。

---

## 4. 约束触发器 + `IS NOT DISTINCT FROM`

### 触发器定义（子表侧 + 父表侧）

```sql
CREATE OR REPLACE FUNCTION fp_finding_ctx_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r_req bigint; r_rev bigint; found_it boolean;
BEGIN
  SELECT requirement_id, requirement_revision_id, true INTO r_req, r_rev, found_it
    FROM review WHERE project_id = NEW.project_id AND id = NEW.review_id;
  IF NOT COALESCE(found_it,false) THEN
    RAISE EXCEPTION 'finding %: parent review (%,%) not found', NEW.id, NEW.project_id, NEW.review_id
      USING ERRCODE='23514', CONSTRAINT='ck_finding_matches_review_context';
  END IF;
  IF NOT (NEW.requirement_id          IS NOT DISTINCT FROM r_req
      AND NEW.requirement_revision_id IS NOT DISTINCT FROM r_rev) THEN
    RAISE EXCEPTION 'finding % context (req=%, rev=%) does not match review % context (req=%, rev=%)',
      NEW.id, NEW.requirement_id, NEW.requirement_revision_id, NEW.review_id, r_req, r_rev
      USING ERRCODE='23514', CONSTRAINT='ck_finding_matches_review_context';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_finding_ctx
  AFTER INSERT OR UPDATE OF project_id, review_id, requirement_id, requirement_revision_id
  ON finding DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION fp_finding_ctx_guard();

-- 父表侧：更新 Review 上下文列不得孤立既有 Finding
CREATE OR REPLACE FUNCTION fp_review_ctx_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bad bigint;
BEGIN
  SELECT count(*) INTO bad FROM finding f
   WHERE f.project_id = NEW.project_id AND f.review_id = NEW.id
     AND NOT (f.requirement_id          IS NOT DISTINCT FROM NEW.requirement_id
          AND f.requirement_revision_id IS NOT DISTINCT FROM NEW.requirement_revision_id);
  IF bad > 0 THEN
    RAISE EXCEPTION 'review % context update would orphan % finding(s)', NEW.id, bad
      USING ERRCODE='23514', CONSTRAINT='ck_review_context_stable';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_review_ctx
  AFTER UPDATE OF requirement_id, requirement_revision_id ON review
  DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION fp_review_ctx_guard();
```

注册确认：

```
     tgname      | is_constraint_trigger | tgdeferrable | tginitdeferred
-----------------+-----------------------+--------------+----------------
 trg_finding_ctx | t                     | t            | f
 trg_review_ctx  | t                     | t            | f
```

### 子表侧实测（R1 = req 1/rev 1；R2 = NULL/NULL）

| 用例 | 结果 |
|---|---|
| 4a Finding on R1 = `(1,1)` | `INSERT 0 1` |
| 4b Finding on R1 = `(2,2)` | `ERROR: 23514: finding 11 context (req=2, rev=2) does not match review 1 context (req=1, rev=1)` |
| 4c Finding on R1 = `(NULL,NULL)` | `ERROR: 23514: finding 12 context (req=<NULL>, rev=<NULL>) does not match review 1 context (req=1, rev=1)` |
| 4d **Finding on R2 = `(NULL,NULL)`** | **`INSERT 0 1` — NULL/NULL 视为相等** |
| 4e Finding on R2 = `(1,1)` | `ERROR: 23514: finding 14 context (req=1, rev=1) does not match review 2 context (req=<NULL>, rev=<NULL>)` |
| 4f' `UPDATE` 子表脱离父上下文 | `ERROR: 23514: finding 13 context (req=1, rev=1) does not match review 2 context (req=<NULL>, rev=<NULL>)` |

4d 确认 `IS NOT DISTINCT FROM` 的 NULL-safe 语义生效；4b/4c/4e 确认双向都拦。

### 父表侧更新实测（ARCHITECTURE §2.3 明确要求）

| 用例 | 结果 |
|---|---|
| 4g `UPDATE review SET (req,rev)=(2,2) WHERE id=1`（2 个 Finding 挂着 `(1,1)`） | `ERROR: 23514: review 1 context update would orphan 2 finding(s)` |
| 4h `UPDATE review SET (req,rev)=(1,1) WHERE id=2`（Finding 是 NULL/NULL） | `ERROR: 23514: review 2 context update would orphan 1 finding(s)` |
| 4i 无 Finding 的 Review 改上下文 | `UPDATE 1`（放行） |

**父表更新可以被拦住，ARCHITECTURE 的要求可实现。**

### 4j–4m 关键发现：`INITIALLY IMMEDIATE` 下父子协同改写在任何顺序都不可能

```sql
-- 4j 父先改
BEGIN;
UPDATE review  SET requirement_id=2, requirement_revision_id=2 WHERE id=1;
-- ERROR: 23514: review 1 context update would orphan 2 finding(s)
-- ERROR: 25P02: current transaction is aborted, commands ignored until end of transaction block
ROLLBACK

-- 4k 子先改
BEGIN;
UPDATE finding SET requirement_id=2, requirement_revision_id=2, ac_id=NULL WHERE review_id=1;
-- ERROR: 23514: finding 1 context (req=2, rev=2) does not match review 1 context (req=1, rev=1)
ROLLBACK
```

只有显式延迟才能协同改写：

```sql
-- 4l
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
UPDATE finding SET requirement_id=2, requirement_revision_id=2, ac_id=NULL WHERE review_id=1;  -- UPDATE 2
UPDATE review  SET requirement_id=2, requirement_revision_id=2 WHERE id=1;                      -- UPDATE 1
COMMIT;   -- COMMIT 成功
```

```
    t    | id | requirement_id | requirement_revision_id
---------+----+----------------+-------------------------
 finding |  1 |              2 |                       2
 finding | 10 |              2 |                       2
 review  |  1 |              2 |                       2
```

延迟后仍在 COMMIT 时真正复查（4m）：

```sql
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
UPDATE review SET requirement_id=1, requirement_revision_id=1 WHERE id=1;  -- UPDATE 1（语句通过）
COMMIT;
-- ERROR: 23514: review 1 context update would orphan 2 finding(s)
```

### 4n–4o SAVEPOINT 可恢复性（纯 SQL 层）

- 4n：IMMEDIATE 触发器报错后 `ROLLBACK TO SAVEPOINT sp1` → 事务可继续，后续 `INSERT 0 1` 并成功 `COMMIT`。
- 4o：DEFERRED 状态下 `SET CONSTRAINTS ALL IMMEDIATE` 会**立刻**暴露违规（不必等到 COMMIT），随后 `ROLLBACK TO SAVEPOINT` 同样可恢复。

### 结论

**可行。** 三点实现约束：

1. `INITIALLY IMMEDIATE` 事实上让 Review 的 `requirement_id`/`requirement_revision_id` 在有 Finding 后**不可变**——这与 ARCHITECTURE §2.3 给出的备选方案"直接拒绝这些身份列在创建后变化"殊途同归。鉴于 D003 规定 Review 身份不可变、旧 Review 永不覆盖，这是**期望行为**，建议明确采纳 `INITIALLY IMMEDIATE` 并在 design.md 写明"Review 上下文列创建后不可改"。
2. 若将来真的需要协同改写，唯一手段是 `SET CONSTRAINTS ALL DEFERRED`，代价是错误推迟到 COMMIT（见 6b-6：那时 Hibernate 约束名会丢失）。
3. `RAISE ... USING CONSTRAINT='...'` 必须写，否则应用层拿不到可判别的名字（见 6b-8）。

---

## 5. `UNIQUE NULLS NOT DISTINCT`（D003）

### DDL

```sql
CREATE TABLE review_identity (
  id bigserial PRIMARY KEY,
  pull_request_id bigint NOT NULL,
  head_sha text NOT NULL,
  review_input_fingerprint text NOT NULL,
  requirement_revision_id bigint NULL,
  CONSTRAINT uq_review_identity
    UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)
);
```

语法被接受，且元数据确认：

```
      relname       | indnullsnotdistinct
--------------------+---------------------
 uq_review_identity | t
```

### 实测

| 用例 | 值 | 结果 |
|---|---|---|
| 5a 首行，revision=NULL | `(1,'abc','fp1',NULL)` | `INSERT 0 1` |
| 5b **完全相同，revision 仍 NULL** | `(1,'abc','fp1',NULL)` | **拒绝** |
| 5c 换 fingerprint | `(1,'abc','fp2',NULL)` | `INSERT 0 1` |
| 5d 同 PR/head/fp，revision=7 | `(1,'abc','fp1',7)` | `INSERT 0 1`（不同身份） |
| 5e 5d 的重复 | `(1,'abc','fp1',7)` | 拒绝 |

5b 原始错误：

```
ERROR:  23505: duplicate key value violates unique constraint "uq_review_identity"
DETAIL:  Key (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)=(1, abc, fp1, null) already exists.
CONSTRAINT NAME:  uq_review_identity
```

### 5f 对照组：默认 `NULLS DISTINCT` 会漏掉重复

同样三行插入到只用普通 `UNIQUE` 的表：

```
INSERT 0 3
 leaked_duplicates
-------------------
                 3
```

**默认语义下三条完全相同的 Review 身份全部落库。**

### 附：另一条 PG15 硬依赖（D010 列级 `ON DELETE SET NULL`）

顺带一并确认了 ARCHITECTURE 第 448 行所述的第二个理由：

```sql
CONSTRAINT fk_pr_author FOREIGN KEY (project_id, author_user_id)
  REFERENCES pm_d10 (project_id, user_id) ON DELETE SET NULL (author_user_id)
```

删除成员后：

```
 id | project_id | author_user_id
----+------------+----------------
  1 |          1 |                 <-- project_id 保留，只有 author_user_id 被置空
```

不带列清单的写法则失败：

```
ERROR:  23502: null value in column "project_id" of relation "pr_d10_plain" violates not-null constraint
CONTEXT:  SQL statement "UPDATE ONLY ... SET "project_id" = NULL, "author_user_id" = NULL ..."
```

### 结论

**可行，行为完全符合 D003 预期。** PG15 的两条硬依赖（`UNIQUE NULLS NOT DISTINCT` 与列级 `ON DELETE SET NULL`）均已实测成立，ARCHITECTURE 第 448 行的"最低版本 15"论断有据。

---

## 6. Hibernate 兼容性（最关键）

探针为完整 Spring Boot 4.1.0 + Spring Data JPA 应用（非仅 JDBC），实体覆盖 `requirement` / `requirement_revision` / `project_member` / `review` / `finding`，schema 与上文一致。

### 6a-0 【最重要发现】复合 `@ManyToOne` 的可插入性必须一致

最自然的映射写法——`project_id`、`id` 只读、`current_revision_id` 可写——**被 Hibernate 在启动期直接拒绝**：

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumns({
    @JoinColumn(name="project_id",          referencedColumnName="project_id",     insertable=false, updatable=false),
    @JoinColumn(name="id",                  referencedColumnName="requirement_id", insertable=false, updatable=false),
    @JoinColumn(name="current_revision_id", referencedColumnName="id")   // 可写
})
RequirementRevision currentRevision;
```

```
Caused by: org.hibernate.AnnotationException: Column mappings for property 'currentRevision'
    mix insertable with 'insertable=false'
	at org.hibernate.boot.model.internal.AnnotatedColumns.checkPropertyConsistency(AnnotatedColumns.java:169)
	at org.hibernate.boot.model.internal.ToOneBinder.processManyToOneProperty(ToOneBinder.java:295)
```

**应用无法启动**（`BeanCreationException: Error creating bean with name 'entityManagerFactory'`）。

另一个自然写法——关联与标量都可写——同样被拒：

```
Caused by: org.hibernate.MappingException: Column 'project_id' is duplicated in mapping for
    entity 'probe.RevisionVariantC' (use '@Column(insertable=false, updatable=false)'
    when mapping multiple properties to the same column)
```

因为 ForgePilot **每一条**项目内外键都把 `project_id` 带进复合键，而 `project_id` 同时又是实体自己的标量字段，**这个冲突会出现在 16 张表的几乎每一个关联上**。只有两种合法形态：

| 变体 | 写法 | 实测 |
|---|---|---|
| **A** 关联只读 + 标量可写 | 三个 `@JoinColumn` 全部 `insertable=false, updatable=false`，另加 `@Column(name="current_revision_id") Long currentRevisionId` 负责写 | **可用**（6a-3/6a-4/6a-8 通过） |
| **B** 关联可写 + 标量只读 | `@JoinColumn` 全部可写，`@Column(name="project_id", insertable=false, updatable=false) Long projectId` 只作镜像 | **可用**（6a-8/6a-9 通过） |
| C 两者都可写 | — | **启动失败**（`MappingException: Column 'project_id' is duplicated`） |
| D 关联内部混合可插入性 | — | **启动失败**（`AnnotationException: mix insertable`） |

变体 B 的实际 SQL（`project_id` 由关联目标推导，服务代码根本没机会写错项目）：

```
insert into requirement_revision (body,requirement_id,project_id,id) values (?,?,?,?)
  [OK] variant B wrote rev id=152 project_id mirror=1 requirement=154
```

变体 B 的读路径 Hibernate 也能正确生成复合条件：

```sql
select rs1_0.id, rs1_0.project_id, rs1_0.current_revision_id, rs1_0.title, rs1_0.version
  from requirement rs1_0 where (rs1_0.id, rs1_0.project_id) in ((?,?))
```

### 6a 回填的 insert 顺序 — **Hibernate 天然满足**

关键问题：开发者写"自然"代码（persist requirement → persist revision → 设回填 → 一次 flush），Hibernate 会不会把 `current_revision_id` 已赋值的 requirement 先 INSERT 进去从而撞 FK？

**不会。** Hibernate 在 `persist()` 时快照实体状态生成 `EntityInsertAction`，flush 时的脏检查再补一条 UPDATE；而 flush 内部顺序固定为 **所有 INSERT 先于所有 UPDATE**。开启 `org.hibernate.orm.jdbc.bind=TRACE` 的实证（6a-7，三步全在一次 flush）：

```
insert into requirement (current_revision_id,project_id,title,version,id) values (?,?,?,?,?)
  binding parameter (1:BIGINT) <- [null]          <-- current_revision_id 是 NULL
  binding parameter (2:BIGINT) <- [1]
  binding parameter (3:VARCHAR) <- [a7]
  binding parameter (4:BIGINT) <- [0]
  binding parameter (5:BIGINT) <- [103]
insert into requirement_revision (body,project_id,requirement_id,id) values (?,?,?,?)
  binding parameter (3:BIGINT) <- [103]
  binding parameter (4:BIGINT) <- [53]
update requirement set current_revision_id=?,project_id=?,title=?,version=? where id=? and version=?
  binding parameter (1:BIGINT) <- [53]            <-- 回填
  [OK] all-three-in-one-flush OK, req=103 rev=53
```

四种写法全部通过：

| 场景 | 结果 |
|---|---|
| 6a-1 标量映射，自然顺序（先设值再一次 flush） | **OK**，SQL 序为 INSERT(NULL) → INSERT rev → UPDATE |
| 6a-2 标量映射，每步显式 flush | **OK** |
| 6a-3 关联映射（变体 A），自然顺序 | **OK**，`assoc-resolves-to=3` |
| 6a-6 已持久化实体 + 未 flush 的新 revision，一次 flush | **OK**（INSERT 先于 UPDATE） |
| 6a-7 三步全在一次 flush | **OK** |

跨 requirement 回填仍被数据库拒绝（6a-5）：

```
[0] org.hibernate.exception.ConstraintViolationException  constraintName=fk_req_current_revision
[1] java.sql.BatchUpdateException  SQLState=23503
[2] org.postgresql.util.PSQLException  SQLState=23503
    ERROR: insert or update on table "requirement" violates foreign key constraint "fk_req_current_revision"
    Detail: Key (project_id, id, current_revision_id)=(1, 54, 5) is not present in table "requirement_revision".
```

**(a) 结论：可行。** 前提是采用变体 A 或 B 的映射约定；insert 顺序不需要任何 `@OrderColumn` / 手工控制，Hibernate 的 flush 语义已经保证。

### 6b 约束触发器的异常映射

| 场景 | 顶层异常 | SQLState | Hibernate `constraintName` |
|---|---|---|---|
| 6b-1 触发器违规 + `repository.saveAndFlush()` | `org.springframework.dao.DataIntegrityViolationException` | 23514 | **null** |
| 6b-2 触发器违规，无显式 flush，COMMIT 时暴露 | `org.springframework.dao.DataIntegrityViolationException` | 23514 | **null** |
| 6b-4 真 CHECK 约束 `ck_finding_ctx` | `DataIntegrityViolationException` | 23514 | `ck_finding_ctx` |
| 6b-6 `SET CONSTRAINTS ALL DEFERRED` → COMMIT 失败 | `DataIntegrityViolationException`（直接包 `PSQLException`，**没有** Hibernate 层） | 23514 | 不可得 |
| 6b-7 `SET CONSTRAINTS ALL IMMEDIATE` 强制提前 | `org.hibernate.exception.ConstraintViolationException`（未经 Spring 翻译） | 23514 | null |
| 6c-1 部分唯一索引 | `DataIntegrityViolationException` | 23505 | `uq_project_single_leader` |

6b-1 完整链：

```
[0] org.springframework.dao.DataIntegrityViolationException
[1] org.hibernate.exception.ConstraintViolationException  constraintName=null
[2] java.sql.BatchUpdateException  SQLState=23514 code=0
[3] org.postgresql.util.PSQLException  SQLState=23514 code=0
    ERROR: finding 1 context (req=1, rev=1) does not match review 1 context (req=<NULL>, rev=<NULL>)
    Where: PL/pgSQL function fp_finding_ctx_guard() line 12 at RAISE
```

#### 6b-8 为什么 `constraintName` 是 null，以及正确取法

裸 JDBC 探针显示**约束名其实在协议里**：

```
class          = org.postgresql.util.PSQLException
SQLState       = 23514
errorCode      = 0
serverMessage  = finding 151 context (req=1, rev=1) does not match review 151 context (req=<NULL>, rev=<NULL>)
constraint     = ck_finding_matches_review_context
table          = null
schema         = null
routine        = exec_stmt_raise
```

对照真 CHECK 约束（6b-9）：

```
SQLState   = 23514
constraint = ck_finding_ctx
table      = finding
```

原因：Hibernate 的 PostgreSQL `ViolatedConstraintNameExtractor` 是**按消息文本模板**抓取的（找 `violates check constraint "..."` 之类的字样），而 `RAISE EXCEPTION` 的自定义消息里没有这个模板，所以抓空；但 `USING CONSTRAINT=` 已经把名字放进了协议字段。

**可行做法**：错误映射层不要依赖 `ConstraintViolationException.getConstraintName()`，而是解包到 `PSQLException.getServerErrorMessage().getConstraint()`。触发器侧则**必须**在每个 `RAISE` 上写 `USING CONSTRAINT='...'`。注意触发器错误的 `table`/`schema` 均为 `null`，不能按表名分发。

#### 6b-3 / 6b-5 【重要】事务不可"捕获后继续"

6b-3：在 `@Transactional` 方法内 `try/catch` 住 `DataIntegrityViolationException`，然后写一条合法记录：

```
[caught inside tx] org.springframework.dao.DataIntegrityViolationException
[0] org.springframework.orm.jpa.JpaSystemException
[1] org.hibernate.exception.GenericJDBCException
[2] java.sql.BatchUpdateException  SQLState=25P02
[3] org.postgresql.util.PSQLException  SQLState=25P02
    ERROR: current transaction is aborted, commands ignored until end of transaction block
[4] org.postgresql.util.PSQLException  SQLState=23514  (原始触发器错误)
```

6b-5：即使手工加 JDBC `Savepoint` 并 `con.rollback(sp)`，**SQL 层恢复成功**（合法 Finding 插入成功），但 Spring 在提交阶段仍然抛：

```
[caught] DataIntegrityViolationException
[rolled back to savepoint]
[OK]   recovered inside same tx, inserted finding id=53
[FAIL] [0] org.springframework.transaction.UnexpectedRollbackException
           msg: Transaction silently rolled back because it has been marked as rollback-only
```

原因：JPA 规范要求 `flush()` 失败时把事务标记为 rollback-only，Spring 在 commit 时据此抛 `UnexpectedRollbackException`。裸 JDBC 同样确认无 savepoint 时不可恢复（6b-8：`recoverable = NO SQLState=25P02`）。

**(b) 结论：可行但有明确边界。** 触发器错误**能**被 Spring 映射成可捕获的 `DataIntegrityViolationException`（SQLState 23514），完全够做 409/422 映射；但**不能**在同一个 `@Transactional` 里捕获后继续干活。约束违规必须让它冒泡出事务边界，由 `@ControllerAdvice` 统一映射。若某个业务流程确实需要"试一下失败就换路径"，必须用 `Propagation.REQUIRES_NEW` 把风险写入隔离到独立事务，或改为先 `SELECT` 预检。

### 6c 部分唯一索引与乐观锁的异常类型

| 场景 | 异常 | SQLState |
|---|---|---|
| 6c-1 插入第二个 LEADER | `DataIntegrityViolationException` → `ConstraintViolationException(constraintName=uq_project_single_leader)` → `BatchUpdateException` → `PSQLException` | 23505 |
| 6c-2 `@Version` 乐观锁冲突 | `org.springframework.orm.ObjectOptimisticLockingFailureException` → `org.hibernate.StaleObjectStateException`（"Row was already updated or deleted by another transaction for entity [probe.ProjectMember with id '3']"） | 无 SQLState（非 SQL 错误） |
| 6c-3 先升后降（顺序错误） | `ConstraintViolationException(constraintName=uq_project_single_leader)` | 23505 |
| 6c-4 先降 → flush → 再升 | **成功** | — |

**两者不会"打架"**：乐观锁冲突走 `ObjectOptimisticLockingFailureException`（可映射 409 Conflict），唯一索引冲突走 `DataIntegrityViolationException`/23505（可映射 409/422），异常类型完全可区分。注意 6c-3 的顶层是**未经 Spring 翻译**的 `org.hibernate.exception.ConstraintViolationException`——因为它由 `em.flush()` 直接触发，而非 Spring Data 仓储代理；只有走 `repository.*` 或事务提交路径才会得到 `DataIntegrityViolationException`。**错误映射必须同时处理这两种类型。**

#### 6c-5 / 6c-6 【重要】单次 flush 的 LEADER 转移是主键顺序依赖的

两个实体都置脏、只 flush 一次：

- 6c-5：旧 LEADER 的主键较小 → 其"降级" UPDATE 先执行 → **成功**。
- 6c-6：把持久化顺序反过来（新 LEADER 的主键较小），并先置脏"升级" → **失败**：

```
[0] org.hibernate.exception.ConstraintViolationException  constraintName=uq_project_single_leader
[1] java.sql.BatchUpdateException  SQLState=23505
    Batch entry 0 update project_member set project_id=('2'::int8),role=('LEADER'),user_id=('821'::int8),
      version=('1'::int8) where id=('54'::int8) and version=('0'::int8) was aborted:
    ERROR: duplicate key value violates unique constraint "uq_project_single_leader"
    Detail: Key (project_id)=(2) already exists.
```

`hibernate.order_updates=true` 按**实体名 + 主键**排序 UPDATE，与业务代码置脏的先后**无关**。也就是说：单次 flush 的 LEADER 转移能否成功，取决于两行成员记录谁的主键更小——这与第 1 项 SQL 层的 ctid 顺序依赖是同一个陷阱在 ORM 层的翻版。

**(c) 结论：可行，异常类型明确。** 但 LEADER 转移**必须**写成"降级 → `flush()` → 升级"，绝不能依赖单次 flush 的自动排序。

---

## 对 design.md 的具体建议

### 必须写入 design.md 的硬约束

1. **实体映射约定（全局）**：所有含 `project_id` 的复合外键关联，统一采用**变体 A**（`@JoinColumn` 全部 `insertable=false, updatable=false`，另用标量 `Long xxxId` 承担写入）或**变体 B**（关联持有列，标量 `projectId` 标 `insertable=false, updatable=false`）。二者择一并全局统一，禁止混用。变体 B 在防跨项目写入上更强（`project_id` 由关联目标推导），变体 A 在批量写入/DTO 映射上更简单。**必须在 spec 里固化，否则第一个写复合关联的人就会撞上启动期 `AnnotationException`。**
2. **LEADER 转移的执行顺序**：Service 必须"先降旧 LEADER → `flush()` → 再升新 LEADER"。禁止单条 `CASE` UPDATE，禁止依赖单次 flush 排序。部分唯一索引**不能** DEFERRABLE，没有别的兜底。
3. **Requirement 回填流程**：保持 `INSERT(current_revision_id=NULL) → INSERT revision → UPDATE 回填` 三步。FK 保持默认 `MATCH SIMPLE` 且**非 DEFERRABLE**；显式注明禁止改 `MATCH FULL`。Hibernate 的 flush 顺序天然满足，无需特殊代码。
4. **约束触发器的 `RAISE`**：每个 `RAISE EXCEPTION` 必须带 `USING ERRCODE='23514', CONSTRAINT='<稳定名字>'`。错误映射层解包到 `PSQLException.getServerErrorMessage().getConstraint()` 取名，**不要**用 Hibernate 的 `getConstraintName()`（触发器场景恒为 null）。
5. **触发器保持 `DEFERRABLE INITIALLY IMMEDIATE`**：等价于"Review 上下文列创建后不可变"，与 D003 一致。design.md 应明写这条推论，并注明如需协同改写只能靠 `SET CONSTRAINTS ALL DEFERRED`（代价：错误推迟到 COMMIT 且丢失约束名）。
6. **错误映射层必须同时处理两种异常形态**：`org.springframework.dao.DataIntegrityViolationException`（走仓储/提交路径）与裸 `org.hibernate.exception.ConstraintViolationException`（走 `em.flush()` 路径），外加 `ObjectOptimisticLockingFailureException`。
7. **禁止"捕获约束异常后继续同一事务"**：写进编码规范。需要探测性写入时用 `Propagation.REQUIRES_NEW` 或先 `SELECT` 预检。

### 建议保留/强调的现有约束

8. ARCHITECTURE L206 的 `CHECK (ac_id IS NULL OR requirement_revision_id IS NOT NULL)` 标注为**不可省略**（实测 3j/3n：删掉就漏悬空 `ac_id`）。
9. `finding` 的 `(project_id, review_id) → review(project_id,id)` 父 FK 标注为**不可省略**（实测 3k）。

### 建议补充的测试

10. Phase 1 迁移落地后，把本文 1b/1i、2b/2d、3d/3e、4b/4d/4g、5b 做成 migration 级集成测试。它们全部是**一行 SQL + 一个断言**，成本极低，但正是 D006 要求的"约束的反馈回路"。

---

## 与现有文档的冲突

以下三条需要主会话判断是否需要新增决策，本文**未**修改任何文档。

### C1. D006 风险条款的判定（需主会话确认口径）

D006 后果条款：「若实施阶段发现约束触发器与 ORM 无法兼容，必须先新增决策，不得静默降级为无测试的 Service 纪律」。

实测结论是**约束触发器与 Hibernate 兼容**（6b-1/6b-2/6b-4 均正确映射为可捕获异常），所以就"触发器"这一项而言**不触发**该条款。

但实测发现了一个 D006 没有预见的**相邻**问题：D006 要求"所有项目内跨表引用使用包含 `project_id` 的复合外键"，而 Hibernate 7.4.1 对这种复合关联的映射施加了硬限制（6a-0：混合可插入性 → 启动失败；关联与标量都可写 → 启动失败）。这不是"触发器与 ORM 不兼容"，而是"复合外键与 ORM 的映射形态受限"。

**需要主会话裁定**：这属于 (i) design.md/spec 层面的映射约定，还是 (ii) 需要一条新决策来固化"变体 A / 变体 B 二选一"。倾向 (i)，因为它不改变 D006 的任何语义，只是限定实现形态；但因为它影响全部 16 张表且违反即启动失败，可见性要求较高。

### C2. D004"恰有一个 LEADER"的可执行性边界（措辞需澄清）

D004：「每个项目恰有一个 LEADER；数据库部分唯一索引保证至多一个，Service 事务保证至少一个。」

实测结果与之**不矛盾**，但暴露了一个文档未言明的推论：因为部分唯一索引**无法 DEFERRABLE**（1j），LEADER 转移必须"先降后升"，事务中间**必然**存在"零个 LEADER"的窗口。因此"至少一个"这个不变式：

- 不能用任何 immediate 数据库约束表达；
- 只能在事务结束前由 Service 显式复查（或用 deferrable 约束表达在**另一张**汇总表上）。

**需要主会话确认**：是否在 design.md 明确写出"转移期间允许瞬时零 LEADER，且'至少一个'仅在事务边界校验"。当前文档措辞容易让实现者以为可以加一条 `CHECK`/immediate 约束来兜底，而那是做不到的。

### C3. ARCHITECTURE §2.3 两个备选方案实际已收敛（建议澄清而非改设计）

ARCHITECTURE 第 196 行：「触发器也必须覆盖对父 Review 上下文列的更新，**或**直接拒绝这些身份列在创建后变化。」

实测（4g/4h/4j/4k）显示：一旦采用 `INITIALLY IMMEDIATE` 的父表触发器，在 Review 已有 Finding 的情况下，父表上下文更新在**任何语句顺序下都不可能成功**——即前一个方案在效果上**就是**后一个方案。两者并非真正的二选一。

**需要主会话确认**：是否把措辞改为单一方案（覆盖父表更新，其效果即为身份列不可变），以免实现者误以为存在两条不同路线。这与 D003"Review 身份不可变、旧 Review 永不覆盖"完全一致，属于澄清而非设计变更。

---

## Caveats / 未验证

以下项**未实测**，不做推测性结论：

1. **`GenerationType.IDENTITY`（`bigserial`）下的 flush 顺序未验证。** 本探针统一用 `GenerationType.SEQUENCE`（Hibernate 对 PostgreSQL 的推荐，且能保留批处理）。IDENTITY 会强制 `persist()` 立即执行 INSERT，语义与本文 6a 的结论可能不同。若 design.md 最终选 IDENTITY，需重跑 6a-1/6a-6/6a-7。
2. **真实并发未验证。** 6c-2 的乐观锁冲突是用同一连接内的 native UPDATE 模拟的，没有跑多线程/多连接。部分唯一索引在真实并发插入下的行为（`_bt_check_unique` 会阻塞等待竞争事务结束）未实测，D004 的并发语义、D003 的一次性 Decision 条件更新、D008 的 fencing 均未做并发验证。
3. **未经 Flyway 验证。** 所有 DDL（含 `CREATE CONSTRAINT TRIGGER`、`$$` 引用的 plpgsql 函数体）是用 `psql` 直接执行的，**没有**通过 Flyway 迁移脚本跑一遍。Flyway 对 `$$ ... $$` 的解析历史上有坑，落 migration 时需单独验证一次。
4. **只建模了与 6 个验证项相关的表**（`project`/`project_member`/`requirement`/`requirement_revision`/`acceptance_criterion`/`review`/`finding`），不是完整 16 张表。表间其余复合链（附件归属、Finding 血缘、`pull_request_requirement_event` 等）未实测。
5. **`ck_finding_matches_review_context` 触发器的性能未测。** 每行 INSERT 都会多一次父表 `SELECT`；批量插入 Finding 时的开销未测量。
6. **未测 `finding_type <> 'CODE_QUALITY' OR ac_id IS NULL`**（ARCHITECTURE L207）与 review decision 组合 CHECK（L210-216），它们是普通 CHECK，风险等级远低于本文 6 项。
7. **未测 Review 的 `UNIQUE NULLS NOT DISTINCT` 与 Hibernate 的交互**。第 5 项是纯 SQL 验证；该唯一键在 Phase 6 才用到，其在 Hibernate 下的冲突异常形态（推测同 6c-1，但**未验证**）需要时再测。

## 临时资源

创建并已删除/待删除的资源见任务报告；未修改任何仓库文件（本研究文件除外），未触碰 `cpa-manager-plus`、`cli-proxy-api`、`cloudflared`。
