# Deployment — 2026-09-20

## Release

- Application commits `631fb51`, `eb1e7e3`, and `4121cea` were pushed to
  `origin/main`; deployed HEAD was `4121cea`.
- Existing Compose project `fp-demo` used `/root/ForgePilot/compose.yaml` and
  the existing private environment configuration.
- Only backend and frontend were rebuilt and replaced. The PostgreSQL container
  and its data volume were retained.
- Backend, frontend, and PostgreSQL all reached healthy state after replacement.
  Schema remained at Flyway V14; the release added no migration.

| Service | Deployed image ID |
|---|---|
| backend | `sha256:7ab0c5c31f1ea63b2122702fc14b59270d440bccdfda43df604a338c8f3a8a54` |
| frontend | `sha256:080cc24a165997c951e82f726aa05a02b2607b40cebf81c0c0e2920d5713bb59` |
| postgres | `sha256:a947c45cdc5906a1bc951f20a8709e321256343ee0f251e4ae00b5e7def4e6da` |

Loopback backend `/actuator/health`, frontend `/healthz`, and public
`https://yasinlin.com/api/actuator/health` all returned healthy responses. The
public JavaScript asset `/assets/index-DOonhBjg.js` matched the container asset
byte for byte with SHA-256
`54db9966771eb29e754d2ebcd888555bd92aad81490076ff1d90c2540ef578b9`.
The public backend health route includes `/api`; `/actuator/health` is handled
by the SPA fallback.

## Pre-deployment backup

The existing production state was saved before replacement:

`/root/forgepilot-backups/call-chain-20260920T065012Z/database.dump`

- Size: 431,683 bytes; mode 0600.
- SHA-256: `4ed4c85eaf36a8c2bb5a20b03dd4cc79da3609efab2b656cc9c95fc64ac47321`.
- Container `pg_restore --list` validation passed.

Before deployment, the database contained 15 COMPLETED reviews, 12 READY
knowledge documents, three projects, 17 requirements, and 57 findings. No
PENDING or RUNNING review or knowledge work existed.

## Post-deployment PR revalidation

The 15 existing real GitHub PR snapshots were revalidated serially after the
deployment. Each linked requirement received a semantically identical revision
with an explicit revalidation reason; each new revision then created one new
Review. Historical Review rows and decisions were retained. Full method and
results are in [production-revalidation.md](production-revalidation.md).

After the exercise, a second private custom-format backup preserved the new
production state:

`/root/forgepilot-backups/call-chain-revalidation-20260920T082408Z/database.dump`

- Size: 477,605 bytes; mode 0600.
- SHA-256: `fda57e0dd4db5f78a3c7abb149ce6eae1b437f4f685b4cf5a009d57fae0459c2`.
- Container `pg_restore --list` validation passed.

No formal evaluation asset was changed or rerun.
