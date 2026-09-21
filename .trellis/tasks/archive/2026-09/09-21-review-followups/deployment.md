# Deployment — 2026-09-21

## Release

- Commits `2487e94`…`10cec66` pushed to `origin/main`; deployed HEAD `10cec66`.
- Compose project `fp-demo`, only backend and frontend rebuilt and replaced;
  PostgreSQL container and volume retained. Schema stays at Flyway V14 (14 migrations).
- Pre-deployment backup: `/root/fp-demo-pre-deploy-20260921T101723Z.sql`.

| Service | Deployed image ID |
|---|---|
| backend | `sha256:b28c09da844919d4debfd05b7a253d382163eefad2b56238b386e3339b0185f6` |
| frontend | `sha256:30a7bceae61dd50a010a11c050fb495e0fd31c41b7dd9fd56d6f389010bedbcb` |
| postgres | `sha256:a947c45cdc5906a1bc951f20a8709e321256343ee0f251e4ae00b5e7def4e6da` |

## Post-deployment checks

- All three containers healthy; public `/api/actuator/health` 200.
- Public `/api/auth/me` now sends `XSRF-TOKEN … SameSite=Lax`,
  `Strict-Transport-Security` and `Referrer-Policy`.
- Loopback with a test account: unknown path → 404 `not_found`, malformed JSON
  → 400 `bad_request`, wrong method → 405 `method_not_allowed`, path type
  mismatch → 400 `bad_request`, all as `{code,message,traceId}` with Chinese
  messages; wrong password → 401 「用户名或密码错误。」 with empty traceId.
- Each of those traceIds appears in the backend log at WARN.
- Served JS asset `index-DRm_Q9uR.js` is the freshly built bundle.
