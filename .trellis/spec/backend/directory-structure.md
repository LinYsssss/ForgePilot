# Directory Structure

The backend is a single Maven module. The tree expresses the boot process, the
authorized package boundaries, and the deployment/verification entry points; it
does not pre-create feature scaffolding.

## Directory layout

```text
backend/
├── README.md                                # local and containerized commands
├── pom.xml                                  # the only build descriptor
├── mvnw                                     # POSIX build entry point
├── mvnw.cmd                                 # refuses and points back to ./mvnw
├── Dockerfile                               # multi-stage build + runtime image
├── .mvn/wrapper/maven-wrapper.properties    # pinned Maven URL + sha256
└── src/
    ├── main/
    │   ├── java/com/forgepilot/
    │   │   ├── ForgePilotApplication.java    # bootstrap class
    │   │   └── {ai,auth,common,knowledge,project,requirement,review,scm}/
    │   │       ├── package-info.java         # boundary documentation
    │   │       └── feature classes           # flat package-owned implementation
    │   └── resources/
    │       ├── application.yml               # default configuration
    │       ├── application-capacity.yml      # measurement profile only
    │       └── db/migration/V1__foundation.sql
    └── test/java/com/forgepilot/
        ├── ArchitectureRulesTest.java        # architecture rules + counter-probes
        ├── FoundationDatabaseTest.java       # real PostgreSQL/pgvector test
        ├── agent/fixture/                    # deliberately illegal package
        ├── review/fixture/
        └── scm/fixture/
```

`target/` is build output and is ignored (`.gitignore`). Root-level build and
deployment files are not imported by application code.

## Package organization

The eight allowed top-level packages and the rule against building empty
`domain/application/infrastructure/web` trees for symmetry are defined in
`ARCHITECTURE.md` §1.1. This repository realizes that rule as follows:

- Each authorized package owns its feature classes directly and retains a
  `package-info.java` that describes the boundary. Adding a directory is not
  the same as adding a layer: create a class only for real behavior in the
  package that owns it.
- Feature code is flat inside its package. Sub-packages are allowed only where
  `ARCHITECTURE.md` §1.1 already permits them.
- A new top-level production package under `com.forgepilot` is a rule
  violation, including infrastructure-sounding names such as `config`,
  `support`, or `util`. Cross-cutting code belongs in `common`.

## Test layout

- Cross-cutting suites live directly in the root test package
  `com.forgepilot`; feature tests belong in the feature's test subtree.
- ArchUnit counter-probe fixtures live under `src/test/java` in
  `<feature>/fixture`, plus one intentionally forbidden
  `com.forgepilot.agent.fixture`. They exist only to prove the rules are not
  tautologies and must never be moved into `src/main/java`.
- `ArchitectureRulesTest` imports production classes with
  `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`, so those fixtures cannot
  weaken the production rules. Keep that import option when editing the suite.

## Resource layout

- `application.yml` holds the default configuration. Additional profiles use
  `application-<profile>.yml`; `capacity` is currently the only one.
- Migrations live in `src/main/resources/db/migration` and are the only place
  schema changes are allowed. See
  [database-guidelines.md](./database-guidelines.md).

## Reference examples

- [ForgePilotApplication.java](../../../backend/src/main/java/com/forgepilot/ForgePilotApplication.java)
  is the bootstrap class and the root-package exception to the feature rule.
- [review/package-info.java](../../../backend/src/main/java/com/forgepilot/review/package-info.java)
  states the single-engine boundary delivered by batch 3.
