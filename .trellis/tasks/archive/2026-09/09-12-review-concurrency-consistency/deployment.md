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
The user's supplied owner credentials were subsequently used successfully in the
real browser UI. Owner, developer, and reviewer login are all verified; project
preparation and the sequential 15-PR exercise are complete. The final report is
[live-review-exercise.md](live-review-exercise.md).

## Local operational evidence

- `/tmp/forgepilot-concurrency-deploy-build.log`
- `/tmp/forgepilot-concurrency-deploy-up.log`
- `/tmp/forgepilot-sequential-pr-20260912/cleanup.sql`
- `/tmp/forgepilot-sequential-pr-20260912/evidence/deployment-http.json`
- `/tmp/forgepilot-sequential-pr-20260912/evidence/clean-reviews-dev01.png`
- `/tmp/forgepilot-sequential-pr-20260912/evidence/clean-reviews-rev01.png`

Browser authentication cookies and all credentials remain in private local
files outside Git. The 15-PR exercise is recorded separately from the automated
regression tests and the immutable formal evaluation.

## Post-exercise verification

At `2026-09-12T05:59:17.783Z`, read-only database checks confirmed:

- 15 COMPLETED reviews, five per project; zero PENDING/RUNNING reviews.
- 15 REQUEST_CHANGES decisions by `rev01`; all requirements remain IN_DEVELOPMENT.
- 55 findings claimed by `dev01`, two rejected, and exactly 112 finding events.
- Zero current-input or immutable-snapshot mismatches; 12 READY knowledge documents.
- Schema remains V14. Public and loopback health returned HTTP 200/UP; the
  deployed JavaScript still has the verified SHA-256 recorded above.

The final browser audit also verified all 15 PR heads, requirement versions,
decisions, finding states, and successful GitHub webhook deliveries. Exact
delivery IDs are stored as strings in [live-review-results.json](live-review-results.json).

A second private custom-format backup preserves the completed exercise:

`/root/forgepilot-backups/review-sequential-complete-20260912T055915Z/database.dump`

- Directory mode 0700, file mode 0600; 431,683 bytes.
- SHA-256: `5f6aea6ebb730e0acbf9c1a7e373f92973428cb26b70eed544b3077713d981c6`.
- `pg_restore --list --file=/dev/null` exited 0.

The source branches intentionally contain defects. Their remote PRs remain open
for implementation work; the exercise did not mark unimplemented fixes as done.
