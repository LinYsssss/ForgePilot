# PR Gatekeeper Phase 1 Engineering Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a reproducible build, migration, test, and CI baseline before adding the Agent runtime.

**Architecture:** Fix the current frontend build first, replace production schema initialization with Flyway, add PostgreSQL/RabbitMQ integration tests, and make the existing verification script the single local quality gate. Preserve current APIs and database data.

**Tech Stack:** Vite 6, Vue 3, Maven, JUnit 5, Testcontainers, PostgreSQL 16/pgvector, RabbitMQ 3, Flyway, GitHub Actions.

---

### Task 0: Freeze the current workspace checkpoint

**Files:**
- No feature files are changed by this task.

- [ ] **Step 1: Inventory and validate current changes**

```powershell
git status --short
git diff --stat
git diff --check
cd backend
mvn -s .mvn/settings.xml test
cd ..\frontend
npm run build
```

Expected: generated files and credentials are excluded, backend tests pass, and the frontend result is recorded. The previously observed absolute-path Vite failure is currently not reproducible, so it must not be treated as a confirmed configuration defect.

- [ ] **Step 2: Commit the reviewed existing work separately**

Stage reviewed paths explicitly with `git add path/to/file`; do not use `git add -A` for this checkpoint. Then run:

```powershell
git diff --cached --check
git diff --cached --stat
git commit -m "feat: checkpoint current repository workflow"
git rev-parse HEAD
```

- [ ] **Step 3: Create an isolated worktree**

Use `superpowers:using-git-worktrees`. Phase 1 starts only from the clean checkpoint.

### Task 1: Stabilize the frontend production build

**Files:**
- Modify: `frontend/vite.config.js`
- Modify only if required by the failing regression: `frontend/index.html`
- Create: `frontend/tests/smoke.test.mjs`
- Modify: `frontend/package.json`
- Create: `frontend/.nvmrc`

- [ ] **Step 1: Add a frontend entrypoint smoke test**

```js
// frontend/tests/smoke.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'

test('index declares the Vue entrypoint', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8')
  assert.match(html, /src="\/src\/main\.js"/)
})
```

- [ ] **Step 2: Pin the supported runtime and add the test script**

```json
"engines": {
  "node": ">=20 <23"
},
"scripts": {
  "test": "node --test tests/*.test.mjs",
  "build": "vite build"
}
```

Run:

```powershell
cd frontend
npm test
```

Create `frontend/.nvmrc` containing `20`.

- [ ] **Step 3: Make only deterministic output settings explicit**

```js
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

- [ ] **Step 4: Verify tests and production build**

Run:

```powershell
npm ci
npm test
npm run build
```

Expected: both commands exit `0`; `frontend/dist/index.html` is produced.

If the absolute-path Rollup error returns, capture `node --version`, `npm --version`, `Get-Location`, and `npm exec vite build -- --debug` before changing configuration. `base: './'` is not a proven fix.

- [ ] **Step 5: Commit**

```powershell
git add frontend/vite.config.js frontend/package.json frontend/tests/smoke.test.mjs frontend/.nvmrc
git commit -m "fix: make frontend production build reproducible"
```

### Task 2: Correct Maven compiler configuration and add baseline quality plugins

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Add a Maven model assertion**

Run:

```powershell
cd backend
mvn -s .mvn/settings.xml help:effective-pom -Doutput=target/effective-pom.xml
Select-String target/effective-pom.xml -Pattern '<release>17</release>'
```

Expected before the fix: no explicit project compiler `release` configuration, or conflicting `source/target` values.

- [ ] **Step 2: Replace the Java 8 override and add test coverage reporting**

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.release>17</maven.compiler.release>
    <testcontainers.version>1.20.4</testcontainers.version>
</properties>
```

Remove the custom `<source>8</source>` and `<target>8</target>` block. Add:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 3: Verify the effective compiler level**

Run:

```powershell
mvn -s .mvn/settings.xml clean verify
Select-String target/effective-pom.xml -Pattern '<release>17</release>'
```

Expected: build succeeds and the effective POM contains Java 17.

- [ ] **Step 4: Commit**

```powershell
git add backend/pom.xml
git commit -m "build: standardize backend on Java 17"
```

### Task 3: Introduce Flyway without losing existing production data

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/resources/db/migration/V1__baseline_schema.sql`
- Create: `backend/src/main/resources/db/migration/V2__review_task_idempotency.sql`
- Modify: `backend/src/main/resources/application-prod.yml`
- Modify: `deploy/init.sql`
- Retain temporarily: `backend/src/main/resources/db/schema-postgres.sql`

- [ ] **Step 1: Add a production configuration test**

```java
// backend/src/test/java/com/example/codereview/config/ProductionMigrationConfigTest.java
package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ProductionMigrationConfigTest {
    @Test
    void productionUsesFlywayAndDisablesSqlInit() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"))
                .get(0);
        assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(source.getProperty("spring.sql.init.mode")).isEqualTo("never");
    }
}
```

- [ ] **Step 2: Run the test and verify failure**

Run:

```powershell
mvn -s .mvn/settings.xml -Dtest=ProductionMigrationConfigTest test
```

Expected: FAIL because Flyway is not configured and SQL init is `always`.

- [ ] **Step 3: Add Flyway dependencies and production configuration**

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
    locations: classpath:db/migration
  sql:
    init:
      mode: never
```

- [ ] **Step 4: Convert the current schema into ordered migrations**

Compare the checkpoint schema with any deployed demo database before writing migrations. `V1__baseline_schema.sql` must contain the complete checkpoint table and index definitions from `schema-postgres.sql`, including `create extension if not exists vector`.

Because `baseline-on-migrate` permits non-empty legacy databases, `V2__review_task_idempotency.sql` must include idempotent upgrades for every column and index that may be absent from the legacy deployment, not only the sample below:

```sql
alter table review_task
    add column if not exists base_commit_id_normalized varchar(80) not null default '';

create unique index if not exists uk_review_task_idempotent
    on review_task(project_id, repository_id, commit_id, base_commit_id_normalized, branch_name);
```

Add `backend/src/test/resources/db/legacy-schema.sql` and an integration test that initializes this legacy schema, runs Flyway, and verifies Hibernate `ddl-auto=validate`. `create table if not exists` alone cannot upgrade incomplete existing tables.

Change `deploy/init.sql` to extension bootstrap only:

```sql
create extension if not exists vector;
```

- [ ] **Step 5: Verify configuration and migrations**

Run:

```powershell
mvn -s .mvn/settings.xml -Dtest=ProductionMigrationConfigTest test
mvn -s .mvn/settings.xml test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add backend/pom.xml backend/src/main/resources/application-prod.yml backend/src/main/resources/db/migration deploy/init.sql backend/src/test/java/com/example/codereview/config/ProductionMigrationConfigTest.java
git commit -m "feat: manage production schema with Flyway"
```

### Task 4: Add PostgreSQL and RabbitMQ Testcontainers infrastructure

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/com/example/codereview/support/IntegrationTestContainers.java`
- Create: `backend/src/test/java/com/example/codereview/config/InfrastructureIntegrationTest.java`

- [ ] **Step 1: Add Testcontainers dependencies**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Create reusable container registration**

```java
package com.example.codereview.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

public abstract class IntegrationTestContainers {
    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("code_review")
                    .withUsername("code_review")
                    .withPassword("test");

    @Container
    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management");

    protected static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
```

- [ ] **Step 3: Add infrastructure smoke assertions**

```java
@ActiveProfiles("prod")
@SpringBootTest(properties = {
        "app.review.inline=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.security.token-secret=test-secret-at-least-32-characters",
        "app.security.token-encrypt-key=test-encryption-key-at-least-32"
})
@Testcontainers
class InfrastructureIntegrationTest extends IntegrationTestContainers {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;

    @Test
    void migrationsAndRabbitConnectionAreAvailable() {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history", Integer.class)).isPositive();
        assertThat(rabbit.execute(channel -> channel.isOpen())).isTrue();
    }
}
```

- [ ] **Step 4: Run the integration test**

Run:

```powershell
mvn -s .mvn/settings.xml -Dtest=InfrastructureIntegrationTest test
```

Expected: PASS when Docker is available.

- [ ] **Step 5: Commit**

```powershell
git add backend/pom.xml backend/src/test/java/com/example/codereview/support backend/src/test/java/com/example/codereview/config/InfrastructureIntegrationTest.java
git commit -m "test: add containerized infrastructure integration tests"
```

### Task 5: Add core review pipeline regression tests

**Files:**
- Create: `backend/src/test/java/com/example/codereview/review/ReviewProcessorTest.java`
- Create: `backend/src/test/java/com/example/codereview/mq/ReviewTaskConsumerTest.java`
- Create: `backend/src/test/java/com/example/codereview/rag/RagServiceTest.java`

- [ ] **Step 1: Test canceled tasks do not write reports**

Create a Mockito test that loads a canceled `ReviewTask`, invokes `ReviewProcessor.process(taskId)`, and verifies:

```java
verifyNoInteractions(modelRiskClient, ragService, aiReviewClient, resultWriter);
```

- [ ] **Step 2: Test MQ retry classification**

Given `reviewProcessor.process` throws and `retryCount < maxRetry`, verify:

```java
verify(publisher).publishDelayed(message.nextRetry());
verify(publisher, never()).publishDead(any(), anyString());
```

Given retry count equals the limit, verify `markDead` and `publishDead`.

- [ ] **Step 3: Test RAG project and document isolation**

Create chunks for two projects and selected document IDs. Assert `buildContext(projectA, query, selectedIds)` contains only project A and selected-document content.

- [ ] **Step 4: Run focused tests**

```powershell
mvn -s .mvn/settings.xml -Dtest=ReviewProcessorTest,ReviewTaskConsumerTest,RagServiceTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/test/java/com/example/codereview/review backend/src/test/java/com/example/codereview/mq backend/src/test/java/com/example/codereview/rag/RagServiceTest.java
git commit -m "test: cover review recovery and RAG isolation"
```

### Task 6: Add CI and make verification deterministic

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `scripts/verify-local.ps1`
- Modify: `.gitignore`

- [ ] **Step 1: Ignore generated local artifacts**

Add:

```gitignore
.m2home/
frontend/.vite-dev.*.log
frontend/dist/
backend/target/
```

- [ ] **Step 2: Add GitHub Actions quality gate**

```yaml
name: ci
on:
  push:
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: mvn -s .mvn/settings.xml verify
        working-directory: backend
      - run: npm ci
        working-directory: frontend
      - run: npm test
        working-directory: frontend
      - run: npm run build
        working-directory: frontend
```

- [ ] **Step 3: Add frontend tests to local verification**

Before `Frontend build`, invoke:

```powershell
Invoke-Step "Frontend tests" {
    Invoke-CommandChecked -FilePath "npm" -Arguments @("test") -WorkingDirectory $FrontendDir
}
```

- [ ] **Step 4: Run the full local gate**

```powershell
.\scripts\verify-local.ps1 -SkipSmoke
```

Expected: backend tests, frontend tests, frontend build, and model check pass.

- [ ] **Step 5: Commit**

```powershell
git add .github/workflows/ci.yml .gitignore scripts/verify-local.ps1
git commit -m "ci: enforce reproducible project verification"
```

### Task 7: Document the baseline and phase exit

**Files:**
- Modify: `README.md`
- Modify: `docs/11_本地开发与联调手册.md`

- [ ] **Step 1: Document Java, Node, Docker, and test prerequisites**

State exact supported baseline: Java 17, Node 20, Maven 3.9+, Docker Desktop/Engine with Compose v2.

- [ ] **Step 2: Document Flyway rules**

State that schema changes require a new immutable `V<N>__description.sql`; existing migrations must never be edited after release.

- [ ] **Step 3: Record a compatibility matrix**

| Component | Supported baseline |
| --- | --- |
| Java | 17 |
| Node | 20 LTS |
| PostgreSQL | 16 + pgvector |
| RabbitMQ | 3.13 |
| Docker Compose | v2 |

- [ ] **Step 4: Run documentation and build checks**

```powershell
rg -n "Flyway|Java 17|Node 20|verify-local" README.md docs/11_本地开发与联调手册.md
.\scripts\verify-local.ps1 -SkipSmoke
```

Expected: all terms are present and verification passes.

- [ ] **Step 5: Commit**

```powershell
git add README.md docs/11_本地开发与联调手册.md
git commit -m "docs: define engineering baseline and migration workflow"
```
