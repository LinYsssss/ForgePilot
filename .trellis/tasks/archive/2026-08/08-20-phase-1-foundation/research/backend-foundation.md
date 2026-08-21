# Phase 1 backend foundation research

## Scope of this note

This is a planning input for the Phase 1 Trellis task. It does not authorize implementation and does not replace `prd.md`, `design.md`, or `implement.md`. The repository is still at the planning gate and no backend application/build files exist at `HEAD` beyond `backend/README.md`.

The findings below deliberately separate:

- **Confirmed**: mandated by the current V2 authority or directly observed in the current repository/environment.
- **Recommended**: the smallest implementation shape that satisfies the confirmed contract without importing Legacy architecture.
- **Deferred**: a choice the Phase 1 design must freeze before implementation, or an issue owned by another Phase 1 slice.

## Confirmed facts and evidence

### Current repository state

- **Confirmed:** `backend/` currently contains only `backend/README.md`; there is no `pom.xml`, Gradle build, Java source, migration, Dockerfile, or active CI workflow. Evidence: `backend/README.md`; `git ls-tree -r --name-only HEAD`; current top-level tree.
- **Confirmed:** the backend is intended to be one Spring Boot modular monolith rooted at `com.forgepilot`. The only allowed feature-level packages are `common`, `auth`, `project`, `requirement`, `scm`, `knowledge`, `ai`, and `review`; the root application class is the natural exception. Evidence: `docs/v2/ARCHITECTURE.md` §1.1; `backend/README.md`; `AGENTS.md`.
- **Confirmed:** Phase 1 may create engineering foundation only. It must not create login/project/requirement/knowledge/SCM/review behavior, business entities, business tables, business endpoints, business migrations, or empty four-layer directory trees. Evidence: `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1; `backend/README.md`; `AGENTS.md` “Current execution gate”.
- **Confirmed:** current `.trellis/spec/backend/*.md` files are unfilled templates, not established ForgePilot conventions. They cannot be treated as authority yet. Evidence: `.trellis/spec/backend/index.md`, `directory-structure.md`, `database-guidelines.md`, `quality-guidelines.md`, `error-handling.md`, and `logging-guidelines.md`.
- **Confirmed:** root ignore/EOL rules already cover Maven `target/`, logs, local environment files, and LF for Java/YAML/SQL/shell files. Evidence: `.gitignore`; `.gitattributes`.

### Mandatory Phase 1 backend capabilities

- **Confirmed:** Phase 1 must establish Spring Boot, PostgreSQL 15+ with pgvector, Flyway, Testcontainers, ArchUnit, basic CI, repeatable clean-database startup, and a backend suitable for the 4 GB deployment measurement. Evidence: `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1 target products and exit conditions.
- **Confirmed:** PostgreSQL 15 is a hard minimum for later accepted schema contracts, and Testcontainers, Docker Compose, and deployment must use the same 15+ baseline. Evidence: `docs/v2/ARCHITECTURE.md` §7.1 and `docs/v2/PRD.md` §8.
- **Confirmed:** tests must exercise real PostgreSQL/pgvector; H2 cannot prove the required isolation, vector, and PostgreSQL constraint behavior. Evidence: `docs/v2/ARCHITECTURE.md` §7.1; `docs/v2/IMPLEMENTATION-PLAN.md` “测试与研究纪律”.
- **Confirmed:** Flyway naming is `V<n>__<snake_case>.sql`, with V1 as the initialization migration. Phase 1 creates only the foundation schema; business tables arrive with their vertical phases and are squashed before the first releasable baseline. Evidence: `docs/v2/ARCHITECTURE.md` §2.3; `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1.
- **Confirmed:** pgvector must be installed, but no vector table, fixed vector dimension, or HNSW index belongs in Phase 1. The accepted future model uses one dimensionless `vector` column and defers its index until the embedding profile is frozen in Phase 4. Evidence: `docs/v2/DECISIONS.md` D001; `docs/v2/ARCHITECTURE.md` §5; `docs/v2/LEGACY-MIGRATION-MATRIX.md` knowledge section.
- **Confirmed:** ArchUnit must enforce zero top-level-package cycles, absence of the nine forbidden top-level names (`agent`, `patch`, `mq`, `rag`, `repo`, `pullrequest`, `context`, `assistant`, `finding`), no `scm -> review` compile dependency, no direct cross-feature `*Repository` injection, and no controller access to a repository in another feature. Evidence: `docs/v2/ARCHITECTURE.md` §1.4. The Phase 1 exit gate explicitly calls out cycle freedom and `scm` not depending on `review`: `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1 exit conditions.
- **Confirmed:** full observability, Prometheus/Grafana/OTel, RabbitMQ/Kafka, Redis, Resilience4j Circuit Breaker, service discovery, an API gateway, a second AI runtime, and a sandbox are outside the V2 mainline. Evidence: `docs/v2/ARCHITECTURE.md` §7.1; `docs/v2/PRD.md` §4.

### Legacy/reference evidence: useful conventions, not a baseline to copy

The migration matrix classifies all 32 Legacy Flyway migrations as **DROP** and `.trellis/tasks/**` as **REFERENCE** only. Therefore the following history is evidence of local build experience, not authorization to restore RepoSage.

- **Confirmed historical convention:** the removed implementation used Maven, the Spring Boot Maven parent/plugin, `mvn ... verify`, Temurin in GitHub Actions, and a multi-stage Maven/JRE Dockerfile. Evidence: Git history before `6ecc8dc`, especially historical `backend/pom.xml`, `backend/Dockerfile`, and `.github/workflows/ci.yml`; commit `9fda31a` (“standardize backend quality baseline”).
- **Confirmed historical convention worth retaining in spirit:** production used Flyway with `spring.sql.init.mode=never` and JPA `ddl-auto=validate`; Testcontainers injected real container connection properties with `DynamicPropertySource`. Evidence: commits `12bec4f` and `065cda9`; historical `application-prod.yml` and `IntegrationTestContainers.java`.
- **Confirmed historical convention requiring fresh judgment:** `backend/.mvn/settings.xml` forced HTTPS Maven Central to override a known host-global mirror. This may be useful if the same environment issue reappears, but it is not a current V2 requirement. Evidence: historical `backend/.mvn/settings.xml`.
- **Confirmed Legacy patterns that must not return:** H2 development mode, `ddl-auto=update`, Flyway disabled in development, `baseline-on-migrate=true`, the old V1/V2 migration history, a separate vector table/runtime DDL, RabbitMQ/Testcontainers RabbitMQ, and `disabledWithoutDocker=true` tests. Evidence: historical `application-dev.yml`, `application-prod.yml`, `V1__baseline_schema.sql`, `IntegrationTestContainers.java`; `docs/v2/LEGACY-MIGRATION-MATRIX.md` lines covering old Flyway history, `PgVectorIndexService`, MQ, and runtime DDL.
- **Confirmed:** there was no ArchUnit dependency/test in the inspected Legacy backend, so Phase 1 needs a new rule set rather than a migration. Evidence: historical `backend/pom.xml` and `backend/src/test`; repository-history search for `ArchUnit` returned no backend match.

### Toolchain/version observations

- **Confirmed environment observation (2026-08-20):** this host has Docker 29.6.1, Docker Compose 5.3.1, and Python 3.10.12, but no system `java` or `mvn`. Consequently a Maven verification cannot run locally until Java is installed or a JDK builder container is used. Evidence: `java -version`, `mvn -version`, `docker version`, `docker compose version`, and `python3 --version` probes.
- **External planning evidence supplied by the orchestrator (2026-08-20):** Spring Initializr currently offers Spring Boot `4.1.0.RELEASE` with Java 21; Maven Central current stable candidates include ArchUnit `1.5.0` and Testcontainers PostgreSQL `1.21.4`. These are candidate versions, not repository decisions.
- **Confirmed:** the old repository used Java 17/Spring Boot 3.5.x/Testcontainers 1.20.4. Those numbers belong to Legacy and are not binding on this greenfield baseline. Evidence: historical `backend/pom.xml`; `docs/v2/LEGACY-MIGRATION-MATRIX.md` greenfield rule.

## Recommended minimal backend shape

### Build and runtime choice

Use one Maven module under `backend/`, with the Maven Wrapper committed and CI invoking `./mvnw`. Maven is the lowest-risk choice because it matches the repository's proven build history while avoiding any source-code reuse.

Recommended version policy:

- Java 21 LTS.
- The current stable Spring Boot release that is verified compatible with Java 21 on implementation day; `4.1.0.RELEASE` is the current candidate, not a frozen fact.
- Stable, non-snapshot dependencies, managed by the Spring Boot BOM wherever possible.
- An explicit ArchUnit stable version if the Boot BOM does not manage it; `1.5.0` is the current candidate.
- Testcontainers managed by Boot where available, otherwise a single pinned BOM/version; `1.21.4` is the current candidate.
- One exact pgvector/PostgreSQL image tag (and preferably digest for deployment reproducibility) shared by Testcontainers and Compose. PostgreSQL 16 is a reasonable 15+ candidate because the historical project already exercised `pgvector/pgvector:pg16`, but the exact current tag/digest remains a design decision.

Do not introduce Gradle alongside Maven, snapshots, floating `latest` images, or separate dependency versions for local and CI builds.

### Minimal Maven dependency set

Recommended runtime dependencies:

- `spring-boot-starter-web` — accepted Spring MVC/API runtime and health-serving process.
- `spring-boot-starter-data-jpa` — accepted JPA/PostgreSQL persistence foundation; no entities are added in Phase 1.
- `spring-boot-starter-actuator` — health plus the JVM memory metrics needed by the Phase 1 capacity evidence; no Prometheus registry or full observability stack.
- `org.postgresql:postgresql` at runtime.
- `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql`.

Recommended test dependencies:

- `spring-boot-starter-test`.
- Testcontainers JUnit Jupiter and PostgreSQL modules.
- `com.tngtech.archunit:archunit-junit5`.

Defer until the owning business phase:

- Spring Security (`auth` begins in Phase 2); adding it now would create generated credentials/default access behavior without a Phase 1 user outcome.
- Validation until an input contract exists.
- pgvector Java/JPA mapping until `knowledge` needs an embedding column.
- AI/HTTP clients, retry libraries, SCM SDKs, JSON-schema helpers, and all business-specific dependencies.
- Lombok, MapStruct, Spring Modulith, JaCoCo coverage gates, Checkstyle/SpotBugs, and other quality plugins unless the Phase 1 design identifies a concrete requirement. Legacy had JaCoCo reporting, but current V2 documents do not require a coverage threshold for an almost-empty skeleton.

### Minimal application/configuration behavior

- One root bootstrap class: `com.forgepilot.ForgePilotApplication`.
- No controller, entity, repository, service, event, scheduler, or seed-data class in Phase 1.
- A single environment-driven `application.yml` is sufficient initially. It should set the application name, PostgreSQL datasource, `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`, Flyway enabled, and `spring.sql.init.mode=never`. Do not create an H2/default-DDL profile.
- Actuator should expose only what Phase 1 needs. `health`, `info`, and local/internal `metrics` are enough for startup and memory measurement. Do not add a Prometheus registry. Compose should bind the backend to loopback during the capacity baseline so unauthenticated metrics are not exposed externally before Phase 2 security exists.
- Database credentials must come from environment/Compose. A tracked `.env.example` may contain clearly local, non-secret sample values; real `.env` remains ignored.

### Minimal Flyway migration

Recommended `V1__init.sql` content is only the foundation fact that Phase 1 can justify:

```sql
create extension if not exists vector;
```

No business table, vector shadow table, seed row, HNSW index, trigger, or compatibility DDL should be present. Flyway's own history table is expected. A Testcontainers test should assert that there are no other application base tables in `public`.

Keep `baseline-on-migrate` disabled: this is a greenfield database, and accepting a non-empty unmanaged schema would hide deployment mistakes. Later vertical phases should add their own migrations and follow the already-approved pre-release squash plan; they must not import or renumber the Legacy history.

### ArchUnit foundation

Add a single focused architecture test suite that imports production classes below `com.forgepilot..` and codifies all five §1.4 rules now, so later phases cannot silently drift.

The Phase 1 design must explicitly resolve one non-vacuity issue: with only a root bootstrap class and no business feature classes, cycle and `scm -> review` rules naturally have no feature classes to inspect. Preferred options, in order:

1. Add package-level `package-info.java` markers for the eight authorized feature packages if a quick compile probe proves ArchUnit imports them; these are boundary documentation, not empty four-layer scaffolding.
2. Otherwise keep rules future-facing with an explicit empty-selection allowance and document that Phase 1 proves rule installation plus absence of illegal names, while each vertical phase makes the relevant dependency rule non-vacuous.

Do not create fake service/repository/controller marker classes merely to make ArchUnit green, and do not add Spring Modulith solely for package discovery.

The rule for allowed packages must not accidentally permit a new `config` or `support` top-level production package. Test utilities should remain under the root test package or an allowed feature's test subtree and should not weaken production imports.

### Testcontainers foundation test

One Spring integration test can cover all database foundation behavior against the exact pgvector image used by Compose:

- application context starts from a newly created database;
- Flyway V1 succeeds and its history row is successful;
- `server_version_num >= 150000`;
- extension `vector` exists and a trivial vector cast/operator query works;
- no application base table exists apart from `flyway_schema_history`;
- JPA is configured for validation, not schema generation.

Use dynamic datasource properties or the current Spring Boot Testcontainers service-connection mechanism, but choose one. Do not mark this test `disabledWithoutDocker`; CI must fail if its required PostgreSQL/pgvector verification cannot run. A clear local prerequisite message is preferable to a false-green skip.

### Basic CI

The backend portion of `.github/workflows/ci.yml` should be one small required job:

- checkout with `contents: read` permissions;
- set up Temurin Java 21 with Maven cache;
- set `working-directory: backend` and run `./mvnw -B -ntp verify`;
- rely on the runner Docker daemon for the mandatory Testcontainers test;
- use a finite job timeout.

Do not restore the Legacy sandbox, supply-chain/image-scan matrix, RabbitMQ service, H2 fallback, or tests that skip when Docker is absent. Frontend/evaluation jobs and the final combined workflow are shared integration work and should be owned by the orchestrator or a dedicated non-overlapping slice.

## Likely file boundaries

### Backend-owned files

```text
backend/
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw
├── mvnw.cmd
├── pom.xml
├── Dockerfile                              # needed for deploy/capacity integration
├── README.md                              # exact local/test/container commands
└── src/
    ├── main/
    │   ├── java/com/forgepilot/ForgePilotApplication.java
    │   ├── java/com/forgepilot/{common,auth,project,requirement,scm,knowledge,ai,review}/package-info.java
    │   │                                      # only if the non-vacuity probe supports this option
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__init.sql
    └── test/java/com/forgepilot/
        ├── ArchitectureTest.java
        └── DatabaseFoundationIntegrationTest.java
```

The exact Maven Wrapper support files depend on the wrapper version generated; commit the official generated set rather than hand-writing it.

### Shared integration files (single owner to avoid overlapping edits)

```text
.github/workflows/ci.yml
deploy/compose.yml or compose.yml             # exact location is a Phase 1 design choice
.env.example                                  # if the integrated Compose workflow uses it
.trellis/spec/backend/{index,directory-structure,database-guidelines,quality-guidelines}.md
```

`error-handling.md` should remain untouched until an API error pattern exists. `logging-guidelines.md` should only be filled if Phase 1 establishes a real logging/trace convention. Specs must record the conventions actually implemented, not aspirational rules.

## Targeted validation commands

Exact class names/Compose paths may change in the approved design, but the validation intent should remain.

### Fast backend checks

```bash
cd backend
./mvnw -v
./mvnw -B -ntp -Dtest=ArchitectureTest test
./mvnw -B -ntp -Dtest=DatabaseFoundationIntegrationTest test
./mvnw -B -ntp verify
```

Expected: Java/Maven versions are pinned as planned; ArchUnit passes; the pgvector Testcontainer starts; clean-database/Flyway/version/extension/no-business-table assertions pass; the boot jar is built.

### Image and clean-stack smoke check

```bash
docker build -t forgepilot-backend:phase1 backend
docker compose -p forgepilot-phase1-smoke -f <approved-compose-path> up -d --build --wait
curl -fsS http://127.0.0.1:8080/actuator/health
docker compose -p forgepilot-phase1-smoke -f <approved-compose-path> exec -T postgres \
  psql -U forgepilot -d forgepilot -Atc "show server_version_num; select extversion from pg_extension where extname='vector';"
docker compose -p forgepilot-phase1-smoke -f <approved-compose-path> down -v
```

Use the unique Compose project name shown above so cleanup targets only this smoke stack. Before `down -v`, verify `docker compose ... ps` resolves the expected Phase 1 containers. The final plan should substitute the approved file path, service names, and non-secret local credentials.

### Capacity evidence hooks for the backend slice

```bash
curl -fsS http://127.0.0.1:8080/actuator/metrics/jvm.memory.used
curl -fsS http://127.0.0.1:8080/actuator/metrics/jvm.buffer.memory.used
docker stats --no-stream <backend-container> <postgres-container>
docker inspect <backend-container> <postgres-container>
```

These are supporting measurements only. The integrated Phase 1 capacity owner must run the user-approved 5-minute baseline + 2-minute warm-up + 4-minute steady observation with the frontend static server and existing resident services, record RSS/PSS, Postgres settings, available memory, swap/OOM evidence, and prove at least 1 GB remains available. The shortened window supports only a short-term empty-stack claim.

### Repository-level finish checks

```bash
git diff --check
git status --short
git diff --stat
```

Also inspect the actual diff to confirm there are no business entities/tables/endpoints, forbidden top-level packages, H2/RabbitMQ/Agent dependencies, Legacy migrations, or unowned changes in frontend/evaluation files.

## Risks and required design decisions

1. **Version freeze:** Java 21, Spring Boot 4.1.0, ArchUnit 1.5.0, and Testcontainers 1.21.4 are recommendations/current candidates. The approved `design.md` must record exact stable versions and compatibility evidence before code generation.
2. **Local toolchain gap:** the host currently cannot run Maven because Java/Maven are absent. The implementation plan needs a deliberate bootstrap step: install/use Java 21 plus the Maven Wrapper, or define a reproducible JDK builder-container command. Docker availability alone is not the same as a completed Java build validation.
3. **ArchUnit non-vacuity:** do not claim package cycles or `scm -> review` are meaningfully exercised while no feature class exists. Resolve the package-marker/empty-rule approach explicitly and require each later feature phase to make its rules non-vacuous.
4. **pgvector image drift:** tests and deployment can disagree even when both say “PostgreSQL 15+”. Freeze one exact pgvector image reference and assert the server version plus extension at runtime.
5. **extension privilege:** `CREATE EXTENSION vector` requires suitable database privileges. The Compose/Testcontainers owner can provide them, but the deployment design must document who runs Flyway and avoid silently depending on an over-privileged long-lived application role.
6. **Flyway false compatibility:** `baseline-on-migrate`, old migration copying, or H2/`ddl-auto=update` would hide an invalid greenfield database. Keep Flyway authoritative and fail closed on a non-empty unmanaged schema.
7. **V1 lifecycle:** Phase 1's extension-only V1 is intentionally incomplete as a product schema. Later phases must follow the approved additive-migration then pre-release-squash process rather than pre-creating all 16 business tables now.
8. **Actuator exposure:** metrics are useful for the mandated memory evidence but unauthenticated endpoints must stay loopback/internal until Phase 2 establishes security.
9. **CI false green:** a Docker-conditional skip would allow the most important database contract test to disappear. Required CI must fail when Testcontainers cannot execute.
10. **Shared-file conflicts:** CI, Compose, `.env.example`, and Trellis specs affect multiple Phase 1 slices. Give each shared file one integration owner; backend/frontend/evaluation agents should not edit the same workflow or Compose file concurrently.
11. **Scope creep through “foundation”:** do not add security configuration, generic error frameworks, logging frameworks, retries, repositories, seed users, CRUD APIs, or package-layer scaffolding merely because later phases will need them. Add only the boot/database/architecture/test/deployment hooks proven by Phase 1 acceptance criteria.

## Planning conclusion

The minimal credible backend Phase 1 deliverable is a Maven-wrapped Java 21 Spring Boot process with no business behavior, an extension-only Flyway V1 against one pinned PostgreSQL 15+/pgvector image, one mandatory clean-database Testcontainers integration test, one future-facing ArchUnit suite, a small backend CI job, and a container image/health/metrics surface usable by the integrated Compose and 4 GB capacity checks.

This scope stays inside the accepted product/architecture boundary. It reuses only mature build/testing ideas from repository history and explicitly rejects Legacy schema, H2, MQ, runtime DDL, and business scaffolding.
