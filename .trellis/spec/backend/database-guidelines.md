# Database Guidelines

PostgreSQL is reached through Flyway-managed schema and JPA. The data model,
its constraints, project isolation, and the table/column naming rules are
defined once in [ARCHITECTURE.md](../../../docs/v2/ARCHITECTURE.md) §2 and
§7.1. This guide records how the repository enforces schema authority and how
database behavior is tested.

## Flyway owns the schema

`backend/src/main/resources/application.yml` fixes the following, and they are
not negotiable per environment:

| Property | Value | Why it stays |
|---|---|---|
| `spring.flyway.enabled` | `true` | Migrations are the only schema source. |
| `spring.flyway.baseline-on-migrate` | `false` | An unmanaged non-empty schema must fail, not be adopted. |
| `spring.flyway.clean-disabled` | `true` | No code path may drop the schema. |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate verifies, never generates. |
| `spring.sql.init.mode` | `never` | No script-based side channel into the schema. |

Forbidden: `ddl-auto: update`/`create`/`create-drop`, enabling `clean`,
enabling `sql.init`, baselining over an existing schema, and any runtime DDL
executed from application code. `spring.jpa.open-in-view` is `false`; load what
a transaction needs explicitly rather than relying on an open session in the
web layer.

## Migrations

- Migration files live in `backend/src/main/resources/db/migration` and follow
  the naming rule in `ARCHITECTURE.md` §2.4.
- `V1__foundation.sql` contains only `CREATE EXTENSION IF NOT EXISTS vector`.
  Batches 1–3 append `V2__auth_project.sql`, `V3__requirement.sql`,
  `V4__knowledge_ai.sql`, `V5__scm.sql`, and `V6__review.sql`, producing the
  Phase 0–8 baseline of sixteen business tables. `V7__pull_request_title.sql`
  is a column-only migration that persists the PR title frozen into Review
  context. D020 appends `V8__member_roles_and_scm_identities.sql`, which adds
  the three irreducible role/identity/binding facts and brings the current
  schema to nineteen business tables.
- **No migration carries seed rows.** The first account is created through
  `POST /api/auth/register`, so no password ever lives in the repository.
- Flyway derives the history `description` from the file name, and
  `FoundationDatabaseTest` asserts all eight successful entries plus the exact
  nineteen-table set in `public`. Renaming an applied migration breaks both that
  test and every existing database.
- Migrations are append-only. Never edit or renumber an applied migration; add
  the next version instead.
- The only `ON DELETE` clause is `pull_request.author_user_id ON DELETE SET
  NULL`, exactly as `ARCHITECTURE.md` §2.3 defines. Everywhere else a hard
  delete is refused by the foreign key until a decision defines its semantics.
- The role that runs Flyway must be allowed to create the `vector` extension.
  The Testcontainers database and the Compose database both run as the image's
  bootstrap superuser; a deployment with a restricted application role has to
  solve extension creation before startup, not by weakening the migration.
- `project_member` is now only the membership fact. Roles live in
  `project_member_role`; user-owned Provider identities live in `scm_identity`;
  project selection and approval history live in `project_member_scm_binding`.
  Do not add role arrays/JSON or identity columns back to `project_member`.

## Entities over composite foreign keys

Every project-scoped foreign key carries `project_id`, so almost every
association repeats a column that is already mapped. Hibernate 7 **refuses to
start** on the natural spelling, with `AnnotationException: ... mix insertable
with 'insertable=false'` or `MappingException: Column 'project_id' is
duplicated in mapping`.

The project-wide answer is [D013.1](../../../docs/v2/DECISIONS.md#d013)
variant A, and it is not optional: **write through a scalar `Long xxxId`, and
mark every `@JoinColumn` of the association `insertable = false, updatable =
false`.** `Requirement.currentRevision` is the worked example — a three-column
self-referencing key that proves same project, correct parent and existence in
one constraint.

Two consequences follow from the same decision:

- `requirement.current_revision_id` stays nullable and its foreign key stays
  `MATCH SIMPLE` and `NOT DEFERRABLE`. Creating a requirement is therefore a
  three-step write in one transaction: insert the requirement with a null
  pointer, insert revision 1, then backfill. Hibernate's flush order (inserts
  before updates) produces exactly that; do not "tighten" the key to
  `MATCH FULL`, which makes the design impossible.
- A partial unique index (`... WHERE role = 'LEADER'`) cannot be deferred, and a
  single-statement `UPDATE ... CASE` swap succeeds or fails depending on
  physical scan order. Transferring the LEADER role is always **demote →
  `flush()` → promote** inside one transaction, with the `project` row locked
  first so concurrent transfers serialise and the loser re-reads its own role.

## Vectors

`knowledge_chunk.embedding` is `vector` with **no dimension**. D001 makes the
model's dimension deployment configuration, so binding it in the schema would
let one Flyway version produce different structures in different environments.

Three consequences, all measured rather than assumed:

- **No vector index can exist on that column.** `ivfflat` and `hnsw` both fail
  with `22023: column does not have dimensions`. The route to an index is an
  expression index, and it arrives with the phase that freezes an embedding
  profile — not before.
- **Until an index exists, the database does not check dimensions at all.** A
  column-level `CHECK (embedding IS NULL OR dimension = vector_dims(embedding))`
  proves a single row is self-consistent and nothing more; it cannot see the
  other rows. One row embedded at a different dimension makes **every** TopK
  query in that project fail with `22000`. So the application-side check — does
  this vector match the dimension this project already uses? — is the entire
  defence, not a nicety. It lives in `ChunkSearchRepository.writeEmbedding`.
- **The column is deliberately not mapped by the entity.** Of the four spellings
  that were tried, `String` fails at startup, `float[]` without a type code fails
  at startup, `@JdbcTypeCode(SqlTypes.VECTOR) float[]` works but needs the
  `hibernate-vector` dependency, and `String` + `columnDefinition = "vector"`
  is the trap: `ddl-auto: validate` passes and **every write then fails at
  runtime** with `42804`. Never use that one. `validate` only inspects columns an
  entity maps, so leaving it unmapped is safe at startup, and retrieval needs
  native SQL either way.

`::vector` and the distance operators appear in exactly one class,
`ChunkSearchRepository`. Cosine (`<=>`) is the chosen operator; when an index is
finally added it must use `vector_cosine_ops` **and** the query's left-hand side
must match the index expression exactly, or the index is dead weight.

`project_id` is a hard filter in retrieval, and measurement confirms it is
evaluated before the ordering expression — so another project's rows can neither
appear in nor poison a result.

## Database tests

Database behavior is proven against a real PostgreSQL with pgvector, never a
substitute:

- `PostgresTestBase` owns one `pgvector/pgvector:0.8.6-pg15-bookworm` container
  for the whole test run, declared with
  `DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres")` because
  the image is not the official `postgres` name. Database tests extend it
  instead of declaring their own container.
- Container coordinates are injected with `@DynamicPropertySource` into
  `spring.datasource.url/username/password`. Use the same mechanism for new
  database tests instead of adding a second wiring style.
- `FoundationDatabaseTest` asserts the runtime facts, not just that the context
  started: `server_version_num >= 150000`, the `vector` extension is installed,
  a `<->` distance query returns the expected value, the whole Flyway history is
  successful, and `public` contains exactly the migrated tables.
- `DatabaseConstraintTest` asserts that each constraint actually rejects, by
  writing through `JdbcTemplate` **below** any application code and naming the
  SQLState it expects (`23505`, `23503`, `23514`). A constraint that is only
  ever enforced by a service is not enforced.

Forbidden in database tests: H2 or any in-memory replacement, a "skip when
Docker is missing" branch, `@Disabled`, and `assumeTrue` guards. A database
test that cannot run must fail the build; a false green here hides the
constraint behavior the model depends on.

## Review fencing and immutable input

`review` freezes its PR, requirement, acceptance-criteria and changed-file
context when the row is created. Database triggers reject later changes to its
identity columns and reject project/context disagreement. Finding writes carry
the current attempt and are protected by the composite foreign key; a worker
whose lease was replaced can neither finish, fail, renew nor append findings.
Re-claim deletes findings from the abandoned attempt in the same transaction,
so a crashed worker cannot pin the Review permanently. These paths are tested
through direct JDBC and concurrent workers in the `review` test package.

## Image pinning

`compose.yaml` pins the same pgvector image by tag **and** digest, and
`scripts/phase1-compose-smoke.sh` rejects any other image string. The
Testcontainers test pins the same tag. When the image is upgraded, change the
tag in the test, the tag and digest in `compose.yaml`, and the expected value
in the smoke script in one change, then re-run both.

## Common mistakes to avoid

- Adding a table "because a later phase will need it". Tables arrive with the
  phase that uses them.
- Introducing an entity without the migration that creates its table:
  `ddl-auto: validate` will fail at startup, which is the intended outcome.
- Querying by a bare id and checking project scope afterwards. Read paths carry
  `projectId`; see `ARCHITECTURE.md` §2.3.
