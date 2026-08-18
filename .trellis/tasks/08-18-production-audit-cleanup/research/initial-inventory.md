# Initial repository and production audit inventory

**Date**: 2026-08-18
**Repository**: `LinYsssss/ForgePilot` fresh clone at main `47a0ed4`

## Confirmed cleanup evidence

1. `model-service/tests/__pycache__/test_main.cpython-312-pytest-8.3.4.pyc` is a tracked CPython/pytest cache artifact. It is the only tracked path matching build/cache/binary patterns and must be removed.
2. `.gitignore` contains only package-specific `__pycache__/` rules and no global `**/__pycache__/`, `*.py[cod]`, or `.pytest_cache/` rule, allowing the tracked `.pyc` regression.
3. Exact duplicate files reported under `evaluation/cases/*/{base,head}` are intentional diff fixtures. They are not deduplication candidates.
4. `.trellis/tasks/**` screenshots, QA images, migration maps, and archived research are evidence artifacts; size alone is not deletion evidence.
5. `demo-repos/` and `knowledge-noise/` are intentional product proof assets protected by `.trellis/spec/guides/demo-assets-and-claims.md`.
6. Fresh-clone ignored paths are limited to Trellis runtime/developer/cache files; no repository build output is present before checks.

## Confirmed functional/production findings

### High: backend smoke authentication is broken

- `scripts/smoke-backend.ps1:94-102` expects `login.data.token` and sends a Bearer token.
- `AuthDtos.AuthResponse` contains only `userId`, `username`, and `role`; `AuthController` stores the token exclusively in an HttpOnly cookie.
- The smoke script therefore sends `Authorization: Bearer ` and receives 401 on authenticated endpoints.
- `scripts/verify-local.ps1` also starts the dev backend without configuring `SEED_ADMIN_USERNAME/PASSWORD`, while the smoke script assumes `admin/admin123`; a clean H2 startup has no such user.

### Medium: production Grafana has a weak fallback password

- `deploy/docker-compose.yml` uses `GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-admin}`.
- The service is bound to localhost, but the documented access path is an SSH tunnel and the default remains a known credential.
- Production compose should require an explicit value, and `.env.example`/docs should provide a non-secret placeholder.

### Medium: package dependency scan is machine-path dependent

- `scripts/scan-package-deps.py` hardcodes `/root/ForgePilot/backend/src/main/java`.
- It cannot run from a normal local clone or a differently located deployment, making a repository audit tool non-reproducible.
- The default should resolve from the script/repository path with an optional CLI/environment override.

### Low: verification output retains the legacy product name

- `scripts/verify-local.ps1` prints `RepoSage local verification ...` although the public product is ForgePilot.
- Internal package/application identifiers remain out of scope, but user-facing verification output should be current.

## Baseline counts

- Tracked files: 1,632 before the new audit task.
- Reachable commits: 383 at the audit starting point (`47a0ed4` plus rewritten history/migration records).
- Tracked generated/cache hits: 1 (`.pyc`).
- Tags: 0.
- Docker is unavailable on the current host; Docker-dependent execution must be recorded separately rather than treated as passed.

## Research-agent status

Three delegated Trellis research agents failed before producing files due an external HTTP 530/Cloudflare service error. The main session performed the evidence inspection locally; no agent findings were assumed.
