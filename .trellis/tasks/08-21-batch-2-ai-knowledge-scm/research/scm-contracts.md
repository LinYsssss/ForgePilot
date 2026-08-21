# Research: GitHub SCM contracts (batch 2 / Phase 5)

- **Query**: authoritative contracts for `scm_repository`, `pull_request`, `pull_request_requirement_event`;
  repository identity; `review_input_fingerprint`; webhooks; `PullRequestChanged`; `REQ-<n>`; author mapping;
  credential-free testing
- **Scope**: internal (docs/v2, .trellis, backend) · **Date**: 2026-08-21

`[Q]` = verbatim quote (file + section given). `[I]` = my inference, written nowhere, no authority.

---

## 1. Exact column and constraint list

### 1.1 `ARCHITECTURE.md` §2.1 — the three rows, verbatim

`scm_repository` (line 95):
> [Q] | `scm_repository` | 项目的活动仓库与加密凭据 | `project_id` unique；provider、规范化 `instance_identity`、external_id、api_base、encrypted_token/secret；有 PR 后稳定身份三元组不可修改，api_base 更新须验证同一实例（D010）；`UNIQUE(project_id,id)` |

`pull_request` (line 96):
> [Q] | `pull_request` | PR/MR 权威快照、作者与需求关联 | `(repository_id,external_number)` unique；当前 base_sha、head_sha、`review_input_fingerprint`（由规范化 base/head、changed-file manifest、patch 及可用的稳定 Diff version 确定性计算）；source_revision/source_updated_at 仅用于乱序保护；requirement_id nullable + 普通索引（D004/D007）；author_external_user_id、author_username（不可变作者快照）、author_user_id（可重算映射，复合 FK 指向 project_member，列级 `ON DELETE SET NULL`，D010）；`UNIQUE(project_id,id)` |

`pull_request_requirement_event` (line 97):
> [Q] | `pull_request_requirement_event` | **仅**记录 PR↔需求关联变更审计 | project_id、pull_request_id、from_requirement_id、to_requirement_id、actor_type(`USER/SYSTEM`)、actor_user_id（可空，→ `user_account`）、reason、created_at；CHECK 保证 type 与 actor 组合合法；与关联修改同事务写入（D007） |

### 1.2 `ARCHITECTURE.md` §2.3 — composite FKs, verbatim

> [Q]
> ```text
> pull_request
>   (project_id, repository_id) -> scm_repository(project_id, id)
>   (project_id, requirement_id) -> requirement(project_id, id)
>   (project_id, author_user_id) -> project_member(project_id, user_id)
>     ON DELETE SET NULL (author_user_id)
>
> pull_request_requirement_event
>   (project_id, pull_request_id) -> pull_request(project_id, id)
>   (project_id, from_requirement_id) -> requirement(project_id, id)
>   (project_id, to_requirement_id) -> requirement(project_id, id)
> ```

§2.3 lists no FK for `scm_repository`; its `project_id` reference follows from the blanket rule — every
project-scoped table carries `project_id`, every intra-project FK carries it too, read paths take `projectId`,
constraint conflicts map to 409/422. §2.4 adds: `snake_case` singular, `id` BIGINT identity PK, `created_at`/
`updated_at` `timestamptz`, enum = `varchar` + `CHECK`, Flyway `V<n>__<snake_case>.sql`.

### 1.3 What §2.1 does not enumerate

Its third column is headed **关键约束**, not "columns". Required elsewhere but absent there:
`pull_request.project_id` / `repository_id` / `external_number`; `pull_request.title` (§4.2 `ReviewContext
.pullRequest: … number, baseSha, headSha, inputFingerprint, title`); `created_at`/`updated_at` on all three;
`scm_repository.project_id` + `UNIQUE(project_id)`.

**Not defined anywhere: where the changed-file manifest and patches live.** §1.2 gives `scm` "PR 元数据与
**patch**", §3.1 requires the fingerprint to cover "changed-file manifest 与每个 patch 内容", §4.2 requires
`changedFiles[]: path, changeType, providerPatch` — yet §2.1 lists no patch column, there is no
`pull_request_file` table, and a 17th table needs "已发生的业务事实 + 新决策记录". → **OQ-2**.

### 1.4 Disagreements between sources

1. **`ON DELETE SET NULL` vs. batch 1's "no `ON DELETE` anywhere"** — not a real conflict; batch 1 pre-carved this
   exception. `database-guidelines.md`: no `ON DELETE` is declared anywhere because §2.3 defines deletion
   semantics only for `pull_request.author_user_id`, leaving every other hard delete to be refused by the foreign
   key until a phase decides what deletion means. `pull_request` is therefore the only table that may carry an
   `ON DELETE`, on that one FK, in the PG15 column-level form (§7.1 names it as one of the two syntaxes making
   PostgreSQL 15 a hard floor).
2. **`scm` owns `REQ-N` parsing (§1.2) but may not depend on `requirement` (§1.3).** Real conflict → §6, **OQ-1**.
3. **The repository triple has no uniqueness constraint.** D010 calls it the stable identity; §2.1 declares only
   `project_id` unique and `UNIQUE(project_id,id)`. Nothing stops two projects registering one repo. **OQ-3**.
4. **The freeze rule has no declared enforcement mechanism.** §2.1 authorizes a constraint trigger only for
   `finding`; `database-guidelines.md`: "A constraint that is only ever enforced by a service is not enforced."
   **OQ-4**.

---

## 2. Stable repository identity

> [Q] D010: 仓库稳定身份为 `provider + normalized instance identity + external repository id`。产生 PR 后三元组冻结；凭据/Secret 可换，api_base 只有验证仍指向同一实例才可更新。…理由：用户名可变且可被复用；仅冻结 provider/external id 无法区分不同自建实例。

§2.3 末段 repeats the triple ("`api_base` 可变但只能指向同一规范化实例"); `PRD.md` §8 adds the escape hatch —
确需更换仓库或实例须**新建项目**.

- **Frozen once a PR exists**: `provider`, `instance_identity`, `external_id`. **Never frozen**:
  `encrypted_token`, webhook secret. **Conditionally mutable**: `api_base`.
- [I] "有 PR" = at least one `pull_request` row for that `repository_id`; no document defines the predicate.
- **Why `external_id`, not `owner/repo`**: GitHub's numeric repo id survives rename and transfer, the slug does
  not. [I] `owner/repo` may be stored for display, never for identity or authorization.

**"Normalized instance identity", concretely** — [I], never defined. It must distinguish two self-hosted
instances and be checkable against `api_base`:

| Deployment | `api_base` | `instance_identity` [I] |
|---|---|---|
| github.com | `https://api.github.com` | `github.com` |
| GHE Server | `https://ghe.corp.example/api/v3` | `ghe.corp.example` |

[I] Normalize = lowercase host, drop scheme, default port, path and trailing slash, IDN → A-label. The SaaS case
is special — `api.github.com` and `github.com` are one instance — so a raw host needs a provider-specific
mapping. Pure function: unit-testable, no network. → **OQ-5**.

**What an `api_base` update must verify** [I] (D010 says only "验证仍指向同一实例"): (1) the identity derived from
the *new* `api_base` equals the stored `instance_identity` — a pure function, no network, and alone it defeats
"re-point a github.com repo at my own server"; **and** (2) a live call to the new `api_base` returns the same
`external_id`. (3) `api_base` is caller-supplied and dereferenced server-side ⇒ SSRF: the LEGACY matrix marks
`git/OutboundUrlPolicy` **KEEP** → `common.security.OutboundUrlPolicy`, and the plan requires SSRF coverage in
security tests. The class does not exist yet. → **OQ-9**.

---

## 3. `review_input_fingerprint`

### 3.1 Inputs

> [Q] D003: `review_input_fingerprint` 是规范化 SCM Diff 输入的确定性哈希，至少覆盖 provider/instance/repository、base/head、changed-file manifest 与 patch 内容；Provider 若提供能标识实际 Diff 版本的稳定 revision，也纳入哈希。仅用于乱序保护的事件序号或更新时间不应无条件进入哈希。Base 或 Diff 改变时，即使 head 不变，也产生新 Review 身份。

§3.1 restates it and sharpens two points: the hash covers "**每个** patch 内容", and 仅用于事件排序的
revision/time **不得单独制造新身份**.

**In**: provider; normalized instance; repository; base SHA; head SHA; changed-file manifest; each patch's
content; the provider's stable Diff version *if it has one*. **Explicitly out**: `source_revision`,
`source_updated_at` (§2.1 "仅用于乱序保护"; D003 above).
**Not listed ⇒ out** [I]: PR title, author, requirement association — the association must not mint a new Review
identity, D003 keeping `requirement_revision_id` as a *separate* identity component. [I] **GitHub exposes no
stable diff revision**, so that slot stays empty for GitHub.

### 3.2 Required property

**Equal fingerprint ⟺ equal normalized review input.** *Determinism* (same input ⇒ same hash across processes,
restarts, pagination order): without it a replay mints a new Review identity. *Sensitivity*: §3.1 requires that
"Base、changed files、patch … 改变时，即使 head SHA 不变，也必须形成新的 Review Identity" — without it a base change
reuses a stale Review and the Decision gate is bypassed. [I] SHA-256 over a length-prefixed canonical encoding
gives both.

### 3.3 What must be normalized — and where the docs are silent

**Silent on all of it.** §3.6.2 gives normalization rules, but for `evidence_hash`/`basis_hash` — a different hash
on different input ("统一换行并去除易变行号…不得做通用空白折叠"). Applying those here is **wrong**: a whitespace-only
change is a real Diff change. Seven decisions no document makes (**OQ-6**):

1. **File ordering** — `GET /pulls/{n}/files` is paginated (30/page, max 100) and a 300-file PR spans pages;
   order is conventional, not contractual. Sort by path, byte-wise, before hashing.
2. **Path case** — §3.4.3/§3.6.1 require case-sensitive paths ("不做 lower-case"); the LEGACY matrix records the
   bug "旧算法 lower-case path … 可能错误合并". [I] Same rule here.
3. **Patch encoding** — GitHub's `patch` is a JSON string and may be **absent** (binary files, per-file diff
   limit, every file once the PR exceeds GitHub's cap). Absent ≠ empty; they must hash differently.
4. **Line endings inside the patch** — must **not** be normalized. Opposite of §3.6.2's rule.
5. **Field framing** — `path + patch` concatenated without length prefixes lets two manifests produce one string.
6. **Which manifest fields participate** — §4.2 names `path` and `changeType` only. Pin it once.
7. **Truncation** — §7.2 caps 300 changed files / 60000 chars per patch. Truncating before hashing lets two PRs
   share a fingerprint; after, it covers content no Review saw. Docs place truncation in `review`, so [I] the
   fingerprint covers the **untruncated** provider manifest.

---

## 4. Webhook handling

### 4.1 Raw-byte signature verification

> [Q] §3.1: Webhook 是同步信号，不是 PR 真值。`scm` 先完成 raw-byte 验签，再从 Provider 读取权威 PR/changed-file 快照。

**Why raw bytes, not a re-serialized body.** The HMAC (`X-Hub-Signature-256: sha256=<hex>`) covers the exact
octets GitHub signed. Parsing to a DTO and re-serializing changes key order, whitespace, `\uXXXX` escaping,
number formatting and Unicode normalization, so legitimate deliveries fail — and an implementation that "fixes"
that by canonicalizing before verifying has stopped authenticating the bytes it acts on. [I] Take
`@RequestBody byte[]`, verify, then hand **those same bytes** to Jackson. Compare constant-time
(`MessageDigest.isEqual`); the LEGACY matrix marks `GitHub/GitLab WebhookVerifier` **KEEP**.

**On an invalid signature: nothing is written** — `IMPLEMENTATION-PLAN.md` Phase 5 退出条件:
> [Q] 退出：重放幂等、乱序/并发不回退、Base/Diff 变化更新 fingerprint、非法签名不写数据、编译依赖无 `review`。

No document gives a status code. [I] 401 with the `common.ApiError` body. → **OQ-7**.

**Two batch-1 mechanisms the endpoint collides with** (`auth/SecurityConfig.java`): the chain ends in
`.anyRequest().authenticated()` and CSRF is `.csrf(CsrfConfigurer::spa)`. A GitHub POST carries neither a session
nor `X-XSRF-TOKEN`, so the path needs `permitAll()` **and** a CSRF exclusion or it is rejected before verification
runs. Per `error-handling.md` that rejection writes the `ApiError` body itself — the chain runs before MVC
dispatch and never reaches `ApiExceptionHandler`.

### 4.2 "A signal, not the truth"

D010: 验签后读取 Provider 权威快照，并用 source revision/time 单调更新；**重放、并发和乱序事件不得回退当前
base/head/patch**。The payload is arbitrarily stale (GitHub retries) and attacker-influenced in ordering; the API
read is current by definition — which is also what makes replay harmless.

### 4.3 The out-of-order rule

> [Q] §3.1: 仅当 source revision/updated time 不旧于当前记录时才更新 `pull_request`，旧事件不得回退 head、base 或 patch。

Note the comparison: **不旧于** = `>=`, not `>`. Equal timestamps still update — necessary because GitHub's
`updated_at` has 1-second resolution and successive pushes can share one; safe because the values come from a
fresh authoritative read, so an update at an equal timestamp writes the same bytes.

[I] The compare-and-write must be atomic. Docs specify a row lock only for the Decision path; nothing for the PR
update path, yet two concurrent deliveries that read-then-write will interleave. Either `SELECT ... FOR UPDATE`
before comparing, or a conditional `UPDATE … WHERE source_updated_at <= :new`. A first-insert race on
`(repository_id, external_number)` → 409 per D013.11. → **OQ-8**.

### 4.4 Replay idempotency as a testable assertion

§2.1 refuses a delivery table ("**不建**：… `webhook_delivery`") and demotes `WebhookDeliveryRecorder` to
REFERENCE. So idempotency is **not** a delivery-id unique key; it is a property of snapshot-based update.

```text
A1 (replay). Repo R, PR #7, snapshot S; identical raw body + signature POSTed 3x ->
  count(pull_request WHERE repository_id=R AND external_number=7) = 1;
  (base_sha, head_sha, review_input_fingerprint, source_updated_at) byte-identical to their
  values after call #1; count(pull_request_requirement_event for that PR) unchanged after #1;
  every response 202.
A2 (out-of-order). Row holds S2 (t2); a delivery whose provider read returns S1 with t1 < t2 ->
  base_sha, head_sha, review_input_fingerprint, source_revision, source_updated_at unchanged,
  response still 202 (an old event is a no-op, not an error).
A3 (concurrency). Two deliveries on two threads returning S1(t1) and S2(t2>t1) -> the committed
  row equals S2 for every interleaving, and exactly one row exists.
A4 (bad signature). One byte of the signature header flipped -> no pull_request row, no event
  row, no outbound provider call.
```

A2 and A3 are constructible **only** if the test controls what the provider returns (§8). The 202 comes from §3.1
("Webhook 在 PR 与 PENDING Review 均提交后返回 202"); [I] with no PENDING Review in batch 2 the condition
degenerates to "after the PR is committed".

---

## 5. `PullRequestChanged`

> [Q] §3.1: `scm` 在更新 PR 的数据库事务内发布**同步**进程内 `PullRequestChanged`；`review` 的同步监听器参加同一事务，按 `(pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)` 幂等创建或取得 Review(PENDING)。监听失败则整个 SCM 事务回滚，禁止出现"PR 已更新但无应有 PENDING Review"的提交结果。`scm` 只依赖事件 contract，仍不编译依赖 `review`。

D008 adds: only an after-commit callback may submit the bounded executor (提交前启动会读取未提交数据).

**Why the listener must join the same transaction**: the invariant is "no committed state where the PR is updated
but the implied PENDING Review is missing". An after-commit listener cannot roll the PR back; a separate
transaction commits one half and loses the other. [I] This forces a plain `@EventListener` (synchronous, same
thread, same transaction); `@TransactionalEventListener` — default phase `AFTER_COMMIT` — is the wrong tool and
belongs in the spec as forbidden, being the reflex choice.

**Payload**, from §3.3: `PullRequestChanged(prId, headSha, inputFingerprint)` — `requirement_revision_id` belongs
to Review identity but not to the event, so the listener derives it.

**Publishing without compiling against `review`.** §1.4 rule 3 ("`scm` 的编译期依赖不含 `review`") is enforced by
`ArchitectureRulesTest::scmCannotDependOnReview`, over production classes only. [I] So the event **type lives
`scm`**, published via `ApplicationEventPublisher`; `review` imports `scm.PullRequestChanged`, legal in the §1.3
direction (`… scm … ← review`). Putting it in `common` compiles too but adds an SCM-shaped type to a package §1.1
defines as "API error、paging、clock、纯安全工具".

**What "no `review` module yet" means for batch 2.** The event has **zero production listeners**, and Spring
tolerates that silently, so the publication is invisible unless a test listener is added. Since
`ArchitectureRulesTest` imports with `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`, a **test-scoped**
`@EventListener` under `src/test/java/com/forgepilot/scm/` cannot weaken the production rule — that is the seam
for proving the contract now. **T1** a test listener asserts `TransactionSynchronizationManager
.isActualTransactionActive()` and reads the uncommitted `pull_request` row (⇒ same transaction); **T2** a listener
that throws ⇒ the row is **absent** afterwards (⇒ 监听失败则事务回滚); **T3** the event's
`headSha`/`inputFingerprint` equal the committed row's. Batch 2 **cannot** prove idempotent PENDING creation,
after-commit scheduling or reconciliation — Phase 6, not to be stubbed in now.

---

## 6. `REQ-<n>` parsing

> [Q] D007: 同步 PR 时从分支名和标题解析第一个 `REQ-<n>`，失败不阻断入库；页面允许设置或清除。LEADER 始终可改；PR 作者在当前 head 尚无任何人工终局 Decision 时可改；其他不可改。

> [Q] D013.2: `<n>` 直接是 `requirement.id`，不新增项目内展示编号列。解析必须按 PR 所属项目过滤，外项目 id 解析不到即落入 P1 已规定的「未关联需求」。

P1 adds "解析失败不阻断入库"; §7 E2E requires `feat/REQ-<n>-*` to auto-link. Settled: `<n>` is the global `requirement.id`, no new column. Resolution is filtered by the PR's project — an id
belonging to project B on a PR in project A resolves to **no linked requirement**, exactly as if there were no
`REQ-` token; not an error, not a 4xx, never a block on ingestion.

That last rule is why the composite FK `(project_id, requirement_id) -> requirement(project_id, id)` **cannot** be
the filter: D013.11 forbids catching a constraint violation and continuing, so an FK rejection would abort the
whole insert and violate 失败不阻断入库. Filtering must happen **before** the insert — forcing a project-scoped read
of `requirement` from inside `scm`, while §1.3 grants `scm` only `common, project`. → **OQ-1**.

Testable: same-project id → linked; other project's id → `requirement_id IS NULL` + 2xx; nonexistent id → NULL;
no token → NULL; token in title only, or branch only → linked; two conflicting tokens → the first (**OQ-10**).

---

## 7. Author mapping

`author_external_user_id` and `author_username` are the **不可变作者快照** (historical facts; the username display
only). `author_user_id` is the **可重算映射** — derived, composite FK to `project_member`, column-level
`ON DELETE SET NULL`.

> [Q] D010: 成员的"本人 PR"权限使用项目级稳定外部用户 ID，禁止用户名比对；身份由 LEADER 通过 SCM API 配置。PR 保存不可变作者外部 ID/用户名快照和可重算的本地映射，成员退出后映射由列级 `ON DELETE SET NULL` 清空。

§2.3 explains the two different FK targets: 审计表的 `actor_id` 指向 `user_account`（退出项目不能抹掉既成事实），
而 `pull_request.author_user_id` 指向 `project_member`（成员退出后活权限必须失效）。P11: **禁止按用户名授权**。

**Why the external id is the key**: a GitHub login can be renamed and the old handle re-registered by someone
else; the numeric user id cannot. Batch 1 shipped the authorization side already —
`project_member.scm_external_user_id` with `UNIQUE(project_id, scm_external_user_id)` — and proved the negative by
grep (AC4: 无处读取 `scmUsername` 做判定). Batch 2 keeps that green **and** adds a behavioral test: a member whose
`scm_username` matches the PR author but whose `scm_external_user_id` differs must not map.

**The mapping** = the `project_member` in this project whose `scm_external_user_id` equals the PR's
`author_external_user_id`, else NULL. [I] `scm` may depend on `project` (§1.3), but ArchUnit rule 4 forbids
injecting `ProjectMemberRepository` across features, so `project` must expose a facade method —
`ProjectAccessService` today has only `requireMember`/`requireRole` (cf. D013.6's `auth.UserDirectory`).

**"可重算" has a gap** [I]: recomputation happens on PR sync, but if a LEADER configures a member's SCM identity
*after* the PRs exist, nothing in `scm` observes it (`project` may not depend on `scm`). The mapping stays NULL
until the next webhook, and "本人 PR" permissions silently do not work. → **OQ-11**.

**`ON DELETE SET NULL` vs. batch 1's rule**: §1.4.1 — batch 1 already names this FK as its single exception, so the
only obligation is to write exactly one such clause. `FOREIGN KEY (project_id, author_user_id) REFERENCES
project_member (project_id, user_id) ON DELETE SET NULL (author_user_id)`: nulling only that column keeps
`project_id` intact, which is why the whole-row form is unusable. `author_user_id` is therefore nullable and the
FK stays `MATCH SIMPLE` — a NULL skips the composite key entirely, as batch 1 measured for
`requirement.current_revision_id` (D013.10). **The clause is unreachable through the API today** (batch 1 ships no
member-removal endpoint), so test it with a direct `DELETE FROM project_member` in `DatabaseConstraintTest`, below
all application code, asserting `author_user_id IS NULL` while `project_id`, `author_external_user_id`,
`author_username` are untouched — also the only test proving §7.1's PG15 floor is real.

---

## 8. Testability without credentials

> [Q] `IMPLEMENTATION-PLAN.md` §下一步: **批次 2 的硬约束：CI 不得依赖任何 AI/SCM 凭据或仓库秘密。** …Webhook 与 PR 快照的测试一律打到进程内或本地的假服务端，不打真实 provider。真实凭据只在人工验证时使用，且不进仓库。

Verified: `.github/workflows/ci.yml` references no `secrets.*`; its only `env:` block (compose-smoke) sets
`FORGEPILOT_DB_PASSWORD: forgepilot-phase1-ci-only`.

### 8.1 Two independent seams, not one

1. **Signature verification needs no HTTP at all.** It is a pure function of `(raw bytes, secret, signature
   header)`. Unit tests build the bytes and compute the HMAC with `javax.crypto.Mac`: correct signature, one byte
   flipped, truncated hex, missing header, wrong algorithm prefix, empty body — plus a body whose *parsed* form is
   identical but whose bytes differ (reordered keys / whitespace), the case that proves the raw-byte rule is
   implemented rather than re-serialized.
2. **PR snapshot fetching needs an HTTP seam — and it already exists in the data model.** `api_base` is
   per-repository data in `scm_repository`. If the provider client takes its base URI from that column instead of
   hardcoding `https://api.github.com`, a test points `api_base` at `http://127.0.0.1:<port>` and needs **no
   production change and no credential** (`encrypted_token` can hold any string; the stub ignores it). The one
   constraint to lock in: **the GitHub client must never hardcode a host.**

### 8.2 What is on the classpath (checked, not assumed)

`backend/pom.xml` test scope: `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`,
`testcontainers-junit-jupiter`, `testcontainers-postgresql`, `archunit-junit5`. There is **no** WireMock,
MockWebServer, OkHttp or MockServer, and none is in `~/.m2/repository`. Options needing **no new dependency**:

| Option | Present because | Gives / lacks |
|---|---|---|
| `org.springframework.test.web.client.MockRestServiceServer` | `spring-boot-starter-test` 4.1.0 → `spring-test` 7.0.8; verified in `spring-test-7.0.8.jar`, including `…$RestClientMockRestServiceServerBuilder.class` | request expectations, canned responses, per-request assertions; no socket, fastest. Lacks real sockets, status line/redirects and any exercise of the SSRF policy |
| `com.sun.net.httpserver.HttpServer` (JDK) | `jdk.httpserver@21.0.11` in `eclipse-temurin:21-jdk`, verified with `java --list-modules` | a **real** loopback server: headers, statuses, `Link` pagination, 403 + `X-RateLimit-*`, connection resets, scripted per-call sequences |

The outbound side needs nothing new either: `spring-web` 7.0.8 ships `RestClient` +
`JdkClientHttpRequestFactory`. [I] Recommended split —
`MockRestServiceServer` bound to an injected `RestClient.Builder` for error mapping, pagination and parsing;
**one** `jdk.httpserver` test for the end-to-end path, since only a real socket exercises `OutboundUrlPolicy` and
the actual client stack. **Do not add a dependency**: `quality-guidelines.md` forbids new gates "without a
concrete failure they would have caught", and the plan requires an approved decision before any new dependency.

### 8.3 Consequences for the design

- Stub tests must assert the exact request line, or "it works against the stub" says nothing about github.com.
  Fingerprint determinism needs **no** server at all: a pure function over a canned manifest, with the expected
  digest pinned as a literal so a normalization change fails loudly.
- A2/A3 need a **scripted sequence** of snapshots for one PR — impossible against a real repository, an argument
  for the stub beyond credentials.
- **Settle first**: an `OutboundUrlPolicy` that blocks loopback and private ranges (it should) breaks every
  stub-server test. It needs an explicit, non-weakening test seam. → **OQ-9**.
- `database-guidelines.md` bans `@Disabled`/`assumeTrue`/"skip when Docker is missing". No "skip when no GitHub
  token" branch either — that is how the constraint quietly dies.

---

## 9. Open questions (listed, not resolved)

| # | Question (sources in tension in parentheses) |
|---|---|
| **OQ-1** *(blocking)* | How does `scm` resolve `REQ-<n>` inside the PR's project when §1.3 grants it only `common, project`? The FK cannot do it (D013.11 + 不阻断入库) and rule 4 forbids injecting `RequirementRepository`. Precedent: D013.6 narrowed §1.3 for `auth.UserDirectory`; `requirement ← scm` adds **no cycle**. (§1.2 vs §1.3 vs D013.2/11) |
| **OQ-2** *(blocking)* | Where do the changed-file manifest and patches live between PR sync and Review? JSONB column on `pull_request` / 17th table (barred) / nowhere, i.e. re-fetch at Review time so the fingerprint's inputs are not reproducible from the DB. (§1.2, §3.1, §4.2 vs §2.1) |
| **OQ-3** | Is `(provider, instance_identity, external_id)` globally unique, or may two projects register one repository? Sharing means one delivery has two targets and two secrets. (D010 vs §2.1) |
| **OQ-4** | Is "triple frozen once a PR exists" enforced by a DB trigger or by the Service? (§2.1 allows a trigger only for `finding`, vs `database-guidelines.md`) |
| **OQ-5** | What exactly is "normalized instance identity" — `github.com` vs `api.github.com`, ports, IDN, trailing slash; derived from `api_base` or stored? A frozen column: wrong ⇒ unfixable without a new project. (undefined everywhere) |
| **OQ-6** | Fingerprint canonicalization (§3.3's seven). A later change invalidates every stored fingerprint, hence every Review identity. (D003/§3.1 silent) |
| **OQ-7** | Status for an invalid signature (401/403/202-no-write); is a valid signature for an unknown repository distinguishable? Also: webhook path with `{projectId}` or payload-routed — the secret must be loadable **before** parsing. (§2.4 vs §3.1) |
| **OQ-8** | PR-update concurrency: row lock, or conditional `UPDATE … WHERE source_updated_at <= :new`? A3 is unprovable without a ruling. (§3.1 locks only the Decision path) |
| **OQ-9** | How do stub-server tests coexist with an SSRF policy that must block loopback and private ranges? (LEGACY matrix + the plan's SSRF rule vs §8) |
| **OQ-10** | Precedence when branch and title carry different `REQ-<n>`; case sensitivity of the prefix. (D007/P1) |
| **OQ-11** | Does re-syncing overwrite a **human-set** association by re-parsing (D007 against itself)? When is `author_user_id` recomputed if the SCM identity is configured after the PR exists? |
| **OQ-12** | Does the *initial* auto-association write an event row (`from=NULL`, `to=n`, `SYSTEM`) when §2.1 says the table records **仅** *changes*? Exact CHECK for "type 与 actor"? |
| **OQ-13** | Where does the key encrypting `encrypted_token/secret` come from, and how is it rotated? `SecretCipher` (LEGACY matrix, REWRITE) does not exist. |

---

## Caveats / Not found

- **Not found** (absences, not failed searches — I read `docs/v2/*.md` in full plus `.trellis/spec/backend/`): any
  instance-identity normalization function; fingerprint canonicalization rule; storage location for patches;
  webhook URL shape or status code; key management for `encrypted_token/secret`; GitHub API version or pagination
  policy.
- `docs/v2/adr/` is **empty**; all decisions live in `DECISIONS.md`. I did not run the build. §8.2's claims come
  from `backend/pom.xml`, unzipping `spring-test-7.0.8.jar` and `spring-web-7.0.8.jar` from `~/.m2`, the two
  starter POMs, and `java --list-modules` in `eclipse-temurin:21-jdk`.
- Everything marked `[I]` is mine and carries no authority.
