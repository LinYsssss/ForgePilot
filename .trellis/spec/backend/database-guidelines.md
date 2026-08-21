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
- `V1__foundation.sql` is the only migration and contains only
  `CREATE EXTENSION IF NOT EXISTS vector`. It deliberately contains no business
  table, index, trigger, or seed row; each vertical phase adds its own tables
  when that phase is authorized.
- Flyway derives the history `description` from the file name, and
  `FoundationDatabaseTest` asserts version `1` with description `foundation`
  and `success = true`. Renaming an applied migration breaks both that test and
  every existing database.
- Migrations are append-only. Never edit or renumber an applied migration; add
  the next version instead.
- The role that runs Flyway must be allowed to create the `vector` extension.
  The Testcontainers database and the Compose database both run as the image's
  bootstrap superuser; a deployment with a restricted application role has to
  solve extension creation before startup, not by weakening the migration.

## Database tests

Database behavior is proven against a real PostgreSQL with pgvector, never a
substitute:

- `FoundationDatabaseTest` starts
  `pgvector/pgvector:0.8.6-pg15-bookworm` through Testcontainers, declared with
  `DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres")` because
  the image is not the official `postgres` name.
- Container coordinates are injected with `@DynamicPropertySource` into
  `spring.datasource.url/username/password`. Use the same mechanism for new
  database tests instead of adding a second wiring style.
- The test asserts the runtime facts, not just that the context started:
  `server_version_num >= 150000`, the `vector` extension is installed, a
  `<->` distance query returns the expected value, the Flyway history row is
  successful, and `public` contains no base table other than
  `flyway_schema_history`.

Forbidden in database tests: H2 or any in-memory replacement, a "skip when
Docker is missing" branch, `@Disabled`, and `assumeTrue` guards. A database
test that cannot run must fail the build; a false green here hides the
constraint behavior the model depends on.

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
