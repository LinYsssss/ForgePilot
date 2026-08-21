# Research: AI Gateway, ai_call_log, Knowledge and attachments (Phase 4)

- **Query**: authoritative contracts for batch 2 / Phase 4 — the four tables, the attachment rule, the AI Gateway contract, credential-free testability, exit criteria as assertions
- **Scope**: internal (repo docs + batch 1 code and specs); no web search
- **Date**: 2026-08-21

**>** = verbatim quote. `INFERENCE` = my reading, stated nowhere. `OPEN-n` = needs a ruling (§6).
Bare §refs are `docs/v2/ARCHITECTURE.md`; "matrix" = `LEGACY-MIGRATION-MATRIX.md`;
"plan" = `IMPLEMENTATION-PLAN.md`.

---

## 1. Exact column and constraint list

### 1.1 Verbatim §2.1 rows ("16 张表（唯一定义处）")

> | `knowledge_document` | 项目知识与需求附件共用内容 | project_id、source_type、source_requirement_id（D005）、text、status、model/version；附件类型与归属必须匹配；`UNIQUE(project_id,id)`、`UNIQUE(project_id,id,source_requirement_id)` |

> | `requirement_attachment` | Requirement↔Document 关系 | project_id；`(requirement_id,document_id)` unique、`(project_id,document_id)` unique（一个附件只有一个归属需求）；与 Document 的 source_requirement_id 双复合 FK（D006） |

> | `knowledge_chunk` | Chunk 与唯一向量 | project_id、document_id、seq、content、metadata、`embedding vector`（无维度，D001）、provider/model/version/dimension |

> | `ai_call_log` | 评测与故障定位 | project_id、review_id、requirement_id、requirement_revision_id（三者均可空且使用含 project_id 的复合 FK）、use_case、model、token、latency、status、error |

### 1.2 Verbatim §2.3 composite references ("关键复合引用至少包括")

```text
knowledge_document
  (project_id, source_requirement_id) -> requirement(project_id, id)

requirement_attachment
  (project_id, requirement_id) -> requirement(project_id, id)
  (project_id, document_id, requirement_id)
    -> knowledge_document(project_id, id, source_requirement_id)

knowledge_chunk
  (project_id, document_id) -> knowledge_document(project_id, id)

ai_call_log
  (project_id, review_id) -> review(project_id, id)
  (project_id, requirement_id) -> requirement(project_id, id)
  (project_id, requirement_id, requirement_revision_id)
    -> requirement_revision(project_id, requirement_id, id)
```

**至少** ("at least") makes §2.3 a floor, not a closed set — batch 1 read it that way, since
`V3__requirement.sql:15` declares `(project_id) -> project(id)`, which §2.3 never lists.
`INFERENCE`: all four tables get that same plain FK.

### 1.3 Enum values that ARE sourced

- `knowledge_document.source_type`: only `'REQUIREMENT_ATTACHMENT'` is named (§2.3 quotes the
  literal in the retrieval predicate). The public value is never named → `OPEN-8`.
- `knowledge_document.status`: `PENDING` / `INDEXED` / `FAILED` — matrix, "在用；PENDING/INDEXED/FAILED | REWRITE".
- `ai_call_log.status` and `.use_case`: stated nowhere → `OPEN-8`.
- §2.4 fixes the form: "枚举 | 数据库存 `varchar` + `CHECK`，Java 侧 enum；全大写下划线".

### 1.4 How complete is the §2.1 column list?

Batch 1 is the calibration: `V2__auth_project.sql` and `V3__requirement.sql` add **exactly** the §2.1
columns plus `id`, `created_at`, `updated_at` — nothing else, both headed "Column set and constraints
follow docs/v2/ARCHITECTURE.md 2.1 / 2.3 / 2.4." `INFERENCE`: the same discipline binds Phase 4, so any
non-housekeeping column absent from the §2.1 row is an amendment — which is what makes OPEN-1/2/3
blockers rather than details.

### 1.5 What the migration must add beyond §1.1/§1.2 (`INFERENCE`)

One constraint is not spelled out as SQL anywhere: "附件类型与归属必须匹配" (§2.1) plus §2.3's "附件必须
scope 非空，公共知识必须 scope 为空" is only satisfiable as a biconditional —
`CHECK ((source_type = 'REQUIREMENT_ATTACHMENT') = (source_requirement_id IS NOT NULL))`. The
`UNIQUE (project_id, id, source_requirement_id)` on the document exists purely as the FK target for
`requirement_attachment`.

`MATCH SIMPLE` is then load-bearing in both directions. On
`knowledge_document (project_id, source_requirement_id)` the scope is null for public knowledge, so the
whole key is **skipped** for those rows — the only reason public knowledge can exist, and the same
mechanism batch 1 measured for `requirement.current_revision_id` (D013.10; `V3__requirement.sql:54-59`).
Do **not** tighten to `MATCH FULL`. Conversely all three columns of
`requirement_attachment (project_id, document_id, requirement_id)` are `NOT NULL`, so that key is
**always** fully checked — which is what makes it a real lock rather than decoration, the failure §2.3
warns about: "可空复合外键使用 PostgreSQL `MATCH SIMPLE`，因此不能用 Finding 的 nullable
Requirement/Revision/AC 外键证明父 Review 存在".

### 1.6 Where the sources disagree

Six are decision-shaped and stated fully in §6: `model/version` on the document vs. the chunk vs. the
matrix (`OPEN-1`); `version` on `ai_call_log` in the matrix but not §2.1 (`OPEN-4`); §6 requires a
失败原因 no column can hold (`OPEN-3`); §4.1's signatures cannot populate `ai_call_log` (`OPEN-14`);
Phase 4 ships a table whose FK target is a Phase 6 table (`OPEN-5`); D001 forbids a dimension in the
schema while §5 mandates a dimension-carrying index (`OPEN-12`). The seventh is cosmetic: §5/D001 name
"`V1__init.sql`" for what is actually `V1__foundation.sql`.

---

## 2. The attachment rule, precisely

**What makes a document an attachment.** §2.3: "附件 Document 必须满足
`source_type='REQUIREMENT_ATTACHMENT'` 且 scope 非空，公共知识必须 scope 为空". So attachment ⟺
`source_type = 'REQUIREMENT_ATTACHMENT'` **and** `source_requirement_id IS NOT NULL`; public
knowledge ⟺ `source_requirement_id IS NULL`. §2.1 says the same as "附件类型与归属必须匹配".

**Source of truth.** §2.3: "附件的唯一事实源是 `requirement_attachment`，
`knowledge_document.source_requirement_id` 只是受约束检索投影。" D005 repeats it. For code: the relation
row carries the domain fact; the document column exists so a **single-table** retrieval predicate can
hard-filter without joining back. Never let a user edit ownership through the projection.

**What the double composite FK is for.** The first, `(project_id, requirement_id) ->
requirement(project_id, id)`, proves the requirement exists **and** is in this project. The second is
the anti-divergence device — with `source_requirement_id` as its third referenced column the database
refuses any relation row disagreeing with the document. §2.3:

> 关系表通过 `(project_id,document_id,requirement_id) → knowledge_document(project_id,id,source_requirement_id)` 锁定二者相等，并以 `UNIQUE(project_id,document_id)` 保证一个 Document 最多属于一个 Requirement。

D005's rationale for constraint over service check: "两份可独立修改的归属字段会形成双事实源。"
§2.3 fixes the write shape: "Document 与关系同事务写入" — one transaction, never two requests.

**"Promote to public knowledge copies rather than rewrites."** §2.3: "提升为公共知识时复制为新
Document，不就地改写原附件。" D005: "跨需求共享必须复制/沉淀为新的 Project Knowledge，原附件关系保留。"
`PRD.md` P3: "需求附件只在所属需求的 AI 场景可见；跨需求共享必须显式提升为项目知识". For the code:

promotion is an **INSERT**, never an `UPDATE` — no code path flips `source_type` or nulls
`source_requirement_id`, and were there one the relation FK would block it (`23503`). Afterwards **two**
documents share the same `text`: the untouched attachment, and a new public one with
`source_requirement_id IS NULL`; the `requirement_attachment` row survives verbatim. The new document
needs its own `knowledge_chunk` rows, since chunks are keyed by `document_id` and are not shareable —
re-embed or vector-copy is unstated (`OPEN-6`), and it drives cost and test determinism. Testable
invariant: promotion leaves the source byte-identical — assert `source_type`, `source_requirement_id`,
`text`, `updated_at` unchanged and `count(*) FROM requirement_attachment WHERE document_id = :orig`
still 1.

**The retrieval predicate, quoted.** §2.3, "附件检索必须在 SQL 中同时硬过滤项目与 Requirement：":

```sql
WHERE project_id = :projectId
  AND (source_type <> 'REQUIREMENT_ATTACHMENT'
       OR source_requirement_id = :requirementId)
```

(a) It is written against `knowledge_document` columns, but retrieval ranks `knowledge_chunk` rows,
so the real query joins chunk → document and applies this to the document side (`INFERENCE`).
(b) It is *inclusive* of public knowledge, which is what P3 wants: a requirement's AI scenario sees
public knowledge **plus** its own attachments, never another requirement's. §7.2 fixes 检索 TopK = 8,
"project-scoped". D005 bounds when an attachment may be recalled at all: "只允许所属 Requirement 的
Quality/Guidance/Review 场景召回".

---

## 3. AI Gateway contract

### 3.1 What `ai` owns and must not own

§1.2: "| `ai` | OpenAI-compatible chat/embed 协议与调用记录 | 业务 Prompt、Agent 编排、自动决策 |"

§4.1 (唯一技术入口):

```text
AiGateway.chat(prompt, schema, useCase)
AiGateway.embed(texts, embeddingConfig)
```

> `ai` 负责 HTTP、认证、超时、一次有限重试、调用元数据、Token/延迟与错误分类。
> 它**不知道** Requirement/Finding/Review 等业务类型，也不暴露 tool loop。
> 业务 Prompt 归 `requirement` 与 `review` 各自所有；Requirement Quality 与一次性 Implementation Guidance 共享 AI Gateway 但使用不同 schema。不建 Prompt Registry，不建万能 ContextBuilder。

§1.3: `common ← auth, project, ai` — `ai` may depend on **`common` only**; `knowledge` sits at
`common, project, ai ← knowledge`, so `knowledge` calls `ai`, never the reverse. §8: "接口只允许
chat/embed，不暴露 tool loop". Only sanctioned subpackage: `ai.openai` (§1.1; `ArchitectureRulesTest:25`).

`INFERENCE`: `AiCallLog` fits in `ai` without breaking §1.3, because D013.1 variant A already forces
scalar `Long xxxId` writes — drop the associations and `ai` keeps only opaque ids, no compile dependency
on `review` or `requirement`. Same shape §1.3 blesses for knowledge: "只收不透明 scope id".

### 3.2 Timeout, the one retry, and where its authorization comes from

§7.2 (运行边界，初值):

> | LLM 单次调用超时 | 120 s | 超时按瞬时失败处理 |
> | LLM 重试次数 | 1 | 仅瞬时错误（429/5xx/网络） |

§7.1, right after the "不引入 … Resilience4j Circuit Breaker" list: "HTTP timeout + 一次有限 retry +
Review FAILED + 人工 retry 足够覆盖当前故障事实。" §4.1 assigns "一次有限重试" to `ai`; plan Phase 4
lists "超时、一次 retry".

**This project otherwise avoids retries. Here is where each half comes from.** There is no blanket
"no retries" rule anywhere in `docs/v2/`. What exists is a **batch-1 boundary check**, not a law (`result.md` §4 records
`grep -rniE "retry|fallback|@Recover"` → "无" — batch 1 shipped none, which does not forbid batch 2);
a **frontend** prohibition that does not reach the backend (`.trellis/spec/frontend/`
`quality-guidelines.md:16`, `state-management.md:39`, `hook-guidelines.md:44,72`); D013.8's "必须在写法上
根除而不是靠重试", about the LEADER swap, not I/O; and D013.11 / `error-handling.md`, about database
constraint conflicts, not outbound HTTP.

So the authorization is **§4.1 + §7.1 + §7.2 + Phase 4 of the plan**, narrow on three axes: `ai` only,
transient classes only (429 / 5xx / network / timeout), count exactly 1. A retry in `knowledge`
ingestion, around the database, or on a non-429 4xx has no authorization.

**Do not merge this with the other "once".** §3.5 allows "非法 JSON 允许**一次** format-repair；仍失败则
FAILED" — a `review`-owned Phase 6 behavior on a *successful* response with a malformed body. Two
distinct budgets; conflating them silently permits up to 4 calls. Classification is REFERENCE (matrix:
`AiTransientFailureClassifier` → `ai.openai.AiFailurePolicy`, "保留决策表"), and a second runtime is
banned twice (matrix `ai/langchain4j/*` → DROP; §7.1 "不引入 … 第二 AI runtime").

### 3.3 What `PromptSanitizer` must reject — the honest answer

**No authoritative document says `PromptSanitizer` rejects anything.** Every source describes
transformation, not refusal. The only four mentions in `docs/v2/`:

- §4.3 (Prompt 安全, the entire section): "Requirement、文档、PR 标题、代码注释**全部是不可信数据**，不得改变
  system/task 指令；发送前做敏感信息脱敏与预算裁剪。"
- Matrix: "`ai/PromptSanitizer` | 敏感信息脱敏、Unicode 截断 | KEEP | 纯函数；继续补注入与密钥格式测试"
- §8: "不可信标记 + source 白名单 + 输出回查"; plan 测试纪律: "安全测试必须覆盖 … SSRF 和 Prompt injection."

Its sourced duties, none of them a rejection: **redact secrets** ("敏感信息脱敏" — the KEEP note asks for
more "密钥格式测试", so API-key-shaped strings in requirement text, documents or patches must not leave
the process, masked rather than errored); **Unicode-safe truncation** to budget ("预算裁剪" + "Unicode
截断" — never split a surrogate pair when cutting to a character budget); **mark untrusted data** so it
cannot pass as instruction ("不可信标记"). It is a **pure function** ("纯函数"), so it cannot log, touch
the database, or throw on I/O.

The three rejections Phase 4 does owe — invalid UTF-8, NUL, oversize — belong to a **different** class:
`KnowledgeUploadValidator`, KEEP'd for exactly that ("大小/类型/UTF-8/NUL/文件名安全") and named owner of
the 5 MB limit in §7.2. Assigning them to the sanitizer would put upload policy inside `ai`, which §1.2
forbids. `OPEN-7`: may the sanitizer ever hard-fail?

### 3.4 Exactly which columns `ai_call_log` records

Verbatim §2.1, purpose "评测与故障定位":

> project_id、review_id、requirement_id、requirement_revision_id（三者均可空且使用含 project_id 的复合 FK）、use_case、model、token、latency、status、error

- `project_id` NOT NULL (§2.3: every project-scoped table carries it).
- `review_id` / `requirement_id` / `requirement_revision_id` all nullable ("三者均可空"), because one
  gateway serves Quality (requirement+revision), Guidance (requirement+revision), Review (review, maybe
  requirement) and embedding (none of the three).
- `use_case` is the third `chat()` parameter in §4.1 — the discriminator across those scenarios.
- `model` = the model actually used, for 评测 reproducibility. `token` singular → `OPEN-4`. `latency`
  unit unstated, ms the only sane read (`INFERENCE`). `status` NOT NULL, values unstated (`OPEN-8`).
  `error` nullable — the "故障定位" half.

Written by `ai` for **both** `chat` and `embed` (§1.2: "chat/embed 协议与**调用记录**"). Never a page:
§6 "AI Logs 均**不做**一级页面". Consumer is Phase 8 evaluation ("可复现评测：… Token、耗时").
`INFERENCE` forced by "故障定位": the row must be written on **failure** too and outside the caller's
transaction, or a rolled-back Review erases why it failed. `OPEN-10`.

---

## 4. Testability without credentials — the load-bearing section

**The hard constraint, verbatim** (plan, "下一步：批次 2", last paragraph):

> **批次 2 的硬约束：CI 不得依赖任何 AI/SCM 凭据或仓库秘密。** AI Gateway、Embedding、
> GitHub Webhook 与 PR 快照的测试一律打到进程内或本地的假服务端，不打真实 provider。
> 真实凭据只在人工验证时使用，且不进仓库。

Batch 1 already holds this line (`result.md` §4: "CI 仍不依赖任何 AI/SCM 凭据或仓库秘密").

### 4.1 The seam is `AiGateway` itself

§4.1 defines `ai` as the **唯一技术入口** with exactly two methods — that is already the seam, and no new
abstraction should be invented (D013 总判据: "优先选零新增表、零新增列、零新增抽象的可行解"). Make `AiGateway`
a Java interface in `ai`, implemented by `ai.openai.OpenAiCompatibleGateway`; everything above it
(`knowledge` ingestion and retrieval, `requirement` guidance) depends on the interface and never touches
HTTP. Two tiers follow:

| Tier | Proves | Tool |
|---|---|---|
| Above the gateway | ingestion, chunking, dimension check, attachment scoping, retrieval boundary, guidance shape | a deterministic **fake `AiGateway` bean** — no socket |
| The gateway itself | HTTP shape, auth header, 120 s timeout, exactly-one retry, transient/permanent classification, `ai_call_log` contents | a **real local socket** serving canned responses |

### 4.2 Is an in-process stub HTTP server on the existing classpath? **Yes — two of them.**

Verified against `backend/pom.xml` and `/root/.m2/repository` on this machine. **No new dependency is
required.**

**(a) `com.sun.net.httpserver.HttpServer` — the JDK's own server. Strongest option.**

```
$ docker run --rm eclipse-temurin:21-jdk java --list-modules | grep -iE "httpserver|java.net.http"
java.net.http@21.0.11
jdk.httpserver@21.0.11
```

`jdk.httpserver` ships in the same image `backend/README.md` already prescribes for builds: zero
dependency delta, real TCP socket, real HTTP/1.1. It can serve a canned OpenAI-compatible `200`; return
`429` / `503` / malformed JSON, driving `AiFailurePolicy`; sleep past the configured timeout, proving
the timeout actually fires; **count requests**, the only way to prove "exactly one retry, transient
classes only" — a permanent `400` must leave the counter at 1; and assert the `Authorization` header
carried the configured dummy key, proving auth wiring without any real key existing. Wire it with
`@DynamicPropertySource` setting `ai.base-url = http://127.0.0.1:<port>`, the mechanism
`PostgresTestBase:33-38` already uses; bind port `0` so CI never collides.

**(b) `org.springframework.test.web.client.MockRestServiceServer` — already on the classpath** via
`spring-boot-starter-test` (verified: the class and both `RestClient` / `RestTemplate` builders are in
`spring-test-7.0.8.jar`). It binds to a `RestClient.Builder` or `RestTemplate` and intercepts below the
HTTP layer — excellent for request **shape** (path, headers, JSON body), with a readable `ExpectedCount`
API for call counts. Weak exactly where it matters most: no socket means no honest timeout test, and
connection failures must be simulated, not observed.

**Recommendation** (`INFERENCE`): (a) for timeout / retry-count / classification, (b) for request-shape
assertions. Both are free and complementary.

**What is NOT available, and should not be added.** WireMock and OkHttp MockWebServer are absent from
`backend/pom.xml` and from the local repository (`find ~/.m2/repository -iname "*wiremock*" -o -iname
"*mockwebserver*"` → no output). Either would be a new dependency, and `.trellis/spec/backend/quality-guidelines.md` requires a concrete
failure a new gate would have caught. There is none — the JDK server covers every listed behavior. Also
verified: `spring-boot-starter-webmvc` pulls `spring-boot-starter-tomcat` (so
`@SpringBootTest(webEnvironment = RANDOM_PORT)` is available if a real port is ever wanted); `RestClient`
is in `spring-web` 7.0.8; `@MockitoBean` and `@TestBean` are in `spring-test` 7.0.8.

### 4.3 The bean-replacement seam

`@TestBean` (spring-test 7.0.8, verified) replaces the `AiGateway` bean via a static factory method — no
Mockito DSL, the fake is ordinary Java; `@MockitoBean` is the alternative when a test wants call
verification. Either way the rest of the context is untouched, so the real PostgreSQL, Flyway, security
chain and error contract still run — the dimension-mismatch and attachment-scoping tests need the
**real** database and only a fake provider.

### 4.4 What a deterministic fake embedding looks like

Requirements: same input → same vector (re-index idempotence); different input → different vector; no
network; configurable dimension; plus a variant returning the **wrong** dimension.

```java
static float[] fake(String text, int dim) {      // dim = configured profile dimension
    long h = 1125899906842597L;                  // fixed seed, nothing random
    for (int i = 0; i < text.length(); i++) h = 31 * h + text.charAt(i);
    float[] v = new float[dim];  double norm = 0;
    for (int i = 0; i < dim; i++) {              // xorshift, deterministic
        h ^= (h << 13); h ^= (h >>> 7); h ^= (h << 17);
        v[i] = (float) ((h % 2000) / 1000.0 - 1.0);  norm += (double) v[i] * v[i];
    }
    float inv = (float) (1.0 / Math.sqrt(norm)); // L2-normalise so cosine behaves
    for (int i = 0; i < dim; i++) v[i] *= inv;
    return v;
}
```

Three variants drive three tests: the normal one; `dim + 1` for the dimension-mismatch assertion (§5.5);
and one that throws or returns a transient failure for `ai_call_log.status = FAILED`.

**The caveat that must not be buried.** A hash-based fake gives determinism but **not semantic
similarity** — "登录接口" and "authentication endpoint" land nowhere near each other. Retrieval tests
against it can honestly assert **isolation, filtering, TopK size, ordering stability and dimension
handling**, and an exact hit when the query text is byte-identical to a chunk (distance 0, rank 1).
They cannot assert retrieval *quality* — that belongs to the Phase 6 development set and the Phase 8
holdout with a real provider, where the plan already puts it. A Phase 4 test claiming semantic recall
against a hash fake would be exactly the false green `database-guidelines.md` bans elsewhere. If a test
needs controlled neighbourhood structure, let the fixture text carry its own coordinates (a marker the
fake parses into a fixed vector) rather than pretending the hash is a language model.

### 4.5 The one thing this does not solve

A stub proves the gateway against a contract *we* wrote, not that the real provider honors it. Close
that gap the way batch 1 closed the nginx hop: one manual run with a real key, recorded in `result.md`,
key never committed — and say so, rather than letting a green suite imply more than it proved.

---

## 5. Phase 4 exit criteria as testable assertions

Verbatim (plan, Phase 4): "退出：项目隔离、非法 UTF-8/NUL/超限/维度不匹配显式失败，附件关系和检索边界通过测试。"

Layers follow batch 1: `DatabaseConstraintTest` writes through `JdbcTemplate` **below** application
code and asserts the SQLState; `*ApiTest` uses `MockMvc` over the real filter chain; both extend
`PostgresTestBase`.

**5.1 项目隔离.** DB layer, each must fail `23503`: a `knowledge_chunk` whose `(project_id,
document_id)` names another project's document; a `requirement_attachment` whose `(project_id,
requirement_id)` names another project's requirement; a `knowledge_document` with a foreign-project
`source_requirement_id`; an `ai_call_log` with a foreign-project `requirement_id`. HTTP layer: project
A's member requesting project B's document id gets **404**, byte-identical to a nonexistent id —
non-member and missing must be indistinguishable (`.trellis/spec/backend/error-handling.md`; precedent
`BatchOneApiTest.anotherProjectsIdsAreInvisibleOverHttp`). Static: every knowledge repository read
method takes `projectId` (§2.3: "禁止裸 id 查询后再补权限判断").

**5.2 非法 UTF-8.** Bytes that are not valid UTF-8 (a lone `0xFF`, or a truncated sequence such as
`E4 B8`) return **422** with the single `ApiError` body and leave `count(*) FROM knowledge_document`
unchanged; layer is a `KnowledgeUploadValidator` unit test plus one HTTP test.
**The trap that makes this test worth writing:** `new String(bytes, UTF_8)` does *not* fail — it
silently substitutes U+FFFD, so a validator built on it passes a "no exception" test while corrupting
the document. Decode with `CharsetDecoder` + `CodingErrorAction.REPORT`, and assert no U+FFFD reached
storage.

**5.3 NUL.** Content containing code point U+0000 returns **422**, no row written. Second assertion at
the DB layer: writing that code point into `knowledge_document.text` through `JdbcTemplate` fails with
SQLState `22021` (`invalid byte sequence for encoding "UTF8": 0x00`). That documents *why* the validator
exists — PostgreSQL `text` cannot hold NUL at all — and matches batch 1's rule that every migration
ships an "assert the constraint really rejects" test (`result.md` §10.5).

**5.4 超限.** Source, verbatim §7.2: "| 单文件上传上限 | 5 MB | `KnowledgeUploadValidator` |". Assert both
sides: 5 MB + 1 byte rejected, 5 MB exactly accepted. **Layering trap:** if
`spring.servlet.multipart.max-file-size` is at or below the same limit, Spring raises
`MaxUploadSizeExceededException` in the filter chain and the validator never runs — and that must still
produce the one `ApiError` body, not a Spring default page. Test the **body**, not just the status, and
decide deliberately which of the two rejects. The frozen `-Xmx384m` envelope also forbids buffering
5 MB several times over.

**5.5 维度不匹配.** Source: D001 "应用写入时显式校验维度"; §5 "应用层写入时校验向量维度与当前 Profile 一致，
不一致显式失败." (1) With the `dim + 1` fake, ingestion fails explicitly — document ends `FAILED`,
**zero** `knowledge_chunk` rows written, failure visible to an admin (§6 "管理员只需看到文档状态与失败原因",
where `OPEN-3` bites since no column can hold the reason). (2) **The honest one**: prove the database
does *not* protect you — insert a wrong-dimension vector via `JdbcTemplate` and assert it **succeeds**,
because `embedding` is typmod-less (D001). That test justifies the application-layer check existing;
without it a reader assumes the column guards them. (3) `knowledge_chunk.dimension`
equals the vector's actual length for every written row.

**5.6 附件关系与检索边界.** The single most important row in the batch-2 constraint suite: a
`requirement_attachment` whose `requirement_id` differs from the document's `source_requirement_id` →
`23503`. That makes "投影不会漂移" a fact rather than a hope, and §2.3 already lists it among the fixed
integration tests ("附件关系与投影不一致"). Also: a second `requirement_attachment` for the same
`document_id` → `23505`; an attachment `source_type` with a null scope, and a public one with a non-null
scope, both → `23514`. Retrieval boundary (the P3/D005 test): in one project seed requirement R with
attachment Da, R' with Db, and public document Dp; search scoped to R returns Da and Dp and **never**
Db, then the mirror for R'. Promotion: promoting Da yields a new public document while Da and its
relation row are unchanged.

**5.7 Two Phase 4 items whose exit criteria are silent.** **HNSW index** — D001 / §5 require Phase 4 to
add the expression index by separate migration, with "检索 SQL 须用一致的 cast 表达式"; testable by
`EXPLAIN` showing an index scan, and it fails silently if index and query expressions differ by so much
as a cast (blocked on `OPEN-11`/`OPEN-12`). **一次性 Implementation Guidance** — listed in Phase 4 and
`PRD.md` §4, yet it appears in **none** of the 16 tables, so `INFERENCE`: response-only, never persisted,
with only `ai_call_log` recording that the call happened. Consistent with "不保存对话" and §6's ban on an
Assistant page, but a reload then costs another AI call (`OPEN-13`).

---

## 6. Open questions — for a decision, not for me

| # | Question | Why it blocks / conflict |
|---|---|---|
| OPEN-1 | What do `knowledge_document.model` / `version` mean — embedding profile last used, or parser/chunker version? Redundant with the chunk's four audit columns? | Migration cannot pick a type or nullability. §2.1 doc vs §2.1 chunk vs matrix (chunk only) |
| OPEN-2 | Where does the uploaded **filename / display title** live? §2.1 lists no such column, yet the validator is KEEP'd for "文件名安全". | A column on a §2.1-frozen table is an amendment; batch 1 added none |
| OPEN-3 | Where does the **failure reason** live? §6 requires showing 失败原因; the row has `status` and nothing else. | As OPEN-2; also makes part of exit criterion 5.5 unverifiable |
| OPEN-4 | `ai_call_log.token` — one number, or prompt/completion/total? Does `version` exist (matrix) or not (§2.1)? | Phase 8 reports Token; frozen once migrated |
| OPEN-5 | **`ai_call_log` ships in Phase 4, but its `(project_id, review_id) -> review(project_id, id)` FK cannot exist until Phase 6.** Column now with the FK added by the Phase 6 migration, table moved to Phase 6, or something else? | Blocks the V4 migration entirely. D006 requires such a conflict to go back to a decision — "不得静默降级为无测试的 Service 纪律" — and D012.3 keeps that loop mandatory |
| OPEN-6 | On promote-to-public, are the new document's chunks **re-embedded** or the vectors **copied**? | Cost, determinism, whether promotion needs a live provider |
| OPEN-7 | May `PromptSanitizer` ever hard-fail, or is masked+truncated always the answer? | Whether `ai` may throw, and whether "reject" tests exist. Unsourced either way |
| OPEN-8 | Enum values: `source_type`'s public value, `ai_call_log.status`, `ai_call_log.use_case`. | §2.4 requires a DB `CHECK` listing them, so they freeze at migration time |
| OPEN-9 | Is `(document_id, seq)` unique on `knowledge_chunk`? `requirement_revision` got `UNIQUE(requirement_id, seq)`. | Cheap now, an extra migration later. §2.1 silent |
| OPEN-10 | Is `ai_call_log` written in the caller's transaction or its own? | A rolled-back Review would erase why it failed, defeating "故障定位" |
| OPEN-11 | Cosine (`<=>` / `vector_cosine_ops`) or L2 (`<->` / `vector_l2_ops`)? | Index and query must match exactly or the index is dead weight, and migrations are append-only. §5 names no operator; `FoundationDatabaseTest` probes `<->` |
| OPEN-12 | Which Embedding Profile does Phase 4 freeze (provider / model / **dimension**)? | Nothing can be indexed until chosen — D001 says "模型维度是部署配置，不能让相同 Flyway 版本在不同环境生成不同结构" while §5 mandates a dimension-carrying index |
| OPEN-13 | Is Implementation Guidance really never persisted? No table holds it. | A reload re-costs an AI call; limits what a Phase 4 demo can show |
| OPEN-14 | §4.1's two signatures cannot supply the four ids `ai_call_log` requires. What is the call-context parameter, and does it stay opaque `Long`s so §1.3 holds? | The signature is a frozen §4.1 quote. `INFERENCE`: an `ai`-owned `AiCallContext` of opaque ids mirrors "只收不透明 scope id" |
| OPEN-15 | Who owns the Embedding Profile config and performs the dimension check — `ai` or `knowledge`? §5 says only "应用层". | Where config lives and where the explicit failure is raised. §5 vs §1.2 |

Carried forward from batch 1 (`result.md` §10) and touching this batch: member-removal semantics vs
`requirement.assignee`; and a disable-account endpoint, if added, must bump `session_version`.

---

## Caveats / Not Found

- **Legacy source is not in this repository** — the matrix points at read-only `LinYsssss/reposage` @
  `96137dd3…`, so the bodies of the two KEEP classes this batch depends on, `PromptSanitizer` and
  `KnowledgeUploadValidator`, could not be read. §3.3 and §5.2–5.4 derive from the matrix's one-line
  descriptions plus the V2 documents; fetching those two files is worth doing before design.
- **`docs/v2/adr/` was not read**; `DECISIONS.md` says the 11 ADRs were converged into it.
- **No web search.** pgvector's HNSW dimension ceiling, `vector` operator classes and OpenAI-compatible
  response shapes were not verified against a current source this session.
- **Read, not measured.** Nothing here was run against a database. Whether Hibernate 7.4.1 accepts a
  scalar-only mapping for `ai_call_log`'s four ids should be verified the way D013.1 was — by starting
  the context, early. The classpath facts in §4.2 *were* executed and are reliable.
- **Phase 5 (GitHub SCM) is out of scope here.**
