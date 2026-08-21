# Research: pgvector + PostgreSQL 15 + Hibernate 7.4.1 实测

- **Query**: 批次 2（Phase 4 Knowledge + Phase 5 SCM）落地前，实测无维度向量列、向量索引、附件双复合外键、`UNIQUE NULLS NOT DISTINCT`、约束触发器、Hibernate vector 映射、文本编码边界
- **Scope**: internal（真实数据库 + 真实 Spring Boot 应用实测，非文献推理）
- **Date**: 2026-08-21
- **对标**: 批次 1 的 `pg15-hibernate-constraints.md`，同一标准：每条结论附 SQL 与原始 SQLSTATE

## 实测环境

| 项 | 值 |
|---|---|
| 数据库 | `pgvector/pgvector:0.8.6-pg15-bookworm` → **PostgreSQL 15.19**，**pgvector 0.8.6**，server/client encoding 均 UTF8 |
| 应用 | Spring Boot **4.1.0** + Hibernate ORM **7.4.1.Final** + `ddl-auto=validate`（与 `backend/pom.xml`、`application.yml` 一致）；探针额外引入 `org.hibernate.orm:hibernate-vector:7.4.1.Final` |
| 构建 | `maven:3.9.11-eclipse-temurin-21`，挂载 `/root/.m2` |

临时容器 `pgvec-research` 与 `/tmp/vecprobe` 已删除，**未修改任何仓库文件**（本研究文件除外）。

## 结论速览

| # | 项 | 结论 |
|---|---|---|
| 1 | 无维度 `vector` 列 | **可行**，D001 不需要改；但**同一项目内混维度会让整条 TopK 查询 22000 失败**（毒丸） |
| 2 | 向量索引 | 无维度列上一律 `22023: column does not have dimensions`；**表达式索引是唯一出路**，且**建索引后数据库才第一次拦住错维度写入** |
| 3 | 附件双复合外键 | **完全成立**，公共知识（scope NULL）**无法**挂到 Requirement（23503）；前提是子表 `requirement_id` **NOT NULL** —— 一旦可空，MATCH SIMPLE 跳过，垃圾 document_id 直接落库 |
| 4 | `UNIQUE NULLS NOT DISTINCT` | **可行**；新增：`ON CONFLICT ... DO NOTHING ... RETURNING` **返回 0 行**，get-or-create 必须 `DO UPDATE` |
| 5 | 约束触发器 | **可行**；批次 1 的 `25P02` 结论**仍成立**，补充精确边界：SQL 层 SAVEPOINT **可以**救回，JPA 层不行 |
| 6 | Hibernate 映射 vector | 不映射 ✅ / `String` ❌ / `String+columnDefinition` ⚠️（validate 过、运行时 42804）/ `hibernate-vector` ✅ 全功能可用 |
| 7 | 文本编码边界 | NUL 与非法 UTF-8 **显式失败**（22021/54000）；但**孤立代理项被 PgJDBC 静默替换为 `?`** —— Phase 4「显式失败而非损坏数据」的唯一破口 |

---

## 1. 无维度 `vector` 列（D001 的地基）

### 1a 建表与 typmod

```sql
CREATE TABLE kc (id bigserial PRIMARY KEY, seq int, embedding vector);
SELECT attname, format_type(atttypid, atttypmod), atttypmod
  FROM pg_attribute WHERE attrelid='kc'::regclass AND attnum>0;
--   attname  | coltype | atttypmod
-- -----------+---------+-----------
--  embedding | vector  |        -1
```

`atttypmod = -1` 即无维度。**D001 的写法被 PG15 + pgvector 0.8.6 完全接受。**

### 1b–1e 同一列写入不同维度：全部接受

`'[1,2,3]'`、`'[1,2,3,4]'`、1536 维、`NULL` 依次插入全部 `INSERT 0 1`，`vector_dims` 依次为 3/4/1536/NULL。**写入侧完全没有维度约束。**

### 1f `<->` 遇到混维度 —— 整条查询失败

```sql
SELECT seq, embedding <-> '[1,2,3]' AS dist FROM kc ORDER BY dist LIMIT 5;
ERROR:  22000: different vector dimensions 4 and 3
LOCATION:  CheckDims, vector.c:74
```

**SQLState = 22000**，无约束名、无表名。不是逐行降级，是整条语句失败。

### 1g–1h 过滤可以救，且过滤先于距离计算

`WHERE vector_dims(embedding)=3` 后查询正常返回，计划为 `Limit -> Sort -> Seq Scan  Filter: (vector_dims(embedding) = 3)`。`vector_dims` 的 `provolatile='i'`（IMMUTABLE），可用于 CHECK 与表达式索引。

### 1k 【关键】毒丸：同项目内一行错维度，整个项目检索崩

50 行 8 维 + **1 行 3 维**（同为 `project_id=1`）：`SELECT id FROM kc_p WHERE project_id=1 ORDER BY embedding <-> '[0.1,...8个...]'::vector LIMIT 8;` → `ERROR: 22000: different vector dimensions 3 and 8`。

### 1l 跨项目隔离有效

`WHERE project_id=2`（该项目全 3 维）正常返回 8 行。**`project_id` 硬过滤在 Seq Scan 的 Filter 中先于排序表达式求值**，别的项目污染不了本项目。

### 结论

**D001 不需要改。** 但实测暴露一条 D001 与 ARCHITECTURE §5 都没写明的后果：**无维度列 = 数据库在建索引之前完全不校验维度；同一项目内混入一行错维度向量，该项目所有 TopK 查询立刻 22000 失败。** ARCHITECTURE §5「应用层写入时校验向量维度与当前 Profile 一致」因此**不是可选纪律，而是唯一防线**。数据库侧兜底见 6k。

---

## 2. 向量索引：为什么批次 2 建不了

### 2a–2c 无维度列上建索引一律失败

```sql
CREATE INDEX idx_ivf ON kc_p USING ivfflat (embedding vector_l2_ops);
ERROR:  22023: column does not have dimensions        LOCATION: InitBuildState, ivfbuild.c:356
CREATE INDEX idx_hnsw    ON kc_p USING hnsw (embedding vector_l2_ops);
CREATE INDEX idx_hnswcos ON kc_p USING hnsw (embedding vector_cosine_ops);
ERROR:  22023: column does not have dimensions        LOCATION: InitBuildState, hnswbuild.c:704
```

**2e 反证**：即使表中**所有行维度一致**（100 行全 8 维），普通索引仍报同样 `22023`。失败原因是**列的 typmod**，不是数据。**"批次 2 不建向量索引"从此有了可引用的机械原因，而不是未解释的禁令。**

### 2f 表达式索引可行（D001 承诺的 Phase 4 路径）

```sql
CREATE INDEX idx_clean_expr ON kc_clean USING hnsw ((embedding::vector(8)) vector_l2_ops);
-- CREATE INDEX；\d 显示 hnsw ((embedding::vector(8)) vector_l2_ops)
```

**2d 前置条件**：表内**任何一行**维度不符，建索引即失败：`ERROR: 22000: expected 8 dimensions, not 3` / `LOCATION: CheckExpectedDim, vector.c:86`。

### 2g–2i 【关键】检索 SQL 必须带**完全一致**的左侧 cast

| 查询写法 | 计划 |
|---|---|
| 2g `ORDER BY embedding::vector(8) <-> '[...]'::vector(8)` | **Index Scan using idx_clean_expr** |
| 2i `ORDER BY embedding::vector(8) <-> '[...]'::vector`（右侧不带维度） | **Index Scan**（右侧无所谓） |
| 2h `ORDER BY embedding <-> '[...]'::vector`（左侧无 cast） | **Seq Scan**（索引完全没用上） |

**左侧表达式必须与索引定义字面一致，右侧参数的 cast 无关紧要。** ARCHITECTURE §5「检索 SQL 须用一致的 cast 表达式」实测成立，且**在 Phase 4 写检索 SQL 时就必须遵守**，否则 Phase 6 建的索引对已有代码是死的。

### 2k 【最重要】索引存在后，数据库才第一次拦住错误维度

```sql
-- idx_clean_expr（8 维）已存在
INSERT INTO kc_clean (project_id, embedding) VALUES (1, '[1,2,3]');
ERROR:  22000: expected 8 dimensions, not 3   LOCATION: CheckExpectedDim, vector.c:86
```

`NULL` embedding 仍可插入（2l），索引查询仍正常（2m）。**推论：批次 2 的整个生命周期里，数据库对 `knowledge_chunk.embedding` 的维度零校验。** 这正是 1k 毒丸能落库的原因。

### 2o HNSW + 选择性过滤会**少返回**结果

40000 行 / 2000 项目（每项目 20 行），`hnsw.ef_search` 默认 40，查询 `WHERE project_id=7 ORDER BY embedding::vector(8) <-> '[...]'::vector(8) LIMIT 8`：

| 场景 | 计划 | 实际返回 |
|---|---|---|
| 规划器自选 | Seq Scan + Filter | **8 行**（正确） |
| `SET enable_seqscan=off` | `Index Scan using idx_sel` + `Filter: (project_id = 7)` | **1 行** |

pgvector HNSW 是**后过滤**：先取 `ef_search` 个候选再套 `project_id`。**TopK=8 的项目级检索在建索引后可能静默变成 TopK=1。**

---

## 3. 附件双复合外键（D005/D006，ARCHITECTURE §2.3）

### 承重约束（其余列省略）

```sql
-- knowledge_document: UNIQUE (project_id,id)、UNIQUE (project_id,id,source_requirement_id)、
--   ck_doc_scope CHECK(REQUIREMENT_ATTACHMENT ⇒ scope NOT NULL；PROJECT_KNOWLEDGE ⇒ scope NULL)
-- requirement_attachment 承重项：
  requirement_id bigint NOT NULL,   -- << NOT NULL 是承重墙，见 3j
  document_id    bigint NOT NULL,
  CONSTRAINT uq_att_req_doc  UNIQUE (requirement_id, document_id),
  CONSTRAINT uq_att_proj_doc UNIQUE (project_id, document_id),
  CONSTRAINT fk_att_req FOREIGN KEY (project_id, requirement_id)
    REFERENCES requirement (project_id, id),
  CONSTRAINT fk_att_doc_scope FOREIGN KEY (project_id, document_id, requirement_id)
    REFERENCES knowledge_document (project_id, id, source_requirement_id)
```

两条 FK 均 `condeferrable=f`、`confmatchtype=s`（MATCH SIMPLE）；`uq_doc_proj_id_srcreq` 的 `indnullsnotdistinct=f`。

### 实测

| # | 场景 | 结果 |
|---|---|---|
| 3a | doc10（scope=req1）挂 req1 | `INSERT 0 1` |
| 3b2 | doc14（scope=req1）挂 **req2** | `ERROR: 23503 ... fk_att_doc_scope ... Key (project_id, document_id, requirement_id)=(1, 14, 2) is not present in table "knowledge_document"` |
| **3c** | **公共知识 doc11（scope=NULL）挂 req1** | **`ERROR: 23503 ... fk_att_doc_scope ... Key (1, 11, 1) is not present`** |
| 3d | 项目 1 的关系行指向项目 9 的 doc12 | `ERROR: 23503 ... fk_att_doc_scope ... Key (1, 12, 1) is not present` |
| 3e2 | 同一 doc 挂第二个 Requirement | `ERROR: 23505 ... uq_att_proj_doc ... Key (project_id, document_id)=(1, 13) already exists` |
| 3f/3g | 父 doc 已被挂时改 `source_requirement_id` 为 `NULL` 或改成 2 | 均拒：`23503 ... fk_att_doc_scope on table "requirement_attachment" ... Key (project_id, id, source_requirement_id)=(1, 10, 1) is still referenced` |
| 3h | 子表把 `requirement_id` 改成 2 | `23503 ... Key (1, 10, 2) is not present` |
| 3i | 删除在用父 doc | `23503 ... is still referenced from table "requirement_attachment"` |

### 3c 的机制（任务要问的核心）

**MATCH SIMPLE 的"任一列为 NULL 则跳过"只作用于引用方（子表）的列，不作用于被引用方（父表）。** 子表三列全 NOT NULL → 检查完整执行 → 到父表按等值找 `(1, 11, 1)` → 父行 `source_requirement_id` 是 NULL，`NULL = 1` 永不为真 → 无匹配 → 23503。**公共知识文档在数据库层面根本不可能被挂成需求附件；D005 的这条边界是被强制的，不是靠 Service 纪律。**

### 3j 【承重墙】子表 `requirement_id` 一旦可空，整条检查蒸发

```sql
-- 同样的 fk_att_doc_scope，唯一区别：requirement_id bigint NULL（故意可空）
INSERT INTO att_nullable VALUES (DEFAULT, 1, NULL, 11);      -- 公共 doc
INSERT INTO att_nullable VALUES (DEFAULT, 1, NULL, 999999);  -- 不存在的 doc
INSERT INTO att_nullable VALUES (DEFAULT, 1, NULL, 12);      -- 别的项目的 doc
```

三条**全部 `INSERT 0 1`**：不存在的 doc 999999 与跨项目的 doc 12 都直接落库。

**`requirement_attachment.requirement_id NOT NULL` 必须在 design.md 标注为不可省略**，与批次 1 给 `finding` 的 `ck_finding_ac_needs_rev` 同级。

### 3k `UNIQUE(project_id, id, source_requirement_id)` 是硬需求

去掉它 FK 建不出来：`ERROR: 42830: there is no unique constraint matching given keys for referenced table "doc_nouq"` / `LOCATION: transformFkeyCheckAttrs, tablecmds.c:11777`。

### 3l 把父唯一键改成 `NULLS NOT DISTINCT` 也救不了公共文档

```sql
CONSTRAINT uq_nnd UNIQUE NULLS NOT DISTINCT (project_id, id, source_requirement_id)
INSERT INTO doc_nnd VALUES (1, 1, NULL);   -- INSERT 0 1
INSERT INTO att_nnd VALUES (1, 1, 5);
ERROR: 23503: ... Key (project_id, document_id, requirement_id)=(1, 1, 5) is not present in table "doc_nnd"
```

FK 建得出来，但 FK 查找走等值语义，`NULLS NOT DISTINCT` 只影响索引唯一性判定。**没有任何唯一键写法能让公共文档被挂上去——说明 3c 不是配置巧合。**

**结论：ARCHITECTURE §2.3 与 D005/D006 的这条链完全成立，无需修改。** 附加两条设计要求：3j（`requirement_id NOT NULL`）与 3k（父三列唯一键不可省）。

---

## 4. `UNIQUE NULLS NOT DISTINCT`（PG15，批次 3 的 Review 身份）

### 4a/4b 语义确认

三次插入 `(1,'abc','fp1',NULL)`：`NULLS NOT DISTINCT` 索引（`indnullsnotdistinct=t`）第 2 次即拒 `ERROR: 23505 ... Key (pull_request_id, head_sha, fp, requirement_revision_id)=(1, abc, fp1, null) already exists`；普通 UNIQUE（`f`）**三行全落库**。与批次 1 的 5b/5f 一致。

### 4c–4f 【新增】幂等创建的三个坑

ARCHITECTURE §3.1 要求"幂等创建**或取得** Review"。实测：

| # | SQL | 结果 |
|---|---|---|
| 4c | `ON CONFLICT (4列) DO NOTHING RETURNING id` | **返回 0 行**（`INSERT 0 0`）—— 拿不到已存在的 id |
| 4d | `ON CONFLICT (4列) DO UPDATE SET status = rv_nnd.status RETURNING id` | **返回 `id = 1`** —— 可用的 get-or-create |
| 4e | `ON CONFLICT ON CONSTRAINT uq_rv_nnd DO UPDATE ... RETURNING id` | **返回 `id = 1`** —— 约束名形式同样可用 |
| 4f | 在**普通 UNIQUE** 表上做同样的 `DO NOTHING` | **插入了第 4 行重复**（`INSERT 0 1`），冲突路径根本没触发 |

**4f：如果 Review 唯一键忘写 `NULLS NOT DISTINCT`，`ON CONFLICT` 的幂等保护对 `requirement_revision_id IS NULL` 的 Review 完全失效，且不报错。**

---

## 5. 约束触发器 + `IS NOT DISTINCT FROM`（批次 2 形态）

用批次 2 的形状建：`knowledge_chunk` 的 embedding profile 必须与父 `knowledge_document` NULL-safe 一致。

结构与批次 1 的 `fp_finding_ctx_guard` 完全同构（先按 `(project_id, document_id)` 取父行，缺失即 RAISE；再做 NULL-safe 比较），只列承重两行：

```sql
  IF NOT (NEW.embedding_model     IS NOT DISTINCT FROM d_model
      AND NEW.embedding_dimension IS NOT DISTINCT FROM d_dim) THEN
    RAISE EXCEPTION '...' USING ERRCODE='23514', CONSTRAINT='ck_chunk_matches_document_profile';

CREATE CONSTRAINT TRIGGER trg_chunk_profile
  AFTER INSERT OR UPDATE OF project_id, document_id, embedding_model, embedding_dimension
  ON kchunk2 DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION fp_chunk_profile_guard();
```

注册确认：`tgconstraint<>0 = t`、`tgdeferrable = t`、`tginitdeferred = f`。

| # | 场景 | 结果 |
|---|---|---|
| 5a | chunk 与父 doc profile 一致 | `INSERT 0 1` |
| 5b | model 不一致 | `ERROR: 23514: chunk 2 profile (model=text-embedding-3-small, dim=1024) does not match document 1 profile (model=bge-m3, dim=1024)`；`CONSTRAINT NAME` 取到 `ck_chunk_matches_document_profile` |
| 5c | chunk NULL/NULL，父 doc 已嵌入 | `ERROR: 23514: chunk 3 profile (model=<NULL>, dim=<NULL>) does not match ...` |
| **5d** | chunk NULL/NULL + 父 doc 也 NULL/NULL（未嵌入） | **`INSERT 0 1`** —— `IS NOT DISTINCT FROM` 的 NULL-safe 语义生效 |

### 5e 【重要】一条**不绑定维度**的维度自洽 CHECK

```sql
CONSTRAINT ck_kchunk2_dim CHECK (embedding IS NULL OR embedding_dimension = vector_dims(embedding))

INSERT INTO kchunk2 (...) VALUES (5,1,1,3,'[1,2,3]','bge-m3',1024);
ERROR:  23514: new row for relation "kchunk2" violates check constraint "ck_kchunk2_dim"
DETAIL:  Failing row contains (5, 1, 1, 3, [1,2,3], bge-m3, 1024).
CONSTRAINT NAME:  ck_kchunk2_dim
```

**这条 CHECK 不在 schema 里写死任何具体维度，与 D001 完全兼容**，可以进批次 2 的 migration。但见 6j：它只保证**自洽**，挡不住毒丸。

### 5f–5i 事务状态（验证批次 1 的结论是否仍成立）

| # | 序列 | 结果 |
|---|---|---|
| 5f | `BEGIN; <触发器违规>; SELECT 1;` | 违规 `23514`，随后 `ERROR: 25P02: current transaction is aborted, commands ignored until end of transaction block` |
| **5g** | `BEGIN; SAVEPOINT sp1; <违规>; ROLLBACK TO SAVEPOINT sp1; <合法写>; COMMIT;` | **成功**：`recovered` → `INSERT 0 1` → `COMMIT`，合法行落库 |
| 5h | `SET CONSTRAINTS ALL DEFERRED` + 违规写 | 语句通过（`INSERT 0 1`），**COMMIT 时才报** `23514` |
| 5i | DEFERRED + `SAVEPOINT` + `SET CONSTRAINTS ALL IMMEDIATE` | 违规**立刻**暴露，`ROLLBACK TO SAVEPOINT` 后可继续并成功 COMMIT；违规行均未落库（`count = 0`） |

**对批次 1 措辞的精确化**：本次复测确认 **纯 SQL 层 SAVEPOINT 可以救回**（5g/5i），而 **JPA 层仍救不回**（6i 独立复现 `UnexpectedRollbackException`）。两者不矛盾，但 design.md 引用时必须写清是哪一层，否则会被读成"savepoint 一律无效"。

---

## 6. Hibernate 7.4.1 映射 `vector` 列（`ddl-auto: validate`）

**探针为完整 Spring Boot 4.1.0 应用**，连真实 PG15，表由裸 SQL 建好（`embedding vector NULL` + `ck_kc_dim` 自洽 CHECK）。

### 6a `backend/pom.xml` 现状与可用依赖

当前 `pom.xml` **没有**任何 vector 类型依赖。实测确认：`hibernate-core:7.4.1.Final` **自带** `SqlTypes.VECTOR = 10000`（及 `VECTOR_FLOAT32=10002` 等）但**不含** PostgreSQL 的 JdbcType 实现；**`org.hibernate.orm:hibernate-vector:7.4.1.Final` 在 Maven Central 存在，版本与 Boot 4.1.0 管理的 Hibernate ORM 完全一致**（`<dependency>` 无需写 `<version>`），含 `PGVectorJdbcType`、`PGVectorTypeContributor`、`PGVectorFunctionContributor`、`PGVectorDimsFunction`、`PGVectorNormFunction`，经 `META-INF/services` 自动注册。

### 6b–6f 六种映射写法的 validate 结果

| # | 写法 | `ddl-auto=validate` |
|---|---|---|
| **A** | 实体**完全不映射** `embedding` 列 | **✅ 启动成功** |
| B | `@Column(name="embedding") String embedding` | ❌ 启动失败 |
| **C** | `@Column(name="embedding", columnDefinition="vector") String` | **⚠️ 启动成功，运行时写入失败** |
| **D** | `@JdbcTypeCode(SqlTypes.VECTOR) @Column(name="embedding") float[]` | **✅ 启动成功** |
| E | D + `@Array(length=1024)` | ✅ 启动成功 |
| F | `@Column(name="embedding") float[]`（无 `@JdbcTypeCode`） | ❌ 启动失败 |

```
B: org.hibernate.tool.schema.spi.SchemaManagementException: Schema validation: wrong column type
   encountered in column [embedding] in table [knowledge_chunk];
   found [vector (Types#OTHER)], but expecting [varchar(255) (Types#VARCHAR)]
F: 同上，but expecting [float4 array (Types#ARRAY)]
```

**任务问的核心问题，答案是：`validate` 只检查"实体映射到的列"。未映射的列（变体 A）它根本不看，启动照常成功；只有被映射且类型不匹配时才失败。** 另一条实测：**`validate` 不检查 typmod** —— 变体 E 声明 `@Array(length=1024)`、数据库列无维度，validate 依然通过。

### 6g 读写实测（决定性）

| 变体 | 写 | 读 | HQL `cosine_distance` | 原生 `<->` | 数据库实际值 |
|---|---|---|---|---|---|
| A（不映射） | `OK` | `<unmapped>` | `UnknownPathException: Could not resolve attribute 'embedding'` | OK（null） | `embedding = NULL` |
| C | **FAIL** | — | — | — | — |
| **D** | `OK` | `[1.0, 2.0, 3.0, 4.0]` | **`OK 0.0`** | `OK 0.0` | `[1,2,3,4]`，`vector_dims=4` |
| **E** | `OK` | `[1.0, 2.0, 3.0, 4.0]` | **`OK 0.0`** | `OK 0.0` | `[1,2,3,4]`，`vector_dims=4` |

C 的原始错误（**validate 放行、运行时才炸**，最危险的一种）：

```
[0] org.hibernate.exception.SQLGrammarException :: could not execute statement
    [ERROR: column "embedding" is of type vector but expression is of type character varying
[1] org.postgresql.util.PSQLException SQLState=42804
```

D/E 写入错维度时被 5e 的 CHECK 拦下：`ConstraintViolationException` → `PSQLException SQLState=23514 :: ERROR: new row for relation "knowledge_chunk" violates check constraint "ck_kc_dim"`。

**变体 E 的 `@Array(length=1024)` 对运行时零约束**：声明 1024 维，写入 4 维照样成功，它只是 DDL 生成用的元数据。

### 6h `@Array` 与 DDL 生成（D001 的隐患）

用 `jakarta.persistence.schema-generation.scripts.action=create` 导出：

| 变体 | 生成的列定义 |
|---|---|
| D（无 `@Array`） | `embedding vector($l)` ← **未解析的占位符，DDL 不可用** |
| E（`@Array(length=1024)`） | `embedding vector(1024)` ← **把维度写进了 schema** |

Flyway 拥有 schema 且 `ddl-auto=validate`，二者都不会实际执行。但**一旦有人把 `ddl-auto` 改成 `create`/`update`，变体 E 直接违反 D001**。design.md 应写明"`ddl-auto` 永远是 `validate`，向量列 DDL 只由 Flyway 定义"。

### 6i 事务复现（批次 1 6b-5 在批次 2 上下文再现）

`ck_kc_dim` 违规被 `try/catch` 捕获后事务照常走到提交，得到 `org.springframework.transaction.UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`。**批次 1 的"禁止捕获约束异常后继续同一事务"规则在批次 2 完全适用，无需重新论证。**

### 6j 【关键】5e 的 CHECK 挡不住毒丸

CHECK 只保证 `embedding_dimension` 与 `vector_dims(embedding)` **自洽**。"自洽但错 Profile"的行照样落库：`('poison','[9,9,9]','probe-m',3)` 与 `('good','[1,2,3,4]','probe-m',4)` 均 `INSERT 0 1`，随后 `ORDER BY embedding <-> '[1,2,3,4]'` 得到 `ERROR: 22000: different vector dimensions 3 and 4`。

### 6k 一条 **D001 兼容**的 Profile 级兜底（实测可行）

把维度放进**数据行**而不是 schema：

```sql
CREATE TABLE embedding_profile (
  id int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  provider text NOT NULL, model text NOT NULL, dimension int NOT NULL);

-- fp_chunk_dim_guard() 主体：embedding IS NULL 直接放行；否则读 profile 行后
  IF vector_dims(NEW.embedding) IS DISTINCT FROM p_dim
     OR NEW.embedding_model IS DISTINCT FROM p_model THEN
    RAISE EXCEPTION '...' USING ERRCODE='23514', CONSTRAINT='ck_chunk_matches_active_profile';

CREATE CONSTRAINT TRIGGER trg_chunk_dim AFTER INSERT OR UPDATE OF embedding, embedding_model
  ON knowledge_chunk DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION fp_chunk_dim_guard();
```

| # | 场景 | 结果 |
|---|---|---|
| 6l | 毒丸行（自洽但 3 维，Profile 为 4 维） | `ERROR: 23514: chunk 14 embedding (model=bge-m3, dim=3) does not match active profile (model=bge-m3, dim=4)` |
| 6m / 6o | 正确 Profile / `embedding IS NULL`（未嵌入的 PENDING chunk） | 均 `INSERT 0 1` |
| 6n | 维度对、model 错 | `ERROR: 23514: ... (model=text-embedding-3-small, dim=4) does not match active profile ...` |
| 6p | 改 `embedding_profile.dimension` 后 | 已有行**不被回溯校验**（与 D001「换模型是停写+重嵌入的维护操作」一致） |

**Flyway 对 `$fn$ ... $fn$` 的解析未验证**（批次 1 caveat 3 仍未关闭）。

---

## 7. 大文本 / bytea / 编码边界（Phase 4 退出条件）

### 7a–7g SQL 层

| # | SQL | 结果 |
|---|---|---|
| 7a | `SELECT length(E'abc\000def')` | `ERROR: 22021: invalid byte sequence for encoding "UTF8": 0x00` |
| 7b | `'abc' \|\| chr(0) \|\| 'def'` | `ERROR: 54000: null character not permitted` / `LOCATION: chr, oracle_compat.c:1045` |
| 7c | `INSERT INTO doctext (raw) VALUES (decode('61626300646566','hex'))` | `INSERT 0 1`，`length=7`，`encode(...,'escape') = abc\000def` —— **bytea 完全接受 NUL** |
| 7d/7f | 非法字节 `61ff62`、截断多字节 `e4bd`，经 `convert_from(...,'UTF8')` | `ERROR: 22021: invalid byte sequence for encoding "UTF8": 0xff` / `... 0xe4 0xbd` |
| 7e | `SELECT U&'\D800'` 与 `SELECT E'\uD800'` | 均 `ERROR: 42601: invalid Unicode surrogate pair` |
| 7g | 合法 CJK `decode('e4bda0e5a5bd','hex')` | `你好` |

### 7h 大小上限

| # | SQL | 结果 |
|---|---|---|
| 7h1 | `SELECT length(repeat('a', 300000000))` | `300000000` —— 300 MB **成功** |
| 7h2 | `SELECT length(repeat('a', 1073741824))` | `ERROR: 54000: requested length too large` / `LOCATION: repeat, oracle_compat.c:1163` |
| 7h3 | `INSERT INTO doctext (txt) SELECT repeat('a', 600000000)` | `INSERT 0 1`，`length = 600000000` —— **600 MB 文本真的落库了** |

**varlena 的 1 GB 上限在 Phase 4 的量级上根本不是防线**：ARCHITECTURE §7.2 的「单文件上传上限 5 MB / `KnowledgeUploadValidator`」是**唯一**真实约束。

### 7i–7m JDBC 层（真实 Spring Boot + PgJDBC 参数绑定路径）

| # | Java 字符串 | 结果 |
|---|---|---|
| 7i | `"abc" + (char)0 + "def"` | **REJECTED**：`org.hibernate.exception.DataException` → `PSQLException SQLState=22021 :: ERROR: invalid byte sequence for encoding "UTF8": 0x00` |
| 7j/7k | 孤立高/低代理项 `(char)0xD800` / `(char)0xDC00` | **ACCEPTED**，`stored_length=7` |
| 7l/7m | 合法星光面字符 🚀 / 20 MB 文本 | **ACCEPTED**，`stored_length=7` / `20000000` |

### 7n 【最重要】孤立代理项被**静默替换**，数据库无从拒绝

字节级往返实测：

```
LONE_HIGH_SURROGATE: javaIn=[0061 d800 0062] dbHexUtf8=613f62 javaBack=[0061 003f 0062] roundtripEqual=false
LONE_LOW_SURROGATE:  javaIn=[0061 dc00 0062] dbHexUtf8=613f62 javaBack=[0061 003f 0062] roundtripEqual=false
ASTRAL_EMOJI:  javaIn=[0061 d83d de80 0062] dbHexUtf8=61f09f9a8062 javaBack=[0061 d83d de80 0062] roundtripEqual=true
```

`0xD800` → 数据库里是 `0x3F`（`?`）。**PgJDBC 的 UTF-8 编码器把不可编码的孤立代理项替换成 `?`，不抛异常；数据库收到的是合法 UTF-8，22021 永远不会触发。这是 Phase 4「非法输入必须显式失败而不是损坏数据」的唯一实测破口。** NUL、非法字节序列、超长都显式失败，只有孤立代理项静默损坏。防线只能在应用层：解析文档文本后、写库前用 `CharsetEncoder`（或等价校验）拒绝含孤立代理项的文本。

---

## 对 design 的开放问题

### Q1. 批次 2 要不要给 `knowledge_chunk.embedding` 加数据库侧维度守卫？

批次 2 建不了向量索引（2a–2e），**数据库对维度零校验**（2k），而一行错维度就让整个项目检索 22000 失败（1k）。三个选项都与 D001 兼容：**(a)** 只靠应用层（§5 现状），毒丸落库后需人工清理；**(b)** 加 5e 自洽 CHECK，成本极低但**挡不住毒丸**（6j）；**(c)** 加 6k 的 `embedding_profile` 单行表 + 约束触发器，实测能挡住毒丸（6l）与错 model（6n），维度活在数据行而非 schema；代价是每行多一次单行 `SELECT`（批量开销未测）且引入第 17 张表（触发 §2.1 建表门槛）。

**需主会话裁定**：接受 (b)、(b)+(c)，还是维持 (a)。

### Q2. Phase 4 的检索 SQL 现在就得带 cast 吗？

2h 实测：不带左侧 cast 的 `ORDER BY embedding <-> ?` **永远不会**用上 Phase 6 的表达式索引，且改漏不报错、只会慢。**建议 design.md 现在就固定检索表达式为 `embedding::vector(:dim) <-> :q`**（`:dim` 来自 Profile 配置），批次 2 即使走 Seq Scan 也用这个写法。**需主会话确认**这是否算"提前引入 Phase 6 的实现细节"。

### Q3. Hibernate 侧选 A（不映射）还是 D（`hibernate-vector`）?

| | A 不映射 | D `hibernate-vector` |
|---|---|---|
| `pom.xml` / validate | 不动 / 通过 | 加一条无 `<version>` 的依赖 / 通过 |
| 写读 / HQL | 只能走原生 SQL；HQL `UnknownPathException` | `float[]` 字段直读写；`cosine_distance` 可用 |
| 风险 | 实体与表结构不一致，validate 不提醒（6b） | 新依赖；`@Array` 让 DDL 绑定维度（6h），靠"永远 validate"兜底 |

D013 已为复合外键选定"变体 A：关联只读 + 标量可写"，其精神（服务层写普通 Java 类型、不靠实体图导航）与这里的 D 更一致；但 A 更符合"批次 2 不引入新依赖"的保守取向。**需主会话裁定。**

**必须避开的是变体 C**（`String` + `columnDefinition="vector"`）：validate 放行、运行时 42804 才炸——三个选项里唯一把错误推到运行时的写法。

### Q4. HNSW 后过滤与 TopK=8 的冲突（Phase 6 伏笔，现在记下）

2o 实测：强制走 HNSW + `project_id` 选择性过滤时，`LIMIT 8` 实际只返回 **1 行**。ARCHITECTURE §7.2 的「检索 TopK 8，project-scoped」在建索引后**不再由查询保证**。批次 2 无需处理，但 design.md 应记一条"Phase 6 建索引时必须重测 TopK 实际返回数"。

### Q5. `requirement_attachment` 要不要额外加 `(project_id, document_id) → knowledge_document(project_id, id)` 父 FK？

3j 证明三列 NOT NULL 是当前安全性的唯一依赖。加一条冗余父 FK 可在"某次重构把 `requirement_id` 改成可空"时仍拦住垃圾 document_id，与批次 1 给 `finding` 保留永久父 FK 同构。ARCHITECTURE §2.3 清单里**没有**这条。**需主会话决定**补进清单，还是明确记为"不需要，因为三列 NOT NULL"。

---

## Caveats / 未验证

1. **Flyway 未验证。** 所有 DDL（含 `CREATE CONSTRAINT TRIGGER`、`$fn$` 引用的 plpgsql 函数体、`CREATE EXTENSION vector`）均由 `psql` 直接执行，**没有**经过 Flyway 迁移脚本。批次 1 同名 caveat 仍未关闭。
2. **真实 Embedding 维度未测。** 向量实测用 3/4/8/1024/1536 维；1024 维只出现在 Hibernate `@Array` 声明里，**没有**真正写入过 1024 维数据。HNSW 在真实维度与数据量下的建索引耗时、内存与召回率均未测。
3. **`hibernate-vector` 未在 ForgePilot 真实代码中集成。** 探针是独立 `/tmp/vecprobe` 应用；它与 `backend` 现有 ArchUnit 规则、`@JdbcTypeCode(SqlTypes.JSON)` 用法、Testcontainers 配置是否冲突**未验证**。
4. **`embedding_profile` 触发器性能未测。** 每行插入多一次单行 `SELECT`；批量插入 chunk（Phase 4 主要写路径）的开销未测量。
5. **并发未测。** `ON CONFLICT ... DO UPDATE` 在真实并发下的行为（4d/4e 为单连接顺序执行）、约束触发器在并发插入下的表现均未验证。
6. **SCM（Phase 5）的数据库形态完全未测**（`scm_repository` 加密凭据列、`review_input_fingerprint` 存储形态均不在范围内）。
7. **未测 `bytea` 实际使用形态**（7c 只证明 bytea 接受 NUL；`@Lob`/`byte[]` 在 Hibernate 7.4.1 下的映射未验证），**也未测 `vector` 的其他运算符类**（`vector_ip_ops`、`halfvec`、`sparsevec`）。
8. **7j/7k 的替换行为归因未二次确认。** 已确认孤立代理项落库为 `0x3F`，但未抓包区分是 PgJDBC 编码器还是 JVM `CharsetEncoder` 默认 `REPLACE`。结论（应用层必须校验）不受影响。

## 临时资源

容器 `pgvec-research` 与 `/tmp/vecprobe`、`/tmp/t*.sql` 均已删除。未改动 `backend/pom.xml`、任何 migration 或任何 doc。
