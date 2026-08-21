# Phase 1 Compose smoke evidence

Two fresh-volume cold starts required by `validation.md` §7 and PRD AC5/AC9.
Both runs used `scripts/phase1-compose-smoke.sh`, which refuses to reuse an
existing Compose project, volume, or network, and cleans up via a trap on both
the success and the failure path.

| Run | Project | Log | Exit |
| --- | --- | --- | --- |
| 1 | `forgepilot-phase1-final-e` | `20260821-final-e.txt` | 0 |
| 2 | `forgepilot-phase1-final-f` | `20260821-final-f.txt` | 0 |

Both runs were executed on 2026-08-21 UTC against the working tree described by
the task `result.md`. Run 1 rebuilt both application images from scratch
(`backend/Dockerfile`, `backend/mvnw`, `backend/.mvn/wrapper/maven-wrapper.properties`,
`backend/src/main/resources/application.yml` and the new `frontend/.dockerignore`
had all changed, so the `COPY . .` layer was invalidated); run 2 reused that
layer cache, which is why its log is shorter.

## What each run asserted

Static contract, before anything is started:

- exactly the services `backend`, `frontend`, `postgres`
- `mem_limit` exactly 512 MiB / 768 MiB / 64 MiB
- every service declares an explicit healthcheck
- the postgres image is pinned to the approved digest
- exactly one named volume, `postgres-data`
- the generic `/api/` nginx proxy preserves the `/api` prefix

Runtime contract, after `up --wait`:

- all three containers report `healthy`
- `GET /actuator/health` on the backend returns `"status":"UP"`
- `GET /api/actuator/health` through the frontend proxy returns `"status":"UP"`
- `GET /actuator/metrics` returns **404** under the default profile
- `GET /healthz` and `GET /` on the frontend succeed
- `current_setting('server_version_num') >= 150000`
- the `vector` extension is installed and `'[1,2,3]'::vector <-> '[1,2,4]'::vector` equals 1
- `flyway_schema_history` records version 1 as successful
- the public schema contains **zero** application tables besides `flyway_schema_history`

Both runs ended with `Phase 1 Compose smoke passed ... (pgvector 0.8.6)` and
removed their own containers, network, and named volume. After run 2,
`docker volume ls`, `docker network ls`, and `docker ps -a` filtered on
`forgepilot` were all empty.

## Image identity at the time of the runs

```text
backend   sha256:85ae85d413f365d716a9826dc7d1bf35ad6cb072706037240f7fd1817cb11eef
frontend  sha256:e0dc66d01f229203ed6d2f4e5dd8a951bc15274959747b8b37af657971c87059
postgres  pgvector/pgvector@sha256:a947c45cdc5906a1bc951f20a8709e321256343ee0f251e4ae00b5e7def4e6da
```

The backend and frontend ids are local build ids, not registry digests; Phase 1
does not publish these images. The postgres digest is the pinned upstream one
and matches `compose.yaml`.

## Boundary

These runs prove that an empty stack builds, starts, migrates, and answers its
health contract twice from clean volumes. They are not capacity evidence; the
4 GB host measurement lives in `../capacity/`. The existing `cpa-manager-plus`,
`cli-proxy-api`, and system `cloudflared` services were not stopped, restarted,
or reconfigured by either run.
