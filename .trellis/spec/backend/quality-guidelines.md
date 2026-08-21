# Quality Guidelines

Backend quality is enforced by one build command, one architecture suite, one
real-database test, and a small set of configuration invariants. Every rule
below names the file or command that enforces it. Rules with no enforcement
anchor do not belong in this file.

## Build entry point

- `./mvnw` is the only build entry point. Do not rely on a globally installed
  `mvn`, and do not add a second build tool. `mvnw.cmd` deliberately refuses
  and points back to `./mvnw` or the documented container.
- `.mvn/wrapper/maven-wrapper.properties` is the single source of truth for the
  Maven distribution URL and its `distributionSha256Sum`. `mvnw` reads both
  from that file, verifies the downloaded archive with `sha256sum`/`shasum`,
  and on mismatch prints `checksum mismatch`, deletes the archive, and exits
  `1`. Never bypass the checksum, hardcode the URL in the script, or point at a
  floating version.
- On a host without a JDK, run the same repository wrapper inside
  `eclipse-temurin:21-jdk`; the exact command is in
  [backend/README.md](../../../backend/README.md). Installing a different local
  toolchain is not an equivalent verification.
- Dependencies are managed by the Spring Boot parent where possible and pinned
  explicitly otherwise (`pom.xml` `archunit.version`). No snapshot, RC, or
  floating version.

## Architecture rules

`docs/v2/ARCHITECTURE.md` §1.4 defines the five rules;
`backend/src/test/java/com/forgepilot/ArchitectureRulesTest.java` is where they
are enforced:

| Test method | Blocks |
|---|---|
| `topLevelPackagesAreExplicitlyAllowlisted` | Any production class outside the eight allowed packages, including the nine forbidden names. |
| `featureSlicesAreFreeOfCycles` | Cyclic dependencies between top-level packages. |
| `scmCannotDependOnReview` | A compile-time `scm -> review` dependency. |
| `crossFeatureRepositoriesAreNotInjectedDirectly` | Any class depending on another feature's `*Repository`. |
| `controllersCannotReachCrossFeatureRepositories` | A `*Controller` reaching another feature's `*Repository`. |

`forbiddenAndCrossFeatureRulesAreNotTautologies` proves four of these rules
actually fail by checking them against the deliberately illegal test fixtures.
The cycle rule has no counter-probe, and with only the bootstrap class and
eight `package-info` markers in production it currently has nothing to reject.
Do not describe it as proven. The phase that first creates cross-package
production code is responsible for making it non-vacuous.

When adding a rule, add its counter-probe in the same change. A rule that
passes because nothing is selected is not enforcement.

## Test contract

- `./mvnw -B -ntp verify` is the gate, and CI runs exactly that
  (`.github/workflows/ci.yml`, backend job).
- The `Dockerfile` build stage runs `package -DskipTests`. A successful image
  build is therefore not test evidence; only `verify` is.
- The real-PostgreSQL requirements and the H2/skip prohibitions are in
  [database-guidelines.md](./database-guidelines.md).
- Do not add coverage thresholds, style plugins, or extra static-analysis
  gates without a concrete failure they would have caught.

### Outbound calls are stubbed, never credentialed

CI holds no AI provider key, no SCM token and no repository secret, and
`.github/workflows/ci.yml` references no `secrets.*`. That is a property to
preserve, not an accident: a test suite that needs a credential cannot run for a
contributor who lacks one, and a credential in CI is a credential that can leak.

Two stubs are already available and **no HTTP-mocking dependency may be added**:

| Use | What |
|---|---|
| Timeouts, retry counts, malformed responses, real status lines | `com.sun.net.httpserver.HttpServer` — in the JDK (`jdk.httpserver`), a real loopback socket |
| Request shape: path, headers, JSON body | `org.springframework.test.web.client.MockRestServiceServer` — already transitive via `spring-boot-starter-test` |

Bind port 0 and inject the address with `@DynamicPropertySource`, the mechanism
`PostgresTestBase` already uses, so nothing collides in CI. WireMock and
MockWebServer are absent on purpose: the rule above requires a new gate to name
the real failure it would have caught, and the JDK server covers every case.

**This only works while no client hardcodes a host.** The provider base URI comes
from configuration, and the SCM base URI from `scm_repository.api_base`. That is
the same property D010 needs for self-hosted instances, so production
requirement and test seam are one thing — hardcode a host and both break at once.

A stub on `127.0.0.1` and an SSRF policy that blocks loopback are in direct
conflict, and the wrong resolution is to disable the policy under test, which
ships a protection that never executed. The policy stays on, denies by default,
and reads an explicit allowlist that is empty in production; tests add the one
host they need, and a separate test with an empty allowlist pins the denials.

## Configuration and secrets

- Credentials come from the environment only. In `application.yml`,
  `spring.datasource.password` is `${FORGEPILOT_DB_PASSWORD}` with **no**
  fallback default, unlike `url`, `username`, and pool size, which have
  explicit local defaults. Do not add a default for convenience.
- Observed behavior when the variable is absent: the application does not
  start. Spring passes the unresolved `${...}` expression through as a literal,
  so the failure surfaces during Flyway/datasource initialization as
  `FATAL: password authentication failed`, not as a missing-property error.
  Treat that message at startup as a missing environment variable first.
- `.env.example` contains example values only and says so; a real `.env`
  stays untracked. Never commit a working credential or print one in logs, CI
  output, or captured evidence.
- Actuator exposure is `health` only by default (`application.yml`).
  `metrics` is added exclusively by `application-capacity.yml`, and the
  `capacity` profile is set only by the capacity measurement runner (Phase 1:
  `.trellis/tasks/08-20-phase-1-foundation/evidence/capacity/run-capacity.sh`).
  `scripts/phase1-compose-smoke.sh` clears the profile and fails if
  `/actuator/metrics` returns anything other than `404`. Widening exposure or
  adding a metrics registry is not allowed while there is no authentication.

## Runtime memory envelope

`backend/Dockerfile`, `compose.yaml`, and `.env.example` carry one identical
JVM option string:

```text
-Xms128m -Xmx384m -XX:MaxDirectMemorySize=128m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:NativeMemoryTracking=summary
```

No automated check keeps the three copies in sync, so verify them with the
command below when touching any of the three files. The values are the measured
capacity envelope, not a preference: changing any of them, or any Compose
`mem_limit`, invalidates the recorded capacity result and requires a complete
re-run of the approved capacity protocol before the change can be accepted.

## Check commands

Run from the repository root. Expected results are stated per command.

```bash
cd backend && ./mvnw -B -ntp verify   # ArchUnit + real-database test must pass

# Exactly the eight allowed top-level production packages.
find backend/src/main/java/com/forgepilot -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort

# No forbidden production package. Expect no match.
grep -rEn '^package com\.forgepilot\.(agent|patch|mq|rag|repo|pullrequest|context|assistant|finding)\b' backend/src/main/java

# No unauthorized business types. Expect no match until the owning phase.
grep -rEn 'class .*Controller|class .*Service|@Entity|@Table' backend/src/main/java

# No table DDL in migrations outside an authorized phase. Expect no match.
grep -rEni 'create[[:space:]]+table|alter[[:space:]]+table' backend/src/main/resources/db/migration

# No in-memory database or conditional test skip. Expect no match.
grep -rniE 'h2|disabledWithoutDocker|DisabledIf|assumeTrue|@Disabled' backend/pom.xml backend/src

# The datasource password must have no fallback default.
grep -n 'FORGEPILOT_DB_PASSWORD' backend/src/main/resources/application.yml

# The JVM envelope must resolve to exactly one unique line.
grep -ho -- '-Xms128m[^"}]*NativeMemoryTracking=summary' backend/Dockerfile compose.yaml .env.example | sort -u

git diff --check
```

## Code review checklist

- [ ] The change is inside the currently authorized phase, and no class, table,
      endpoint, or dependency was added "for a later phase".
- [ ] `./mvnw -B -ntp verify` output is available as evidence, not just a claim
      that it passed.
- [ ] New architecture rules ship with counter-probes; new database tests use
      the real pgvector image.
- [ ] No new fallback credential, widened Actuator exposure, or forbidden
      infrastructure dependency (see `ARCHITECTURE.md` §7.1 for the components
      that are explicitly not introduced).
- [ ] If the JVM envelope, Compose memory limits, or the pinned images changed,
      the required re-run was performed and recorded.
- [ ] Any convention the change establishes is written into the matching guide
      in `.trellis/spec/backend/` with a file or command anchor.
