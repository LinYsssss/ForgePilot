# Research: `finding` 约束触发器实测（批次 3 / Phase 6）

- **Query**: `ARCHITECTURE.md` §2.1 在整个 schema 里只为 `finding` 授权了约束触发器。实测（1）它到底要表达什么、
  （2）为什么必须是约束触发器而不是 CHECK、（3）`DEFERRABLE INITIALLY DEFERRED` 的实际行为、
  （4）与 Phase 6 批量插入 Finding 的冲突、（5）父外键与 D013.1 变体 A 的共存
- **Scope**: internal（真实 PostgreSQL 15.19 + pgvector 0.8.6 + Spring Boot 4.1.0 / Hibernate 7.4.1 实测，非文献推理）
- **Date**: 2026-08-21
- **前提决策**: [D006](../../../../docs/v2/DECISIONS.md#d006)、[D013.11](../../../../docs/v2/DECISIONS.md#d013)、
  [D015.9](../../../../docs/v2/DECISIONS.md#d015)
- **对标**: 批次 1 `pg15-hibernate-constraints.md` §4/§6b、批次 2 `pgvector-hibernate-measured.md` §5。
  同一标准：每条结论附命令与原始 SQLSTATE，推理与实测分开标注。

## 实测环境

| 项 | 值 |
|---|---|
| 数据库 | `pgvector/pgvector:0.8.6-pg15-bookworm` → **PostgreSQL 15.19** (Debian 15.19-1.pgdg12+2)，pgvector 0.8.6 |
| schema | 仓库真实 `V1__foundation.sql` … `V5__scm.sql` 原样 `psql` 应用，**再**按 `ARCHITECTURE.md` §2.1/§2.3/§3.1 重建 `review` + `finding` |
| 应用 | Spring Boot **4.1.0** + Hibernate ORM **7.4.1.Final** + `ddl-auto=validate`（与 `backend/pom.xml`、`application.yml` 一致），`GenerationType.IDENTITY`（与全仓 15 个实体一致） |
| 构建 | `maven:3.9.11-eclipse-temurin-21` + `-o` 离线，挂载 `/root/.m2` |

临时容器 `fp-b3-research`、网络 `fp-b3-net`、数据库 `flywaytest` 与两个探针应用均已删除。
**未修改任何仓库文件**（本研究文件除外），未执行任何 git 操作。

复现入口（全部 DDL/DML 见下文各节，可直接粘贴）：

```bash
docker run -d --name fp-b3-research -e POSTGRES_PASSWORD=research \
  -e POSTGRES_USER=research -e POSTGRES_DB=research \
  pgvector/pgvector:0.8.6-pg15-bookworm
cd backend/src/main/resources/db/migration
for f in V1__foundation.sql V2__auth_project.sql V3__requirement.sql V4__knowledge_ai.sql V5__scm.sql; do
  docker exec -i fp-b3-research psql -U research -d research -v ON_ERROR_STOP=1 -q < "$f"
done
```

---

## 结论速览

| # | 项 | 结论 | 来源 |
|---|---|---|---|
| 0 | **任务书的前提有误** | 触发器与 `path`/`line` **完全无关**。§2.1/§2.3 的「父子上下文」= `requirement_id` + `requirement_revision_id`。`path`/`line` 在 schema 里**零约束**，`line=-9999` 直接落库 | 实测 §1.8 |
| 1 | 约束要表达什么 | 12 组行形态全部实测；**语义唯一确定**，但有 3 处后果性歧义（尤其 CODE_QUALITY Finding 被迫复制父需求上下文） | 实测 §1 |
| 2 | CHECK 能不能表达 | 直接子查询 `0A000` 拒绝；**但函数包装的 CHECK 被 PG15 接受并且真的在 INSERT 上拦住了**（23514，且约束名/表名比触发器更完整）。它挡不住的是**父表更新**——§2.3 明文要求的另一半 | 实测 §2 |
| 3 | `INITIALLY DEFERRED` | 违规语句**成功返回**；事务全程可用、**不进 25P02**；COMMIT 报 `23514` 并**点名具体 finding id**；失败 COMMIT 后事务已结束（再 COMMIT 得 `25P01`） | 实测 §3 |
| 3b | JPA 侧 | `DataIntegrityViolationException` 直接包 `PSQLException(23514)`，**不是** `UnexpectedRollbackException`；`getServerErrorMessage().getConstraint()` **拿得到**约束名（修正批次 1「不可得」的记法） | 实测 §3.4 |
| 3c | D015.9 在延迟下 | 「SQL 层 SAVEPOINT 能救回」**仍成立且更宽**：实测**在 JPA 事务内**用 `doWork()` + savepoint + `SET CONSTRAINTS IMMEDIATE` 也能救回并成功提交。D015.9 担心的绕过口子**真实存在** | 实测 §3.5 |
| 4 | 批量插入 | **延迟与非延迟都整批回滚**，差别只是「什么时候知道」。唯一能保住好行的是**写入前校验**（实测可行）或每行独立事务（实测可行，但制造部分成功） | 实测 §4 |
| 4b | 延迟的两个陷阱 | 先插违规行**再改对**——COMMIT 仍失败；先插违规行**再删掉**——COMMIT 仍失败。事件绑定在行版本上，不是最终状态 | 实测 §3.3 |
| 5 | 父 FK 共存 | 无干扰；FK 的内部触发器名 `RI_ConstraintTrigger_*` 排在 `trg_finding_ctx` 之前，**先报 23503**，触发器的「父不存在」分支是死代码 | 实测 §5 |
| 5b | D013.1 变体 A | **照样适用**：自然写法在 `finding` 上仍然启动即 `MappingException: Column 'project_id' is duplicated`；变体 A 读写与关联导航全部正常 | 实测 §5.3 |
| 6 | **并发写偏斜** | 触发器本身**挡不住**并发「插 Finding vs 改 Review 上下文」。目前挡得住，**纯属 D003 的 `UNIQUE NULLS NOT DISTINCT` 含 `requirement_revision_id` 的副作用**；把该唯一键去掉，同一场景立刻产生不一致行 | 实测 §6.1 |
| 7 | Flyway | `CREATE CONSTRAINT TRIGGER` + `$fn$` plpgsql 函数体 + `UNIQUE NULLS NOT DISTINCT` + D015.1 的补外键，**六个迁移全绿**。**关闭批次 1、批次 2 各自遗留的同一条 caveat** | 实测 §6.2 |
| 8 | 血缘 FK 索引 | `(project_id, carried_from_finding_id)` 无索引时删 2000 行 **7.04 s**，有索引 **176 ms**（40×）。§2.3 索引清单里没有这条 | 实测 §6.3 |

---

## 0. 必须先纠正的一条前提

任务书写「上下文（file_path / line 等，具体列以 §2.1 原文为准）保持 NULL-safe 一致」。
**以 §2.1/§2.3 原文为准，这个描述是错的**，而且方向性地错——照它设计会做出一个规范没有要求、
也无法自洽的触发器。原文：

> §2.1 `finding` 行：…；`UNIQUE(project_id,id)`；**CHECK 与约束触发器保证父子上下文 NULL-safe 一致**（D006/D009）
>
> §2.3：同时 migration 定义约束触发器，使用 `IS NOT DISTINCT FROM` 保证 Finding 的 **`requirement_id` 与
> `requirement_revision_id`** 分别等于父 Review 对应列。Review 未关联 Requirement 时，Finding 两列必须都为空。
> 触发器也必须覆盖对父 Review 上下文列的更新，或直接拒绝这些身份列在创建后变化。

「父子上下文」= **父 Review 的 `requirement_id` / `requirement_revision_id`**，是一条**跨行**不变式。
`path` / `line` 是 Finding 自己的列，没有对应的父列，谈不上「与父一致」。

实测确认 `path`/`line` 在数据库层**完全不受约束**（§1.8）。它们的正确性由
`ARCHITECTURE.md` §3.5 的 Validator 保证（`filePath` 必须在 changed files 内、行号必须落在 patch 可验证范围），
那是**应用层**规则，数据库既没有 changed-file manifest 的行级视图也无法表达。

> **推理（非实测）**：如果 design 阶段真的要把 `path` 纳入数据库约束，唯一形态是让触发器去 join
> `pull_request.changed_files` 这个 JSONB 列。这会把每行 Finding 的写入变成一次 JSONB 展开查询，
> 且 §3.4 的 truncation manifest 允许「文件被跳过但仍在 manifest 里」，语义并不是简单的包含关系。
> 本研究**没有实测**这条路径，因为 §2.1 没有授权它。

---

## 1. 这个约束要拒绝的具体行形态（实测）

### 1.1 实测用的 schema 与触发器

`review` / `finding` 按 §2.1 列清单与 §2.3 外键清单重建（完整 DDL 见 §1.9，**它是研究探针，不是提案迁移**）。
父表侧守卫 `fp_review_ctx_guard()` / `trg_review_ctx` 与批次 1 `pg15-hibernate-constraints.md` §4 逐字相同，
此处不重复；子表侧守卫同样同构，本研究只改它的延迟模式：

```sql
CREATE OR REPLACE FUNCTION fp_finding_ctx_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
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
END $fn$;

CREATE CONSTRAINT TRIGGER trg_finding_ctx
  AFTER INSERT OR UPDATE OF project_id, review_id, requirement_id, requirement_revision_id
  ON finding DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION fp_finding_ctx_guard();
```

注册确认：

```
     tgname      | is_constraint_trigger | tgdeferrable | tginitdeferred
-----------------+-----------------------+--------------+----------------
 trg_finding_ctx | t                     | t            | f
 trg_review_ctx  | t                     | t            | f
```

种子数据：项目 1 与项目 2；需求 1 有 revision 1 与 revision 2；AC 1 属 revision 1，AC 2/3 属 revision 2。

| Review | project | pull_request | requirement_id | requirement_revision_id |
|---|---|---|---|---|
| **R1** | 1 | 1 | 1 | **2** |
| **R2** | 1 | 2 | NULL | **NULL** |
| R3 | 1 | 1 | 1 | 1 |
| R9 | 2 | 9 | 9 | 9 |

### 1.2 应通过的行形态（3 组，实测）

**1a** —— 需求型 Finding，上下文与父完全相同，AC 属于该 revision：

```sql
BEGIN;
INSERT INTO finding (project_id, review_id, requirement_id, requirement_revision_id, ac_id,
                     finding_type, path, line, status, finding_key, continuity)
VALUES (1,1,1,2,2,'REQUIREMENT','src/a.java',10,'OPEN','k','NEW');
ROLLBACK;
```

```
BEGIN
INSERT 0 1
ROLLBACK
```

**1d** —— NULL 边界的**通过**侧：父 Review 未关联需求，Finding 两列均为 NULL：

```sql
-- Review 2 = (NULL, NULL)
INSERT INTO finding (project_id, review_id, requirement_id, requirement_revision_id,
                     finding_type, status, finding_key, continuity)
VALUES (1,2,NULL,NULL,'CODE_QUALITY','OPEN','k','NEW');
-- INSERT 0 1
```

`IS NOT DISTINCT FROM` 的 NULL-safe 语义生效——普通 `=` 在这里会得到 NULL 从而放行**所有**组合。

**1k** —— 代码质量型 Finding 挂在**已关联需求**的 Review 上，仍必须复制父上下文：

```sql
INSERT INTO finding (project_id, review_id, requirement_id, requirement_revision_id, ac_id,
                     finding_type, path, line, status, finding_key, continuity)
VALUES (1,1,1,2,NULL,'CODE_QUALITY','src/b.java',3,'OPEN','k','NEW');
-- INSERT 0 1
```

### 1.3 应拒绝的行形态（实测）

**1b** —— 上下文指向**旧 revision**（Finding 声称审的是 rev 1，父 Review 审的是 rev 2）：

```
ERROR:  23514: finding 1015 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
CONTEXT:  PL/pgSQL function fp_finding_ctx_guard() line 12 at RAISE
CONSTRAINT NAME:  ck_finding_matches_review_context
LOCATION:  exec_stmt_raise, pl_exec.c:3891
```

这一条是整个约束**存在的理由**：`(project_id, requirement_revision_id, ac_id) → acceptance_criterion`
那条 FK 只能证明 AC 属于**某个** revision，不能证明是**父 Review 那个** revision。

**1e** —— 反方向：父 Review 未关联需求，Finding 却带上下文：

```
ERROR:  23514: finding 1018 context (req=1, rev=2) does not match review 2 context (req=<NULL>, rev=<NULL>)
```

### 1.4 NULL 参与的边界（3 组，实测）

| # | 父 Review | Finding | 结果 |
|---|---|---|---|
| **1d** | `(NULL, NULL)` | `(NULL, NULL)` | **`INSERT 0 1`** —— 双 NULL 视为相等 |
| **1c** | `(1, 2)` | `(NULL, NULL)` | **拒绝**：`23514: finding 1016 context (req=<NULL>, rev=<NULL>) does not match review 1 context (req=1, rev=2)` |
| **1e** | `(NULL, NULL)` | `(1, 2)` | **拒绝**：见上 |

**1c 是最容易被实现漏掉的一条**：它意味着「Review 关联了需求时，Finding **不可以**只填代码位置而把需求上下文留空」。
下面 §1.7 说明为什么这条同时是本约束最大的语义歧义源。

### 1.5 半 NULL 由行内 CHECK 拦，不是触发器（实测）

```sql
INSERT INTO finding (..., requirement_id, requirement_revision_id, ...) VALUES (1,1,1,NULL,...);
```

```
ERROR:  23514: new row for relation "finding" violates check constraint "ck_finding_ctx"
DETAIL:  Failing row contains (1019, 1, 1, 1, null, null, REQUIREMENT, null, null, null, OPEN, null, k, null, null, NEW, null, ...).
SCHEMA NAME:  public
TABLE NAME:  finding
CONSTRAINT NAME:  ck_finding_ctx
LOCATION:  ExecConstraints, execMain.c:2074
```

行内 CHECK 先于 AFTER 触发器执行（`ExecConstraints` 在 `ExecInsert` 中早于 after-row 队列）。
**分工是清楚的**：`ck_finding_ctx` 管「两列同空同非空」（行内），触发器管「与父相等」（跨行）。

### 1.6 相邻约束的分工（实测，用于避免 design 把职责堆到触发器）

| # | 场景 | 谁拦下的 | 原始输出 |
|---|---|---|---|
| 1g | AC 1 属 revision 1，Finding 声明 revision 2 | `fk_finding_ac` | `23503 ... Key (project_id, requirement_revision_id, ac_id)=(1, 2, 1) is not present in table "acceptance_criterion"` |
| 1h | `ac_id=777` 但 revision 为 NULL | `ck_finding_ac_needs_rev` | `23514 ... violates check constraint "ck_finding_ac_needs_rev"` |
| 1i | `review_id=4242` 不存在 | `fk_finding_review` | `23503 ... Key (project_id, review_id)=(1, 4242) is not present in table "review"` |
| 1j | 项目 2 的 Finding 指向项目 1 的 Review | `fk_finding_review` | `23503 ... Key (project_id, review_id)=(2, 1) is not present in table "review"` |

**触发器不需要重复这四件事**，它唯一不可替代的职责是 1b/1c/1e 三类。

### 1.7 §2.1 原文不足以唯一确定的三处（歧义，**不代裁定**）

#### 歧义 A：CODE_QUALITY Finding 必须复制父需求上下文吗？

§2.3 的字面读法只有一个：Finding 的两列**必须**等于父 Review 的两列，无论 `finding_type`。
实测 1c 证明「留空」会被拒绝，1k 证明「复制」被接受。于是：

- **读法 A1（字面）**：所有 Finding 都复制父上下文。后果：`CODE_QUALITY` 行上的 `requirement_id` 是纯冗余——
  §3.6 规定 `CODE_QUALITY` 的 `finding_key` 是「大小写敏感 path + 归一化位置 + 类别」，**不含** requirement；
  也就是说这两列对代码质量问题**没有任何业务含义**，只是为了让触发器通过而填写。
- **读法 A2（宽松）**：把不变式改成「Finding 的上下文要么为空，要么等于父」。
  后果：`CODE_QUALITY` 可以留空，语义更干净；**但 1c 这条拦截会消失**，
  一个本该带需求上下文的 `REQUIREMENT` Finding 漏填两列时数据库不再报错，
  §3.5「`acId` 必须属于当前 Requirement Revision」的数据库侧保障就只剩 `ck_finding_ac_needs_rev` 一层。

两种读法都能实现，**代价不同且不可兼得**。A1 是字面且更 fail-closed，A2 更符合「不存无意义的值」。
**design 必须裁定。** 本研究不替它选。

#### 歧义 B：「覆盖父表更新」与「拒绝身份列变化」，在延迟模式下**不再等价**

§2.3 给了两个备选：「触发器也必须覆盖对父 Review 上下文列的更新，**或**直接拒绝这些身份列在创建后变化」。
[D013.12](../../../../docs/v2/DECISIONS.md#d013) 前向记录说这两条在实测中「收敛为同一个」，
理由是 `INITIALLY IMMEDIATE` 下父子协同改写在任何顺序都失败。

**实测：这个收敛只在 `INITIALLY IMMEDIATE` 下成立。** 换成 `INITIALLY DEFERRED` 后，
协同改写不需要任何 `SET CONSTRAINTS` 就能成功（§3.6），也就是说
**Review 的 `requirement_id` / `requirement_revision_id` 重新变成可改的**。
D003 规定 Review 身份不可变、旧 Review 永不覆盖——批次 1 是把「改不动」当作**期望行为**接受的。
选延迟就等于放弃这个由约束保证的不可变性，退回到「靠 Service 纪律」。**这是选择延迟的隐藏代价，
D013.12 没有覆盖，design 必须显式裁定。**

#### 歧义 C：触发器要不要 `carried_from_finding_id`？

§2.3 只点名了 `requirement_id` / `requirement_revision_id`。血缘由
`(project_id, carried_from_finding_id) → finding(project_id, id)` 这条 FK 保证「同项目、存在」，
而 §3.6 明写「来源必须属于**同一 PR**，由 Service 不变式和集成测试保证」。
**这一条不是歧义而是明确的分工**，列在这里只是为了让 design 不要顺手把它塞进触发器——
塞进去会让每条 Finding 的写入再多一次跨表查询，且与 §3.6 的授权范围冲突。

### 1.8 `path` / `line` 零约束（实测）

```sql
INSERT INTO finding (..., finding_type, path, line, ...) VALUES (1,2,NULL,NULL,'CODE_QUALITY',NULL,-9999,...) RETURNING id, path, line;
```

```
  id  | path | line
------+------+-------
 1022 |      | -9999
(1 row)
INSERT 0 1
```

```
  id  |       path       | line
------+------------------+------
 1023 | no/such/file.txt |
(1 row)
INSERT 0 1
```

负行号与不存在的路径都直接落库。**见 §0。**

---

### 1.9 实测用的完整 DDL 与种子数据（探针，非提案迁移）

在仓库真实的 `V1`…`V5` 之上执行。列集取自 §2.1 的 `review` / `finding` 行，外键取自 §2.3 的清单。

```sql
CREATE TABLE review (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id               BIGINT      NOT NULL,
    pull_request_id          BIGINT      NOT NULL,
    head_sha                 VARCHAR(64) NOT NULL,
    review_input_fingerprint VARCHAR(64) NOT NULL,
    requirement_id           BIGINT,
    requirement_revision_id  BIGINT,
    context_snapshot_json    JSONB       NOT NULL,
    status                   VARCHAR(32) NOT NULL,
    decision                 VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    decision_by              BIGINT,
    decision_at              TIMESTAMPTZ,
    decision_comment         TEXT,
    execution_attempt        INTEGER     NOT NULL DEFAULT 0,
    execution_token          VARCHAR(64),
    lease_until              TIMESTAMPTZ,
    engine_version           VARCHAR(64),
    prompt_version           VARCHAR(64),
    model                    VARCHAR(128),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_review_pull_request
        FOREIGN KEY (project_id, pull_request_id) REFERENCES pull_request (project_id, id),
    CONSTRAINT fk_review_revision
        FOREIGN KEY (project_id, requirement_id, requirement_revision_id)
        REFERENCES requirement_revision (project_id, requirement_id, id),
    CONSTRAINT fk_review_decision_by FOREIGN KEY (decision_by) REFERENCES user_account (id),
    CONSTRAINT ck_review_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_review_decision CHECK (decision IN ('PENDING','APPROVE','REQUEST_CHANGES')),
    CONSTRAINT ck_review_ctx CHECK (
        (requirement_id IS NULL AND requirement_revision_id IS NULL)
     OR (requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL)),
    CONSTRAINT ck_review_decision_fields CHECK (
        (decision = 'PENDING'
            AND decision_by IS NULL AND decision_at IS NULL AND decision_comment IS NULL)
     OR (decision IN ('APPROVE','REQUEST_CHANGES')
            AND decision_by IS NOT NULL AND decision_at IS NOT NULL)),
    CONSTRAINT uq_review_identity UNIQUE NULLS NOT DISTINCT
        (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id),
    CONSTRAINT uq_review_project_id UNIQUE (project_id, id)
);

CREATE TABLE finding (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id              BIGINT      NOT NULL,
    review_id               BIGINT      NOT NULL,
    requirement_id          BIGINT,
    requirement_revision_id BIGINT,
    ac_id                   BIGINT,
    finding_type            VARCHAR(32) NOT NULL,
    path                    VARCHAR(1024),
    line                    INTEGER,
    evidence                TEXT,
    status                  VARCHAR(32) NOT NULL,
    assignee_id             BIGINT,
    finding_key             VARCHAR(128) NOT NULL,
    evidence_hash           VARCHAR(64),
    basis_hash              VARCHAR(64),
    continuity              VARCHAR(32) NOT NULL,
    carried_from_finding_id BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_finding_review
        FOREIGN KEY (project_id, review_id) REFERENCES review (project_id, id),
    CONSTRAINT fk_finding_ac
        FOREIGN KEY (project_id, requirement_revision_id, ac_id)
        REFERENCES acceptance_criterion (project_id, requirement_revision_id, id),
    CONSTRAINT fk_finding_assignee
        FOREIGN KEY (project_id, assignee_id) REFERENCES project_member (project_id, user_id),
    CONSTRAINT fk_finding_carried_from
        FOREIGN KEY (project_id, carried_from_finding_id) REFERENCES finding (project_id, id),
    CONSTRAINT ck_finding_type CHECK (finding_type IN ('REQUIREMENT','CODE_QUALITY')),
    CONSTRAINT ck_finding_status CHECK (status IN ('OPEN','ACCEPTED','REJECTED','RESOLVED')),
    CONSTRAINT ck_finding_continuity CHECK (continuity IN ('NEW','PERSISTING','SUPPRESSED')),
    CONSTRAINT ck_finding_ctx CHECK (
        (requirement_id IS NULL AND requirement_revision_id IS NULL)
     OR (requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL)),
    CONSTRAINT ck_finding_ac_needs_rev CHECK (ac_id IS NULL OR requirement_revision_id IS NOT NULL),
    CONSTRAINT ck_finding_quality_has_no_ac
        CHECK (finding_type <> 'CODE_QUALITY' OR ac_id IS NULL),
    CONSTRAINT uq_finding_project_id UNIQUE (project_id, id)
);

CREATE INDEX ix_finding_review ON finding (project_id, review_id);
```

**这份 DDL 的两处已知偏差**（design 落地时必须自行核对，不要照抄）：
`ck_finding_status` 的取值集合是本研究为了让插入能跑而**臆定**的，
真正的 Finding 人工生命周期见批次 3 `prd.md` §11（`OPEN → CONFIRMED → IN_PROGRESS → FIXED → VERIFIED → CLOSED`
加两条旁路）；`review` 的 `engine_version` / `prompt_version` / `model` 三列只是为了占位，
§2.1 只写了「engine/prompt/model 审计列」，没有给列名。
**本研究的任何结论都不依赖这两处。**

种子数据（关键部分）：

```sql
INSERT INTO user_account (id, username, password_hash) VALUES (1,'leader1','x'),(2,'dev1','x'),(3,'leader2','x');
INSERT INTO project (id, name, created_by, status) VALUES (1,'P1',1,'ACTIVE'),(2,'P2',3,'ACTIVE');
INSERT INTO project_member (id, project_id, user_id, role) VALUES (1,1,1,'LEADER'),(2,1,2,'DEVELOPER'),(3,2,3,'LEADER');
INSERT INTO requirement (id, project_id, status) VALUES (1,1,'READY'),(2,1,'DRAFT'),(9,2,'READY');
INSERT INTO requirement_revision (id, project_id, requirement_id, seq, title, created_by)
VALUES (1,1,1,1,'R1 v1',1),(2,1,1,2,'R1 v2',1),(3,1,2,1,'R2 v1',1),(9,2,9,1,'P2 requirement',3);
UPDATE requirement SET current_revision_id = 2 WHERE id = 1;   -- 三步回填，D013.10
INSERT INTO acceptance_criterion (id, project_id, requirement_revision_id, ac_key, sort_order, text)
VALUES (1,1,1,'AC-1',1,'rev1 ac1'),(2,1,2,'AC-1',1,'rev2 ac1'),(3,1,2,'AC-2',2,'rev2 ac2'),(9,2,9,'AC-1',1,'p2 ac1');
-- scm_repository / pull_request 略（形态见 V5__scm.sql）
INSERT INTO review (id, project_id, pull_request_id, head_sha, review_input_fingerprint,
                    requirement_id, requirement_revision_id, context_snapshot_json, status)
VALUES (1,1,1,'head1','fp1',1,2,'{}'::jsonb,'COMPLETED'),      -- R1: 关联需求
       (2,1,2,'head2','fp2',NULL,NULL,'{}'::jsonb,'COMPLETED'), -- R2: 未关联需求
       (3,1,1,'head1','fp1x',1,1,'{}'::jsonb,'COMPLETED'),
       (9,2,9,'head9','fp9',9,9,'{}'::jsonb,'COMPLETED');       -- 项目 2
```

---

## 2. CHECK 能不能表达它（实测：一半能，一半不能）

### 2.1 直接子查询：三种写法全部被拒

```sql
ALTER TABLE finding ADD CONSTRAINT ck_finding_ctx_subquery
  CHECK ((requirement_id, requirement_revision_id) IS NOT DISTINCT FROM
         (SELECT r.requirement_id, r.requirement_revision_id
            FROM review r WHERE r.project_id = project_id AND r.id = review_id));
ERROR:  0A000: cannot use subquery in check constraint
LOCATION:  transformSubLink, parse_expr.c:1784

ALTER TABLE finding ADD CONSTRAINT ck_finding_ctx_exists
  CHECK (EXISTS (SELECT 1 FROM review r
                  WHERE r.project_id = finding.project_id AND r.id = finding.review_id));
ERROR:  0A000: cannot use subquery in check constraint
LOCATION:  transformSubLink, parse_expr.c:1784

ALTER TABLE finding ADD CONSTRAINT ck_finding_ctx_crosstable
  CHECK (requirement_id IS NOT DISTINCT FROM review.requirement_id);
ERROR:  0A000: cannot use subquery in check constraint
ERROR:  42P01: missing FROM-clause entry for table "review"
LOCATION:  errorMissingRTE, parse_relation.c:3617
```

### 2.2 CHECK 不能延迟（顺带实测，与 §3 相关）

```sql
ALTER TABLE finding ADD CONSTRAINT ck_finding_ctx_deferrable
  CHECK (line IS NULL OR line >= 1) DEFERRABLE INITIALLY DEFERRED;
ERROR:  0A000: CHECK constraints cannot be marked DEFERRABLE
LOCATION:  processCASbits, gram.y:18381
```

显式 `NOT DEFERRABLE` 则被接受（`condeferrable = f`）。
**推论**：只要 Phase 6 需要任何形式的延迟检查，CHECK 就出局——与它能不能表达跨行无关。

### 2.3 【高价值】函数包装的 CHECK **被接受，而且真的会拦**

这是任务要求「如果实测发现 CHECK **能**表达，就如实说」的那一半。

```sql
CREATE OR REPLACE FUNCTION fp_ctx_matches(p_project bigint, p_review bigint,
                                          p_req bigint, p_rev bigint)
RETURNS boolean LANGUAGE sql STABLE AS $fn$
  SELECT r.requirement_id IS NOT DISTINCT FROM p_req
     AND r.requirement_revision_id IS NOT DISTINCT FROM p_rev
    FROM review r WHERE r.project_id = p_project AND r.id = p_review;
$fn$;

ALTER TABLE finding ADD CONSTRAINT ck_finding_ctx_fn
  CHECK (fp_ctx_matches(project_id, review_id, requirement_id, requirement_revision_id));
-- ALTER TABLE      <-- 被接受
```

```
      conname      | contype | condeferrable |                pg_get_constraintdef
-------------------+---------+---------------+---------------------------------------------------------
 ck_finding_ctx_fn | c       | f             | CHECK (fp_ctx_matches(project_id, review_id, requirement_id, requirement_revision_id))
```

**它确实拦住了 §1.3 的违规行**，而且错误信息**比触发器更完整**：

```sql
-- review 1 = (req 1, rev 2)；插入声称 (1,1) 的 Finding
INSERT INTO finding (id, project_id, review_id, requirement_id, requirement_revision_id,
                     finding_type, status, finding_key, continuity)
VALUES (2001, 1, 1, 1, 1, 'REQUIREMENT', 'OPEN', 'k1', 'NEW');
```

```
ERROR:  23514: new row for relation "finding" violates check constraint "ck_finding_ctx_fn"
DETAIL:  Failing row contains (2001, 1, 1, 1, 1, null, REQUIREMENT, null, null, null, OPEN, null, k1, null, null, NEW, null, ...).
SCHEMA NAME:  public
TABLE NAME:  finding
CONSTRAINT NAME:  ck_finding_ctx_fn
LOCATION:  ExecConstraints, execMain.c:2074
```

对比触发器的同类错误：`TABLE NAME` 是 **null**、没有 `DETAIL`（批次 1 §6b-8 已记录）。
合法行照常通过（`INSERT 0 1`）。

**所以「CHECK 表达不了它」这句话，作为无条件陈述是不成立的。** 精确的陈述是下面两条。

### 2.4 函数 CHECK 挡不住的那一半：父表更新（实测）

§2.3 明文要求「触发器**也必须**覆盖对父 Review 上下文列的更新」。CHECK 做不到——
它只在**本表行**被写入时求值：

```sql
-- finding 2002 = (1,2)，与 review 1 一致，已落库
UPDATE review SET requirement_id = 1, requirement_revision_id = 1 WHERE id = 1;
-- UPDATE 1     <-- 通过了
```

```
 finding | f_req | f_rev | r_req | r_rev
---------+-------+-------+-------+-------
    2002 |     1 |     2 |     1 |     1
```

**不变式已经被破坏，而且是一条已提交的脏数据。**
（对照：触发器方案在 `INITIALLY IMMEDIATE` 下报 `23514: review 1 context update would orphan 1 finding(s)`，见 §6.1。）

后续实测确认这条脏数据**只有全表重验证才发现得了**：

```sql
ALTER TABLE finding DROP CONSTRAINT ck_finding_ctx_fn;
ALTER TABLE finding ADD CONSTRAINT ck_finding_ctx_fn2 CHECK (fp_ctx_matches(...));
ERROR:  23514: check constraint "ck_finding_ctx_fn2" of relation "finding" is violated by some row
LOCATION:  ATRewriteTable, tablecmds.c:6075
```

### 2.5 结论（精确版）

| 需求 | 直接 CHECK | 函数包装 CHECK | 约束触发器 |
|---|---|---|---|
| 子表写入时比较父上下文 | ❌ `0A000` | ✅ 实测拦住，错误信息更完整 | ✅ |
| 父表更新时保护既有子行（§2.3 明文要求） | ❌ | **❌ 实测漏过** | ✅ |
| 可延迟 | ❌ `0A000` | ❌（CHECK 恒不可延迟） | ✅ |
| 可拿到稳定约束名 | ✅ | ✅ | ⚠️ 需 `USING CONSTRAINT=` + 解包 `PSQLException`（批次 1 §6b-8） |

**§2.1「必须是约束触发器」的判断成立，但它成立的理由只有一条：父表更新那一半。**
如果 design 出于错误信息质量的考虑想同时加一条函数 CHECK 做子表侧的第一道拦截，
实测显示两者可以共存（CHECK 先报）；代价是同一条规则有两个定义处，
与「一件事实只在一个权威文档定义」的纪律相抵。**design 需裁定要不要这层冗余。**

> **另一条实测边界**：PostgreSQL 官方明确不保证「CHECK 里调用查询其他表的函数」的正确性
> （不可延迟、不参与快照隔离、`pg_dump`/restore 顺序敏感）。本研究**只实测了上面两条失效场景**，
> **没有**实测 `pg_dump` / restore 与并发下的行为。若 design 真的要用这条，必须补测。

---

## 3. `DEFERRABLE INITIALLY DEFERRED` 的实际行为（实测）

把同一个触发器改为 `DEFERRABLE INITIALLY DEFERRED`（`tgdeferrable=t, tginitdeferred=t`）后重测。

### 3.1 SQLSTATE、25P02、事务状态

```sql
BEGIN;
INSERT INTO finding (project_id, review_id, requirement_id, requirement_revision_id,
                     finding_type, status, finding_key, continuity)
VALUES (1,1,1,1,'REQUIREMENT','OPEN','bad','NEW') RETURNING id;
SELECT 'tx still usable?' AS probe, count(*) FROM finding;
INSERT INTO finding (...) VALUES (1,1,1,2,'REQUIREMENT','OPEN','good','NEW') RETURNING id;
COMMIT;
SELECT 'after failed commit' AS probe, count(*) AS findings_committed FROM finding;
```

```
BEGIN
  id
------
 1027
INSERT 0 1
      probe       | count
------------------+-------
 tx still usable? |     1
  id
------
 1028
INSERT 0 1
ERROR:  23514: finding 1027 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
CONTEXT:  PL/pgSQL function fp_finding_ctx_guard() line 12 at RAISE
CONSTRAINT NAME:  ck_finding_matches_review_context
LOCATION:  exec_stmt_raise, pl_exec.c:3891
                   probe                   | findings_committed
-------------------------------------------+--------------------
 after failed commit: still in a tx block? |                  0
```

逐条回答任务的问题：

1. **违规行在 COMMIT 时报什么 SQLSTATE？** `23514`（由 `RAISE ... USING ERRCODE='23514'` 决定，
   不是 PostgreSQL 内建值）。约束名 `ck_finding_matches_review_context` 出现在协议的 `CONSTRAINT NAME` 字段。
2. **事务是否进入 `25P02`？** **不会。** 违规 `INSERT` 正常返回 `INSERT 0 1`，
   随后的 `SELECT` 和第二次 `INSERT` 全部成功。整个事务在 COMMIT 之前**没有任何可观测的异常**。
3. **失败 COMMIT 之后的会话状态？** 事务已经结束。紧接着的裸 `SELECT` 直接成功，
   再发一次 `COMMIT` 得到 `WARNING: 25P01: there is no transaction in progress`。

**对照 `INITIALLY IMMEDIATE`**（同一容器、同一数据）：

```sql
BEGIN;
SET CONSTRAINTS trg_finding_ctx IMMEDIATE;
INSERT INTO finding (...) VALUES (1,1,1,1,...);
SELECT 'can the tx still do anything?' AS probe;
```

```
ERROR:  23514: finding 5113 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
ERROR:  25P02: current transaction is aborted, commands ignored until end of transaction block
LOCATION:  exec_simple_query, postgres.c:1081
```

**这是延迟与非延迟最重要的差别**：延迟把「立刻毒死事务」换成了「事务一直健康，直到 COMMIT 全盘失败」。

### 3.2 能不能定位到是哪一条 Finding？（实测：能）

一次插入 3 行，第 2 行违规：

```sql
BEGIN;
INSERT INTO finding (id, project_id, review_id, requirement_id, requirement_revision_id,
                     finding_type, status, finding_key, continuity) VALUES
 (3001,1,1,1,2,'REQUIREMENT','OPEN','ok-1','NEW'),
 (3002,1,1,1,1,'REQUIREMENT','OPEN','bad-2','NEW'),
 (3003,1,1,1,2,'REQUIREMENT','OPEN','ok-3','NEW');
COMMIT;
```

```
INSERT 0 3
ERROR:  23514: finding 3002 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
```

`committed = 0`。**能定位，但有三个必须写进 design 的前提：**

- **只因为触发器把 `NEW.id` 写进了 `RAISE` 消息。** 换成内建 FK，错误里只有键值而没有子行 id。
  → `RAISE` 里的 id 与两侧上下文**不是装饰，是唯一的可定位信息**。
- **只报第一条。** 两行同时违规时：

  ```
  -- 3011 ok, 3012 bad, 3013 bad
  ERROR:  23514: finding 3012 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
  ```

  3013 从未被提及。要拿到「全部坏行」必须自己查，数据库只给第一条。
- **`id` 在延迟场景下是已分配但从未提交的值。** 上例 3002 是显式给的；用 IDENTITY 时
  这个 id 在回滚后会被跳过，日志里的 id 与库里任何行都不对应——**只能当排查线索，不能当业务标识**。

### 3.3 【两个陷阱】延迟事件绑定在**行版本**上，不是最终状态

**3e —— 先插违规行、再改对，COMMIT 仍然失败：**

```sql
BEGIN;
INSERT INTO finding (id,...,requirement_revision_id,...) VALUES (3021,...,1,...);
UPDATE finding SET requirement_revision_id = 2 WHERE id = 3021;   -- UPDATE 1
COMMIT;
```

```
INSERT 0 1
UPDATE 1
ERROR:  23514: finding 3021 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
```

注意报的是 `rev=1` —— **已经被改成 2 了**。INSERT 事件持有的是插入时的那个行版本。
「先插 Finding、再回填上下文」这条路（与批次 1 `requirement.current_revision_id` 的三步回填同型）
**在延迟触发器下不成立**。

**3f —— 先插违规行、再删掉，COMMIT 仍然失败：**

```sql
BEGIN;
INSERT INTO finding (id,...) VALUES (3031,...,1,...);
DELETE FROM finding WHERE id = 3031;    -- DELETE 1
COMMIT;
```

```
ERROR:  23514: finding 3031 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
```

行已经不存在，事件照样执行。反证（3h）：**合法**行插入后删除，COMMIT 正常通过（`rows_left = 0`）——
说明事件确实执行了，只是通过了。
**推论**：「先插入全部 candidate，再删掉重复/被抑制的」这条实现路径在延迟触发器下会炸。

**3b —— 唯一能取消延迟事件的手段是子事务回滚：**

```sql
BEGIN;
SAVEPOINT sp1;
INSERT INTO finding (...) VALUES (1,1,1,1,...);   -- 违规
ROLLBACK TO SAVEPOINT sp1;
INSERT INTO finding (...) VALUES (1,1,1,2,...);   -- 合法
COMMIT;
-- COMMIT 成功，committed_rows = 1
```

`ROLLBACK TO SAVEPOINT` 丢弃该子事务排队的触发器事件；`DELETE` 不会。

### 3.4 JPA / Hibernate 侧看到什么（实测）

探针为完整 Spring Boot 4.1.0 应用，`ddl-auto=validate`，实体按 D013.1 变体 A 映射，连真实 PG15。

**延迟模式，一条违规 Finding，`@Transactional` 方法正常返回后提交：**

```
===== J1 one violating finding, @Transactional commit =====
  [0] org.springframework.dao.DataIntegrityViolationException
        msg=Hibernate transaction: Unable to commit against JDBC Connection; ERROR: finding 1031 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)   Where: PL/pgSQL function fp_finding_ctx_guard() line 12 at RAISE
  [1] org.postgresql.util.PSQLException  SQLState=23514  constraint=ck_finding_matches_review_context  table=null
        msg=finding 1031 context (req=1, rev=1) does not match review 1 context (req=1, rev=2)
### committed findings after J1: 0
```

**逐条回答任务的问题：**

- 顶层是 **`org.springframework.dao.DataIntegrityViolationException`**，**不是** `UnexpectedRollbackException`，
  也**不是** `ConstraintViolationException`。异常链只有两层，**中间没有 Hibernate 层**——
  因为失败发生在 `commit()` 期间，Hibernate 从未看到过一次失败的 `flush()`。
- **约束名拿得到**：`PSQLException.getServerErrorMessage().getConstraint()` = `ck_finding_matches_review_context`。
  `table` 仍为 `null`（触发器错误的固有特征，批次 1 §6b-8 已记录）。
  → **这修正了批次 1 §6b-6「不可得」的记法**：那里说的是 Hibernate 的 `getConstraintName()`，
  而不是协议字段。批次 1 的可行做法（解包到 `PSQLException`）在延迟场景下**同样有效**。
- **事务内完全无感**（J2）：

  ```
  ===== J2 violating row + flush + count + second insert, all inside the tx =====
    [inside tx] flush + count(=1) + a second insert all succeeded; no 25P02
    [0] org.springframework.dao.DataIntegrityViolationException
    [1] org.postgresql.util.PSQLException  SQLState=23514  constraint=ck_finding_matches_review_context
  ```

  `em.flush()` 成功、`count()` 成功、第二次 `save()` 成功。**应用层在提交前没有任何机会发现问题。**

**对照非延迟（批次 1 形态），同一探针：**

```
===== J1i one violating finding =====
  [0] org.springframework.dao.DataIntegrityViolationException
        msg=could not execute statement [ERROR: finding 1045 context ...]; constraint [null]
  [1] org.hibernate.exception.ConstraintViolationException  hibernateConstraintName=null
  [2] org.postgresql.util.PSQLException  SQLState=23514  constraint=ck_finding_matches_review_context  table=null
```

三层链，与批次 1 §6b-1 一致。**一处差异**：批次 1 的链里有 `java.sql.BatchUpdateException`，本次没有。
原因是全仓实体都用 `GenerationType.IDENTITY`（已核对 `Requirement`、`UserAccount`、`AiCallLog`、
`ScmRepository`、`PullRequestRequirementEvent` 等），**IDENTITY 主键使 Hibernate 无法做 JDBC 批量插入**，
每条 Finding 都是独立语句。`hibernate.jdbc.batch_size` 设成 20 也不改变这一点。
> 这一条是**实测得到的异常链形态差异**；「IDENTITY 禁用插入批量」本身是 Hibernate 的既知行为，
> 本研究只是观察到链里没有 `BatchUpdateException`，**没有**独立计数 JDBC 往返来直接证明。

### 3.5 D015.9 在**延迟**触发器下是否仍成立（实测：成立，而且口子更大）

[D015.9](../../../../docs/v2/DECISIONS.md#d015) 的措辞是「SQL 层的 `SAVEPOINT` 可以救回，JPA 层不行」。
延迟模式下重测，结论分成三段：

**(a) 纯 SQL 层：能救回，且比非延迟更容易**（§3.3 的 3b：连报错都不必等，直接 `ROLLBACK TO SAVEPOINT` 就把事件丢了）。

**(b) 走 Hibernate `flush()` 的路径：仍然救不回。** J6 —— 用 `SET CONSTRAINTS ... IMMEDIATE` 逼出错误，
`catch` 之后继续写：

```
===== J6 force early, catch inside the tx, keep going =====
  [0] org.springframework.orm.jpa.JpaSystemException
        msg=could not execute statement [ERROR: current transaction is aborted, commands ignored until end of transaction block] [insert into finding (...) values (...)]
  [1] org.hibernate.exception.GenericJDBCException
  [2] org.postgresql.util.PSQLException  SQLState=25P02
  [3] org.postgresql.util.PSQLException  SQLState=23514  constraint=ck_finding_matches_review_context
### committed findings after J6: 0
```

与批次 1 §6b-3 完全一致。

**(c) 【新增，且是 D015.9 明确担心的那个口子】在 JPA 事务里用 `doWork()` 走裸 JDBC + savepoint：能救回，
而且整个 `@Transactional` 方法正常提交。** J7：

```java
em.unwrap(org.hibernate.Session.class).doWork(con -> {
    Savepoint sp = con.setSavepoint("sp1");
    try (Statement st = con.createStatement()) {
        st.execute("INSERT INTO finding (...) VALUES (1,1,1,1,'REQUIREMENT','OPEN','sp-bad','NEW')");
        st.execute("SET CONSTRAINTS trg_finding_ctx IMMEDIATE");
    } catch (Exception e) {
        con.rollback(sp);                                  // 回到 savepoint
        st.execute("SET CONSTRAINTS trg_finding_ctx DEFERRED");
    }
});
```

```
===== J7 JDBC savepoint + forced early check (D015.9 under DEFERRED) =====
  inside-tx result: jdbc caught PSQLException state=23514; rolled back to savepoint;
[NO EXCEPTION]
### committed findings after J7: 2  keys=[ok1-37757609165579, ok2-37757679929924]
```

**两条合法 Finding 提交成功，事务没有被标记 rollback-only。**
原因（推理，未做字节级验证）：Hibernate 从未执行过一次失败的 `flush()`，
所以 JPA 规范里「flush 失败 ⇒ 事务 rollback-only」的那条根本没有触发。

**这精确证明了 D015.9 的判断是对的**：把 D013.11 的理由写成「JPA 层技术上不可能」，
日后一定会被「我用的是 `doWork()` / 原生 SQL，所以不适用」绕开——**实测这条绕法是真的能跑通的。**
D013.11 的禁令必须继续挂在它的另一半理由上（捕获后继续会让「部分成功」的写入提交），
**而不是可行性上**。

### 3.6 延迟让父子协同改写重新变成可能（实测，见歧义 B）

```sql
BEGIN;
UPDATE review  SET requirement_id=1, requirement_revision_id=1 WHERE id=1;   -- UPDATE 1
UPDATE finding SET requirement_id=1, requirement_revision_id=1 WHERE review_id=1;  -- UPDATE 2
COMMIT;   -- COMMIT 成功
```

```
    t    |  id  | requirement_id | requirement_revision_id
---------+------+----------------+-------------------------
 review  |    1 |              1 |                       1
 finding | 3041 |              1 |                       1
```

JPA 侧同样成立（J11：两个脏实体一次 `flush()`，`[NO EXCEPTION]`，`review 1 now: [{requirement_id=1, requirement_revision_id=1}]`）。
**没有任何顺序要求、不需要 `SET CONSTRAINTS`。** 见 §1.7 歧义 B 的后果分析。

---

## 4. 与 Phase 6 的实际冲突点：整批 Finding 回滚（实测）

### 4.1 先说一条实测事实，它推翻了任务书里的一个隐含假设

任务书设想「非延迟触发器让它立即失败」是保住整批的选项之一。**实测：不是。**

| 模式 | 5 条 Finding、第 3 条违规 | 何时报错 | **已提交的 Finding** |
|---|---|---|---|
| `INITIALLY DEFERRED`（J4） | 全部 `save()` 成功、`flush()` 成功 | **COMMIT** | **0** |
| `INITIALLY IMMEDIATE`（J4i） | 第 3 条 `save()`+flush 即抛 | **第 3 条写入时** | **0** |

```
===== J4 batch of 5, index 2 violates =====        (DEFERRED)
  [0] org.springframework.dao.DataIntegrityViolationException
        msg=Hibernate transaction: Unable to commit against JDBC Connection; ERROR: finding 1055 context (req=1, rev=1) ...
### committed findings after J4: 0

===== J4i batch of 5, index 2 violates =====       (IMMEDIATE)
  [0] org.springframework.dao.DataIntegrityViolationException
        msg=could not execute statement [ERROR: finding 1049 context (req=1, rev=1) ...]
  [1] org.hibernate.exception.ConstraintViolationException  hibernateConstraintName=null
  [2] org.postgresql.util.PSQLException  SQLState=23514
### committed findings at end: 0
```

**两种模式下整批都归零。** 非延迟买到的只是「早知道 200 ms」，**买不到任何一行 Finding**。
既然 AI 调用已经付过钱，这个「早」并不减少任何损失——**它减少的是数据库做无用功的时间，不是重跑成本。**

### 4.2 各选项的实测后果

#### 选项 (a)：接受整批回滚（不做任何额外事）

- 实测后果：0 行 Finding 落库（J4 / J4i）。
- **Review 行本身也一起回滚**（若与 Finding 同事务）：`status` 停在 `RUNNING`、`execution_token` 与
  `lease_until` 保持领取时的值。
  > **推理（未实测 reconciliation 代码，Phase 6 尚未实现）**：按 §3.2，lease 过期后
  > reconciliation 会把它捡回 PENDING，产生新的 attempt/token，**再调用一次 AI**。
  > 也就是说选项 (a) 的真实代价不是「这次白跑」，而是「会自动再花一次钱，直到人工介入」。
- **AI 输出无法复用**：核对 `V4__knowledge_ai.sql` 的 `ai_call_log` 列清单
  （`use_case / model / prompt_token / completion_token / total_token / latency_ms / status / error`），
  **没有任何一列保存响应内容**。回滚后模型产出的 ReviewOutput 在数据库里不存在。
  这是**读 schema 得到的事实**，不是推理；由此推出的「重跑必然重新调用 AI」是推理。

#### 选项 (b)：写入前在应用层校验（实测可行，是唯一保住好行的办法）

J13 —— 先读父 Review 的上下文，只写匹配的行：

```
===== J13 pre-validate against the parent, then write only matching rows =====
  accepted=4 rejected-before-write=1
[NO EXCEPTION]
### committed findings after J13: 6      (4 new + 2 from J7)
```

- 成本：**一次** `SELECT`（父 Review 已在 Review Engine 的上下文里，实际很可能一次都不用多加）。
- 后果：4 条合法 Finding 提交，1 条被拒的在应用层可见、可记日志、可计数。
- **它不违反 D013.11**：D013.11 禁的是「捕获数据库约束冲突后在同一事务里继续」；
  这里根本没有产生约束冲突，是写入前的业务校验。
  语义上与 [D015.6](../../../../docs/v2/DECISIONS.md#d015) 让 `scm` 在写 PR 之前
  先问 `requirement` facade 完全同型——**同一个模式在本项目已经有先例。**

#### 选项 (c)：每条 Finding 一个事务（实测可行，但制造部分成功）

J14 —— `Propagation.REQUIRES_NEW`：

```
===== J14 one REQUIRES_NEW transaction per finding, index 2 violates =====
  finding 2 failed with DataIntegrityViolationException
  committed=4 failed=1
### committed findings after J14: 4
```

- 后果：4 条落库、1 条丢失、**Review 的 Finding 集合永久残缺且没有任何记录说它残缺**。
- 这正是 D013.11 要防的形态。选项 (c) 在技术上完全可行，**危险的是它看起来像成功。**

#### 选项 (d)：SQL 层每行 savepoint（实测可行，但违反本项目纪律）

见 §3.3 的 3b 与 §3.5 的 J7：能保住好行，且**在 JPA 事务内也能跑通**。
与 (c) 一样制造部分成功，且把「捕获后继续」的写法引进代码——D013.11 明令禁止。

### 4.3 四个选项的对照（**不代设计裁定**）

| | (a) 接受整批回滚 | (b) 写入前校验 | (c) 每行独立事务 | (d) 每行 savepoint |
|---|---|---|---|---|
| 保住好行 | ❌ 0 行 | ✅ 实测 4/5 | ✅ 实测 4/5 | ✅ 实测 2 行 |
| 部分成功风险 | 无 | 无（被拒的没写进去，应用层知道） | **有** | **有** |
| 与 D013.11 | 相容 | 相容（不是捕获约束冲突） | **抵触** | **抵触** |
| 与 §3.4「不输出部分成功报告」 | 相容 | 需要 design 定义「有 Finding 被拒时 Review 算不算 FAILED」 | **抵触** | **抵触** |
| AI 费用 | 会重跑（推理） | 不重跑 | 不重跑 | 不重跑 |
| 额外成本 | 0 | 一次父行读取 | 事务数 = Finding 数 | 每行一次 savepoint |

**design 必须回答的问题不是「选哪个」，而是它前面那个更根本的问题**：
Review Engine 生成的 Finding 上下文**为什么会**与父 Review 不一致？
按 §3.5，Review 创建时就冻结了 `requirement_id` / `requirement_revision_id` 与不可变上下文快照，
Engine 是从这个快照生成 Finding 的。**如果实现正确，这个触发器永远不该被触发。**

> **推理（非实测）**：如果上述判断成立，那么触发器的角色是**回归防线**而不是业务流程的一部分，
> 选项 (a) 的「整批回滚」就是正确行为（它意味着代码有 bug，本来就不该提交任何 Finding），
> 而选项 (b) 的价值在于把这个 bug 变成一条可读的应用层错误而不是 COMMIT 期的 23514。
> **本研究不裁定；这段推理必须由 design 独立验证，因为它依赖 Phase 6 尚未写出的代码。**

### 4.4 顺带实测：延迟/非延迟的批量写入耗时（2000 行，单条 INSERT ... SELECT）

| 模式 | INSERT | COMMIT |
|---|---:|---:|
| `INITIALLY DEFERRED` | 200 / 327 / 219 ms | 97 / 28 / 172 ms |
| `INITIALLY IMMEDIATE` | 475 / 396 / 419 ms | 30 / 1 / 2 ms |
| 无触发器 | 270 / 265 / 283 ms | 2 / 2 / 2 ms |

**这台机器噪声很大**（延迟模式的 INSERT 有一次低于无触发器，这在物理上不可能），
三次重复的极差接近均值的一半。**唯一可以下的结论**：两种模式的总耗时同数量级，
差异被噪声淹没，**不构成选型依据**。
**这些数字绝不可以进 `ARCHITECTURE.md` §7.2** —— [D012](../../../../docs/v2/DECISIONS.md#d012) 第 2 条
要求 Phase 6 的运行边界必须在**目标 4 GB 机**上实测，本研究的机器不是那台机器。

---

## 5. 父外键 `(project_id, review_id) → review(project_id, id)` 与触发器共存（实测）

### 5.1 无干扰，且 FK 永远先报

```sql
INSERT INTO finding (project_id, review_id, ...) VALUES (1,4242,NULL,NULL,'CODE_QUALITY',...);
```

```
ERROR:  23503: insert or update on table "finding" violates foreign key constraint "fk_finding_review"
DETAIL:  Key (project_id, review_id)=(1, 4242) is not present in table "review".
TABLE NAME:  finding
CONSTRAINT NAME:  fk_finding_review
LOCATION:  ri_ReportViolation, ri_triggers.c:2596
```

机制（实测 `pg_trigger`）：

```
            tgname            | tgisinternal | is_constraint_trigger | tgdeferrable | tginitdeferred |         conname
------------------------------+--------------+-----------------------+--------------+----------------+-------------------------
 RI_ConstraintTrigger_c_17087 | t            | t                     | f            | f              | fk_finding_review
 RI_ConstraintTrigger_c_17088 | t            | t                     | f            | f              | fk_finding_review
 RI_ConstraintTrigger_c_17092 | t            | t                     | f            | f              | fk_finding_ac
 ...
 trg_finding_ctx              | f            | t                     | t            | t              | trg_finding_ctx
```

AFTER 行触发器按名字排序执行，`RI_...` 恒排在 `trg_...` 之前。
**推论：触发器函数里那段「父 Review 不存在」的 `RAISE` 是死代码**，只要父 FK 保持
`NOT DEFERRABLE`（当前 V5 风格的默认）。删掉它并不会降低安全性，但保留它的成本也几乎为零，
且在下面这个场景里会活过来。

### 5.2 想让那段分支可达，必须把 FK 声明为 DEFERRABLE

```sql
BEGIN;
SET CONSTRAINTS fk_finding_review DEFERRED;
ERROR:  42809: constraint "fk_finding_review" is not deferrable
LOCATION:  AfterTriggerSetState, trigger.c:5935
```

**注意**：`SET CONSTRAINTS ALL DEFERRED` **不会**报这个错（它只作用于可延迟的约束），
但按名字点名一个不可延迟的约束会直接失败。这条对 design 有用：
如果实现里出现 `SET CONSTRAINTS ALL DEFERRED`，它会**同时**把所有可延迟约束放开，
而不是只放开 `trg_finding_ctx` —— 应当按名字点名。

其余共存实测：

| # | 场景 | 结果 |
|---|---|---|
| 5c | Finding 存在时删除父 Review | `23503: update or delete on table "review" violates foreign key constraint "fk_finding_review" on table "finding"` / `Key (project_id, id)=(1, 2) is still referenced from table "finding"` |
| 5d | 项目 2 的 Finding 指向项目 1 的 Review | `23503 ... Key (project_id, review_id)=(2, 1) is not present in table "review"` |

**§2.3「`finding` 必须永久保留 `(project_id,review_id) → review(project_id,id)`」实测完全成立，
且与触发器零冲突。**

### 5.3 D013.1 变体 A 在 `finding` 上照样适用（实测）

**反证——自然写法仍然启动即失败：**

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumns({ @JoinColumn(name = "project_id", referencedColumnName = "project_id"),
               @JoinColumn(name = "review_id",  referencedColumnName = "id") })
private Review review;
@Column(name = "project_id", nullable = false) private Long projectId;
```

```
org.hibernate.MappingException: Column 'project_id' is duplicated in mapping for entity 'probe.Finding'
(use '@Column(insertable=false, updatable=false)' when mapping multiple properties to the same column)
```

**变体 A 全部通过：**

```java
@Column(name = "project_id", nullable = false) private Long projectId;
@Column(name = "review_id",  nullable = false) private Long reviewId;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumns(value = {
    @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false),
    @JoinColumn(name = "review_id",  referencedColumnName = "id",         insertable = false, updatable = false)
}, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
private Review review;
```

```
===== J10 variant A write =====
  wrote finding id=1100
[NO EXCEPTION]
===== J9 variant A read navigation =====
  finding 1100 -> review 1 (req=1, rev=2)
[NO EXCEPTION]
```

启动期 `ddl-auto=validate` 通过，写入走标量 `Long`，读取可经关联导航。
**`.trellis/spec/backend/database-guidelines.md` 现有的「Entities over composite foreign keys」一节
无需为 `finding` 增补任何内容。**

> `foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)` 只影响 Hibernate 的 DDL 生成，
> 在 `ddl-auto=validate` 下无作用；写它是为了防止有人把 `ddl-auto` 改成 `update` 时生成第二条重复 FK。
> **未实测**该注解在 `validate` 之外模式下的行为。

---

## 6. 顺带得到的三条实测（都会影响 design）

### 6.1 【重要】触发器**挡不住**并发写偏斜；目前挡得住是 D003 唯一键的副作用

场景：A 事务插入一条与当前 Review 上下文一致的 Finding；B 事务并发把该 Review 的
`requirement_revision_id` 改掉。两者都不知道对方。

**当前 schema（`uq_review_identity` 存在），`INITIALLY IMMEDIATE`：**

```
  A| A inserted at 2026-08-21 18:59:47.809657+00
  A| A committed at 2026-08-21 18:59:51.896466+00
  B| B update starts at 2026-08-21 18:59:48.910684+00
  B| ERROR:  review 1 context update would orphan 1 finding(s)
INVARIANT_HOLDS=true finding_rev=2 review_rev=2
```

B 从 `48.910` **阻塞到 A 提交（`51.896`，约 3 秒）**，然后被父表触发器拒绝。

**同一场景，仅仅去掉 `uq_review_identity`：**

```
  B| B update starts at 2026-08-21 18:59:55.795435+00
  B| B update returned at 2026-08-21 18:59:55.807029+00      <-- 12 ms，没有阻塞
  A| A committed at 2026-08-21 18:59:58.690696+00
INVARIANT_HOLDS=false finding_rev=2 review_rev=1
```

**两个事务都成功提交，数据库里留下一条违反不变式的已提交数据。**

机制（推理，基于 PostgreSQL 的 key-share 锁规则，未读源码验证）：
Finding 的父 FK 在 `review` 行上取 `FOR KEY SHARE`；`requirement_revision_id`
恰好是 [D003](../../../../docs/v2/DECISIONS.md#d003) 的
`UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`
的一列，于是改它算「key update」，与 `FOR KEY SHARE` 冲突 → B 阻塞 → 串行化 → 安全。
**这是一条没有任何文档记载的、跨决策的隐式依赖：D006 的触发器在并发下正确，依赖 D003 的唯一键包含那一列。**

**两条实测出来的补救：**

- **延迟触发器天然堵住这个方向**（同一场景、无唯一键、`INITIALLY DEFERRED`）：

  ```
  B| B update returned at 2026-08-21 19:00:33.515456+00     -- B 不阻塞，先提交
  A| ERROR:  finding 1110 context (req=1, rev=2) does not match review 1 context (req=1, rev=1)
  ```

  A 在 COMMIT 时用新快照重读父行，看到了 B 已提交的值，于是拒绝。
  **这是延迟模式一个实测的、文档从未提及的优点。**
- **在守卫里加 `FOR SHARE` 显式加锁**（`INITIALLY IMMEDIATE`、无唯一键）：

  ```sql
  SELECT requirement_id, requirement_revision_id, true INTO r_req, r_rev, found_it
    FROM review WHERE project_id = NEW.project_id AND id = NEW.review_id FOR SHARE;
  ```

  ```
  A| A committed at 2026-08-21 19:00:44.078035+00
  B| B update starts at 2026-08-21 19:00:41.374082+00
  B| ERROR:  review 1 context update would orphan 1 finding(s)
  INVARIANT_HOLDS=true finding_rev=2 review_rev=2
  ```

  确定性地关闭该竞争，不依赖唯一键的列构成。代价是每条 Finding 写入都在父 Review 行上取共享锁。

**design 必须裁定**：是显式加 `FOR SHARE`（自证正确），还是接受「靠 D003 唯一键兜底」并
**在 D003 旁边写一条不得移除 `requirement_revision_id` 的注记**。
两条路都能跑，但第二条把一个正确性依赖藏在了两个决策之间。

### 6.2 【关闭一条久悬的 caveat】Flyway 能解析 `CREATE CONSTRAINT TRIGGER` 与 `$fn$` 函数体

批次 1 与批次 2 的研究都在 caveat 里写着「所有 DDL 由 `psql` 直接执行，**没有**经过 Flyway，
该 caveat 仍未关闭」。本研究关掉它。

做法：把仓库真实的 `V1`…`V5` 原样复制到一个一次性 Spring Boot 应用的
`db/migration`，再加一个 `V6__review_finding.sql`（含 `UNIQUE NULLS NOT DISTINCT`、
`CREATE FUNCTION ... $fn$ ... $fn$`、`CREATE CONSTRAINT TRIGGER`、以及
[D015.1](../../../../docs/v2/DECISIONS.md#d015) 承诺的 `ALTER TABLE ai_call_log ADD CONSTRAINT fk_ai_call_log_review`），
指向一个全新的空库，`spring.flyway.baseline-on-migrate=false`、`clean-disabled=true`：

```
FLYWAY HISTORY: [{installed_rank=1, version=1, description=foundation, success=true},
                 {installed_rank=2, version=2, description=auth project, success=true},
                 {installed_rank=3, version=3, description=requirement, success=true},
                 {installed_rank=4, version=4, description=knowledge ai, success=true},
                 {installed_rank=5, version=5, description=scm, success=true},
                 {installed_rank=6, version=6, description=review finding, success=true}]
TRIGGER: [{tgname=trg_finding_ctx, is_constraint_trigger=true, tgdeferrable=true, tginitdeferred=false}]
FUNCTION BODY LEN: 901
ai_call_log FK: [{conname=fk_ai_call_log_review}]
TRIGGER ENFORCES: YES -> DataIntegrityViolationException: ... ERROR: finding 1 context (req=<NULL>, rev=<NULL>) does not match review 1 context (req=1, rev=1)
```

**六个迁移全部 `success=true`；函数体 901 字符完整落库；约束触发器注册正确并且真的拦住了违规行；
D015.1 的补外键在同一迁移里加上了。** Flyway 的 SQL 分割器没有被 `$fn$`、分号或
`CREATE CONSTRAINT TRIGGER` 的多行语法绊倒。

> 该 `V6` **只是研究探针**，不是提案迁移；它省略了 `finding` 的多个列与约束。
> 未实测的相邻问题：函数体里如果出现 `${...}`（Flyway placeholder 语法）会不会被替换——
> 本次的函数体不含 `$` 开头的花括号形式，**没有触发该风险**。

### 6.3 血缘自引用 FK 缺索引，删除慢 40 倍

```
--- delete 2000 findings with NO index on carried_from_finding_id ---
DELETE 2000
Time: 7038.247 ms (00:07.038)

CREATE INDEX ix_finding_carried_from ON finding (project_id, carried_from_finding_id);
--- delete 2000 findings WITH the lineage index ---
DELETE 2000
Time: 176.077 ms
```

`(project_id, carried_from_finding_id) → finding(project_id, id)` 是自引用 FK；
没有覆盖引用侧的索引时，每删一行都要全表扫一次找子行。
`ARCHITECTURE.md` §2.3 的索引清单里没有这条索引。
MVP 是否会删除 Finding 尚未确定（§2.3 全库只为 `pull_request.author_user_id` 定义了删除语义），
但**测试数据清理、`@Sql` 回滚、集成测试的 teardown 都会走这条路径**。成本一条索引。

---

## 对 design 的开放问题（**均需主会话/design 裁定，本研究不代决**）

**Q1. 触发器用 `INITIALLY IMMEDIATE` 还是 `INITIALLY DEFERRED`？**

| | IMMEDIATE（批次 1 形态） | DEFERRED |
|---|---|---|
| 何时报错 | 违规语句当场 | COMMIT |
| 事务状态 | 立刻 `25P02`（§3.1） | 全程健康，COMMIT 才炸（§3.1） |
| JPA 异常 | `DataIntegrityViolationException` → `ConstraintViolationException` → `PSQLException` | `DataIntegrityViolationException` → `PSQLException`（少一层） |
| 保住好行 | ❌ 整批回滚 | ❌ 整批回滚 |
| Review 上下文列可变性 | **不可变**（与 D003 一致，批次 1 视为期望行为） | **可变**（§3.6，D013.12 的收敛失效） |
| 并发写偏斜（无 D003 唯一键时） | ❌ 漏（§6.1） | ✅ 挡住（§6.1） |
| 「先插后修 / 先插后删」 | 不适用（当场就炸） | ❌ 都失败（§3.3） |

**没有一个选项在所有维度上占优。** 本研究的立场：`ARCHITECTURE.md` §2.3 与 D013.12 现有文字
指向 `INITIALLY IMMEDIATE`，改成 DEFERRED **必须**先解释 Review 上下文列重新可变这件事怎么与 D003 相容。

**Q2. §1.7 歧义 A：`CODE_QUALITY` Finding 要不要复制父需求上下文？** 见 §1.7。

**Q3. 触发器里加不加 `FOR SHARE`？** 见 §6.1。不加就要在 D003 旁边写死
「`requirement_revision_id` 不得从 review 唯一键中移除」。

**Q4. 批量插入 Finding 前要不要做应用层预校验？** 见 §4.2 (b)。若做，还要定义
「有 Finding 被拒时这次 Review 是 COMPLETED 还是 FAILED」——§3.4 只说了非法 JSON 的情形。

**Q5. 要不要额外加一条函数包装的 CHECK 做子表侧第一道拦截？** 见 §2.5。
好处是错误信息带 `TABLE NAME` 与 `DETAIL`；代价是同一规则两个定义处。

**Q6. 要不要加 `ix_finding_carried_from`？** 见 §6.3。若加，§2.3 的索引清单需要相应更新。

**Q7. 错误映射层怎么把 `23514` 区分成 409 还是 422？** 本研究实测触发器错误的
`PSQLException.getServerErrorMessage()` 中 `constraint` 有值、`table`/`schema` 恒为 `null`；
而行内 CHECK 三者都有值。`common/ApiExceptionHandler` 现有的映射是否已经能区分，
**本研究没有读也没有测**。

---

## 未能回答 / 未实测 / 假设

1. **没有实测 `pg_dump` / restore。** §2.3 的函数包装 CHECK 在 dump/restore 时的顺序敏感性
   （PostgreSQL 官方点名的风险）未验证。若 design 采纳 Q5，必须补测。
2. **没有实测目标 4 GB 机。** §4.4 的耗时数据来自本次的共享构建机，噪声极大且**不得**用于
   `ARCHITECTURE.md` §7.2。D012 第 2 条要求的实测仍然欠着。
3. **没有实测真实规模的 Finding 批量。** 最大只测到 2000 行/事务；
   延迟触发器的事件队列在更大批量下的内存占用未测量。
4. **没有实测 `finding_event`。** 第 16 张表完全不在本研究范围内，
   它的 `(project_id, finding_id)` 复合 FK 与触发器是否互相影响未验证。
5. **没有实测 reconciliation / fencing 与本触发器的交互。** 「回滚后 Review 停在 RUNNING、
   lease 过期后被捡回、再花一次 AI 的钱」（§4.2 (a)）是**推理**，依赖 Phase 6 尚未写出的代码；
   同批次的 `fencing-and-concurrency-measured.md` 与 `after-commit-scheduling-measured.md` 可能已覆盖，
   本研究未交叉核对。
6. **没有独立证明「IDENTITY 禁用 JDBC 批量插入」。** §3.4 只观察到异常链里没有
   `BatchUpdateException`，没有计数 JDBC 往返。
7. **`FOR SHARE` 的性能未测。** §6.1 的补救方案会给每条 Finding 写入加一次父行共享锁，
   在 2000 行批量下的额外开销未测量。
8. **`@ForeignKey(ConstraintMode.NO_CONSTRAINT)` 只在 `validate` 下验证过。**
9. **假设**：本研究重建的 `review` / `finding` DDL 忠实于 §2.1/§2.3。列名与约束逐条对照过原文，
   但**它不是提案迁移**，design 落地时必须重新逐列核对（尤其 `evidence`、`evidence_hash`、
   `basis_hash`、`execution_*`、`decision_*` 这些本研究只建了列、没有测过语义的部分）。
10. **假设**：种子数据用显式 id（1、2、9）而非 IDENTITY 序列，
    因此 §3.2 关于「回滚后 id 被跳过」的那一条是**推理**，未直接观测序列跳号。

---

## 临时资源

容器 `fp-b3-research`、网络 `fp-b3-net`、数据库 `flywaytest`、探针
`/root/.claude/jobs/e84ffece/tmp/{jpaprobe,jpaprobe_natural,flywayprobe}` 均已删除或位于仓库之外。
**未改动 `backend/` 下任何文件、任何迁移、任何文档、任何测试；未执行任何 git 操作。**

本研究重建 `review` / `finding` 所用的完整 DDL 与种子数据保存在本文件 §1.1 及以下各节的代码块中，
可直接粘贴复现。
