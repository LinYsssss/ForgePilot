# Deployment — 2026-09-08

Application commit `746e40b9eabaef80b80176f0373f0712eda4d4bb` was pushed to `origin/main` and deployed to the existing `fp-demo` stack at <https://yasinlin.com>. The user explicitly authorized push and deployment. Subsequent documentation and bookkeeping commits do not change the deployed application.

## Preflight and backup

- Deployment: `/root/ForgePilot/compose.yaml`, ignored root `.env`, Compose project `fp-demo`. Resolved service environments matched the existing containers; credentials and configuration were retained.
- PostgreSQL was at V13, with 21 business tables and zero PENDING/RUNNING reviews.
- Verified custom-format `pg_dump` backup using `pg_restore --list`: `/root/forgepilot-backups/review-collaboration-20260908T080009Z/database.dump`, 200,817 bytes, mode 0600 in a 0700 directory. This private backup is outside Git; its contents are not included here.

## Rollout

```bash
docker compose -p fp-demo build backend frontend
docker compose -p fp-demo up --detach --no-build --no-deps \
  --wait --wait-timeout 180 backend frontend
```

Both commands exited 0. Only the application containers were recreated. Frontend started at 08:22:50 UTC; the wait command returned after both application health checks passed.

| Service | Running image ID |
|---|---|
| backend | `sha256:64ec8b9f5a757959925effeba2bf52f142ed955082f57b77f82f6eab999ba13a` |
| frontend | `sha256:d6e05f17026cc30bbce3f6fd91fa9f170cbf2f143eca87b696264dc7067b798c` |
| postgres | `sha256:a947c45cdc5906a1bc951f20a8709e321256343ee0f251e4ae00b5e7def4e6da` |

PostgreSQL container `8dd86d1a690a` and volume `fp-demo_postgres-data` were retained. Flyway successfully applied `V14__requirement_reviewer.sql` at 08:22:23 UTC. Read-only inspection confirmed nullable bigint `reviewer_id`, composite foreign key `fk_requirement_reviewer` to `project_member(project_id, user_id)`, and 21 business tables.

| Record count | Before | After |
|---|---:|---:|
| user_account | 11 | 11 |
| project | 3 | 3 |
| requirement | 2 | 2 |
| review | 1 | 1 |
| PENDING/RUNNING reviews | 0 | 0 |

## Verification

- All three containers healthy; backend startup logs contained no ERROR lines.
- At 08:52 UTC, curl returned HTTP 200 for backend `127.0.0.1:18080/actuator/health`, frontend `127.0.0.1:18081/`, `/healthz`, `/api/actuator/health`, public `/` and public `/api/actuator/health`. Health JSON reported `UP`.
- Public, loopback and running-container assets matched byte for byte:

| Asset | SHA-256 |
|---|---|
| `index-CwTiKeAm.js` | `88bab02d7fc7124778aeeb9b2f0605afad07dd73100a68d83e3199bb3250aad8` |
| `index-B3PPqBZD.css` | `48cb7e73ce4eb09120c74d669be2e6760aebbc26d89027f3251e842f2d083763` |

[CI run 34202968598](https://github.com/LinYsssss/ForgePilot/actions/runs/34202968598) for this exact commit passed all four jobs: 379 backend tests / 50 classes with no failures/errors/skips, 44 frontend tests / 15 files and all frontend gates, deterministic evaluation contracts, and two independent fresh-volume Compose cold starts. See [validation.md](validation.md) for the earlier local debugging records.

Workspace logs: `/tmp/forgepilot-review-collaboration-deploy-build.log`, `/tmp/forgepilot-review-collaboration-deploy-up.log`, `/tmp/forgepilot-review-collaboration-postdeploy-http.json`, `/tmp/forgepilot-review-collaboration-ci-work.log`. This tracked report preserves their material results independently of temporary log retention.

## Remaining boundaries and rollback

These checks verify deployment and HTTP delivery. Real-browser interaction, live SCM merge/return workflows and live DingTalk delivery were not exercised. Capacity measurements, formal model evaluation and holdout were not rerun or modified.

V14 is additive, but a rollback still requires Flyway/schema compatibility verification. Preserve the applied migration and nullable column in an application repair release, or use the matching database backup under a separately reviewed restore procedure. Do not remove migration history or use cold-start cleanup against the existing data volume.
