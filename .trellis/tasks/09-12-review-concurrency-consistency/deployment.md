# Deployment and prior-review cleanup — 2026-09-12

## Release

- Application commit: `3d002116ea1f9bdc086e83f6cbcc929d1da60ae2`
  (`fix: guard review and requirement concurrency`), pushed to `origin/main`.
- [CI run 34670332547](https://github.com/LinYsssss/ForgePilot/actions/runs/34670332547)
  completed successfully for this exact commit.
- Existing stack: Compose project `fp-demo`, `/root/ForgePilot/compose.yaml`,
  original private `.env`, public address <https://yasinlin.com>.
- Built backend/frontend and ran `up --detach --no-build --no-deps --wait
  --wait-timeout 180 backend frontend`. Both commands exited 0.
- Backend container `977b79224ddf`, frontend `60a3a1b7b7a8`: running and healthy.
  PostgreSQL container `8dd86d1a690a` and its existing data volume were retained.
  Schema remains V14; no migration was added by these fixes.

| Service | Deployed image ID |
|---|---|
| backend | `sha256:56f14902a776fc66be73da65157d40ee079f687f6fc8f9182386b62719030881` |
| frontend | `sha256:c1099b03f996c30af19833ca81310a31e40ca0059afd9f0aa7fc593c6d6b4b81` |
| postgres | `sha256:a947c45cdc5906a1bc951f20a8709e321256343ee0f251e4ae00b5e7def4e6da` |

Loopback backend health, frontend-proxied health, public health, and public `/`
returned HTTP 200. Public JavaScript `/assets/index-Dnjjy4sb.js` matches the
verified local production build byte for byte, with SHA-256
`c0cc0d8dd2664ed85c571488656512a0021567fa1a0bcf22dcbb660cab6ea3e1`.

## Backup and cleanup

Before deployment and cleanup, created a private custom-format database backup:

`/root/forgepilot-backups/review-sequential-20260912T032656Z/database.dump`

- Directory mode 0700, file mode 0600, 201,353 bytes.
- SHA-256: `215f51e408603bed45471ee1687e66a6c7f90c412fbd31eb0b73f235909d0bfc`.
- `pg_restore --list --file=/dev/null` exited 0.

The user authorized clearing previous review records. A transaction with table
locks and explicit preconditions removed only the previous local snapshot of
mall PR #6: local PR 1, Review 1, its seven Findings, six Finding events, and one
PR association event. No review-associated AI call logs existed. The transaction
refused to proceed if an active review or an additional review for that PR existed.

| Object | Before | After cleanup |
|---|---:|---:|
| review | 1 | 0 |
| finding | 7 | 0 |
| finding_event | 6 | 0 |
| pull_request | 1 | 0 |
| project | 3 | 3 |
| requirement | 2 | 2 |
| knowledge_document | 4 | 4 |
| user_account | 11 | 11 |

Sequences were not reset. Accounts, memberships, project configuration,
requirements, knowledge, SCM credentials, remote PR history, and all immutable
formal evaluation assets were retained.

Browser login as the existing developer and reviewer succeeded after deployment.
Both saw an empty review list for project 1, with no JavaScript page errors.
The owner account does not use the shared test password; valid owner login
information was requested before continuing the privileged setup for 15 new PRs.

## Local operational evidence

- `/tmp/forgepilot-concurrency-deploy-build.log`
- `/tmp/forgepilot-concurrency-deploy-up.log`
- `/tmp/forgepilot-sequential-pr-20260912/cleanup.sql`
- `/tmp/forgepilot-sequential-pr-20260912/evidence/deployment-http.json`
- `/tmp/forgepilot-sequential-pr-20260912/evidence/clean-reviews-dev01.png`
- `/tmp/forgepilot-sequential-pr-20260912/evidence/clean-reviews-rev01.png`

Browser authentication cookies and all credentials remain in private local
files outside Git. The 15-PR exercise will be recorded separately from the
automated regression tests and the immutable formal evaluation.
