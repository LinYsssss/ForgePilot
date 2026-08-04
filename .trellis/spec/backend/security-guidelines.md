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
