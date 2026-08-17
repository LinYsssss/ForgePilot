# Security Guidelines

> Backend security conventions for this project. Cross-layer contracts include the frontend side where the two must move together.

---

## Scenario: SPA CSRF over stateless sessions (cookie + header double-submit)

### 1. Scope / Trigger

- Trigger: any change to `SecurityConfig` CSRF wiring, the auth cookie/session model, login/logout flows, or the frontend `api/client.js` bootstrap. This contract exists because the defaults are wrong for us: with **stateless auth (token cookie, no HttpSession)**, Spring Security's default `CsrfAuthenticationStrategy` treats *every authenticated request* as a fresh authentication and clears the `XSRF-TOKEN` cookie on each authenticated response (deferred re-issue never fires for JSON endpoints that never render a token). The browser's token is silently erased; the first write after login gets 403 and the SPA force-logs-out.

### 2. Signatures

- Backend (`SecurityConfig`, CSRF-enabled branch):
  ```java
  http.csrf(csrf -> csrf
          .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
          .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
          // Rotation is done explicitly by CsrfTokenRotator on login/logout.
          .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
          .ignoringRequestMatchers("/api/webhooks/**"));
  ```
- Frontend (`api/client.js`): `initCsrf()` = `GET /api/auth/csrf` to (re)obtain the cookie. Must run at app bootstrap **and immediately after login and logout** (`LoginView.login()`, `useWorkspace.logout()`).
- Rotation endpoint contract: `POST /api/auth/login` responds with `Set-Cookie: reposage_auth=...` **and** a rotated `Set-Cookie: XSRF-TOKEN=...` (non-empty, different from the pre-login token).

### 3. Contracts

- Cookie `XSRF-TOKEN`: `Path=/`, **not** HttpOnly (SPA must read it), value echoed back via `X-XSRF-TOKEN` header on every non-GET `/api/**` request except `/api/webhooks/**` (signature-verified separately).
- Env: `SECURITY_CSRF_ENABLED` (default `true`; `false` is an explicit deployment fallback). Legacy tests run with surefire's `app.security.csrf.enabled=false`; CSRF-specific tests re-enable it with inline properties (higher precedence).
- Authenticated **reads must not** emit any `XSRF-TOKEN` clearing header (`XSRF-TOKEN=;` / empty value).

### 4. Validation & Error Matrix

- Non-GET `/api/**` without header or with stale token → 403 (and the SPA treats it as a fatal auth error → global logout). This is why the cookie must never be silently cleared.
- Login with valid pre-login token → 200 + rotated token; subsequent writes must use the **rotated** value, not the pre-login one.
- `GET /api/auth/csrf` → 200, always safe to call, idempotent bootstrap.

### 5. Good/Base/Bad Cases

- Good: bootstrap → login (old token) → `initCsrf()` → authenticated reads (cookie untouched) → first write with rotated token → 200.
- Base: CSRF disabled (`SECURITY_CSRF_ENABLED=false`): no cookie dance; writes pass on auth cookie alone.
- Bad (the defect this spec pins): default `CsrfAuthenticationStrategy` + stateless auth → every authenticated response clears the cookie → first write after login 403 → SPA force-logout loop. Also bad: frontend "optimizing away" the post-login `initCsrf()` — the rotated cookie is deferred and a plain JSON response never delivers it.

### 6. Tests Required

- `SpaCsrfBrowserFlowTest` (pattern to preserve): drive **the real browser sequence** with MockMvc — `GET /api/auth/csrf` → `POST /api/auth/login` with pre-login cookie+header → assert rotated non-empty token; then (a) authenticated `GET` asserts **no** `XSRF-TOKEN` clearing `Set-Cookie`, (b) first `POST` after login with rotated token → 200.
- Do **not** rely on the `csrf()` MockMvc post-processor alone: it injects a valid token per request and cannot see cookie-lifecycle regressions.

### 7. Wrong vs Correct

#### Wrong
```java
// Stateless session + CSRF defaults: token cookie is cleared on every
// authenticated response; browser token gone; first write after login 403.
http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
```
```js
// Login without re-bootstrapping CSRF: next write 403s and force-logs-out.
await api('/auth/login', { method: 'POST', body })
authenticated.value = true
```

#### Correct
```java
http.csrf(csrf -> csrf
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()));
```
```js
await api('/auth/login', { method: 'POST', body })
await initCsrf()          // rotated cookie is deferred; fetch it explicitly
authenticated.value = true
```

---

**Related**: `.trellis/tasks/07-31-feature-enhance/research/compare-walkthrough-result.md` (discovery record), `backend/src/test/java/com/example/codereview/config/SpaCsrfBrowserFlowTest.java`.

---

## Scenario: Supply-chain gate (trivy) & dependency CVE remediation

### 1. Scope / Trigger

- Trigger: any change to the `supply-chain` job in `.github/workflows/ci.yml`, to `deploy/scan-images.sh`, to the repo-root `.trivyignore`, or any dependency bump made to clear a CVE reported by the gate. The gate blocks on **fixable HIGH/CRITICAL only** (`ignore-unfixed: true` / same policy in `scan-images.sh`), so every red here is actionable by design — never "record and defer" a finding that keeps CI red.
- One blind spot sits between "fixable" and "unfixed": upstream has **published** a fix, but **no pullable artifact carries it yet** (typical for base images — the Go toolchain patch lands weeks before any image rebuilt with it). Trivy calls that fixable, so `ignore-unfixed` will not clear it and no version bump can. That, and only that, is what the repo-root `.trivyignore` is for.

### 2. Signatures

- Gate = two layers, both must pass:
  ```yaml
  uses: aquasecurity/trivy-action@v0.36.0   # fs scan of Maven/npm/Python manifests, exit-code "1"
  run: deploy/scan-images.sh reposage-{backend,frontend,sandbox-runner}:ci
  ```
- Remediation pattern (backend): override the Boot-managed version via a property in `backend/pom.xml` `<properties>`, never by hardcoding versions in `<dependencies>`:
  ```xml
  <!-- CVE-2026-41695(DoS,HIGH):Boot 3.5.14 manages 2025.0.11 (commons 3.5.11), fixed in 3.5.12 => BOM 2025.0.12. -->
  <spring-data-bom.version>2025.0.12</spring-data-bom.version>
  ```
  Precedents in the same file: `jackson-bom.version`, `spring-framework.version`, `tomcat.version`, `postgresql.version`.
- Suppression (base-image blind spot only) = repo-root `.trivyignore`, one dated line per CVE:
  ```
  # stdlib/x-net idna punycode 提权,修复版 Go 1.26.6 / 1.27.0-rc.3
  CVE-2026-39821 exp:2026-11-01
  ```
  Containerized trivy does **not** see the host file — `scan-images.sh` must mount it and pass `--ignorefile`; the `trivy-action` fs scan picks up the repo-root file on its own.

### 3. Contracts

- Action pinning: `aquasecurity/trivy-action` release tags are `v`-prefixed. Versions **≤ v0.29.0 are permanently broken** — they pin `aquasecurity/setup-trivy` by old tags that upstream has deleted (`Unable to resolve action ... setup-trivy@v0.2.1`). v0.33.1+ pin setup-trivy by commit SHA; stay on those.
- A 2-second failure at "Set up job" means action resolution, not scanning — check transitive action refs, not only the top-level tag.
- CVE comment convention: every override property carries a comment naming the CVE, the Boot-managed version, and the fixed version.
- Every `.trivyignore` entry carries an `exp:` date — an entry without one is forbidden, because a suppression that never expires is indistinguishable from a forgotten one. The accompanying comment must state (a) where the finding lives, (b) why the code path is unreachable in our usage, and (c) the concrete release condition that lets the line be deleted. Suppress only after checking that no published tag carries the fix; a suppression used in place of an available bump is a defect.
- A permanently red gate is itself a security failure — it teaches everyone to ignore the job. Prefer a dated, justified suppression over an indefinite red.

### 4. Validation & Error Matrix

- Fixable HIGH/CRITICAL in any image → `Scan images` fails: `FAIL: 存在已有修复版本的 HIGH/CRITICAL 漏洞,升级依赖或基础镜像后重扫`.
- Unresolvable action ref → job dies at "Set up job" in ~2 s, nothing scanned (a gate that never ran looks like a gate that passed — verify output exists, not just green).
- Fixable HIGH/CRITICAL in a manifest → `Scan dependency manifests` step fails (exit-code "1").
- Gate red on a base-image CVE that no available tag fixes → confirm at the source before suppressing (for `docker:*-cli`: compare `ARG GO_VERSION` in `docker/cli` on the release branch vs. `master`; the newest tag may still trail the fix). Suppressing without that check hides a real, fixable finding.
- `.trivyignore` entry past its `exp:` date → the finding blocks again automatically. That is the intended forcing function, not a regression.

### 5. Good/Base/Bad Cases

- Good: bump via BOM property (patch-level), verify resolution, full test suite green, CI image scan returns `Total: 0`.
- Base: finding has no fixed version → gate ignores it (`ignore-unfixed`), no action needed.
- Base: fix published but no image carries it yet → verify the claim at the source (e.g. `ARG GO_VERSION` on the upstream release branch vs. `master`), bump to the newest tag anyway for its other fixes, then add a dated `.trivyignore` line.
- Bad: pinning `trivy-action@v0.28.0` (dead transitive tag); hardcoding a version inside `<dependencyManagement>`/`<dependencies>` instead of the property override; "registering the CVE for later" while the gate stays red; a `.trivyignore` entry with no `exp:` or no reachability argument; assuming a version bump fixes a CVE without checking the toolchain the artifact was actually built with.

### 6. Tests Required

- After an override, verify what actually resolves before pushing:
  ```bash
  mvn -s .mvn/settings.xml help:evaluate -Dexpression=<property> -q -DforceStdout
  mvn -s .mvn/settings.xml dependency:list -DincludeArtifactIds=<artifact> -q -DoutputFile=/dev/stdout
  ```
- Full backend suite (containerized on the deploy host) + the CI `supply-chain` job itself as the authoritative end-to-end check.

### 7. Wrong vs Correct

#### Wrong
```yaml
uses: aquasecurity/trivy-action@0.28.0    # tag without v never existed
uses: aquasecurity/trivy-action@v0.28.0   # exists, but transitively dead (deleted setup-trivy tag)
```

#### Correct
```yaml
# v0.29.0 and earlier pin deleted setup-trivy tags; v0.33.1+ pin by commit SHA.
uses: aquasecurity/trivy-action@v0.36.0
```

---

**Related**: `.trellis/tasks/08-03-r1-fix-unblock-ci/trivy-evidence.md` (first real run of the gate: detection + remediation record), `deploy/scan-images.sh`.
