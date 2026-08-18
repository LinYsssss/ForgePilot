# Implement：全仓代码、生成物与生产就绪审计

## 0. Baseline and context

- [x] Preserve fresh main SHA and clean status; record test/tool availability.
- [x] Curate implement/check context with relevant specs and initial inventory.
- [x] Produce initial DELETE/KEEP/INVESTIGATE inventory in `research/initial-inventory.md`; final manifest remains pending for the next audit slice.

## 1. Repository hygiene

- [x] Remove tracked `model-service/tests/__pycache__/test_main.cpython-312-pytest-8.3.4.pyc`.
- [x] Add global Python cache/pytest ignore rules and a regression check for tracked generated artifacts.
- [x] Classify exact duplicates; protect evaluation base/head fixtures and demo assets.
- [x] Audit largest tracked files; intentional Trellis/migration/QA/model/evaluation evidence is classified KEEP in the initial inventory.
- [ ] Remove task-generated local caches/build outputs by explicit verified paths only.

## 2. Script and developer-flow fixes

- [x] Rewrite `smoke-backend.ps1` around WebRequestSession + CSRF + HttpOnly cookie; update paginated API envelopes; focused H2/Mock smoke passed.
- [x] Update `verify-local.ps1` to seed its ephemeral admin safely, initialize demo repos, and use ForgePilot output.
- [x] Make `scan-package-deps.py` repository-relative with `--root`/`BACKEND_JAVA_ROOT`; execution passed.

## 3. Production configuration

- [x] Require `GRAFANA_ADMIN_PASSWORD` in compose; update `.env.example` and docs.
- [ ] Audit all externally reachable ports, insecure-local flags, secret defaults, cookie/CSRF, TLS/Nginx, backup and observability settings.
- [x] Static compose/config review completed; Docker unavailable so runtime compose validation remains pending.

## 4. Full source audit and fixes

- [ ] Backend/security/authorization/transaction/async/resource/error/logging review.
- [ ] Sandbox trust-chain/contract/container/path/replay review.
- [ ] Frontend API/session/SSE/poller/accessibility/dead-code review.
- [ ] CI/dependency/Docker/evaluation/model-service historical-boundary review.
- [ ] Record each finding with severity/evidence; fix or explicitly close with proof.

## 5. Verification

- [x] Backend full verify: 730 tests passed, 6 skipped.
- [x] Sandbox full verify: 75 tests passed, 1 Windows symlink test skipped with documented reason.
- [x] Frontend `npm ci`, npmjs registry audit (0 vulnerabilities), 85 tests, and build passed; host Node 24 differs from declared Node 22 CI engine.
- [x] Model-service temporary pinned venv: 9 tests passed; deprecation warnings recorded.
- [x] Existing P8 evaluation selftest/context validation remain green in the fresh ForgePilot clone.
- [x] PowerShell/Python script checks, tracked-generated scan baseline, and focused smoke passed; Nginx/Docker checks remain environment-dependent.
- [x] Docker/Trivy unavailable is explicitly recorded; exact follow-up commands remain in this task design.

## 6. Finish

- [ ] Run Trellis check; update specs with new cleanup/smoke/config contracts.
- [ ] Write deletion, preservation, findings and verification reports.
- [ ] Commit in intentional slices, push ForgePilot main, verify remote/CI.


## 2026-08-18 checkpoint before pause

- Full `pwsh scripts/verify-local.ps1` passed: backend tests 730 passed/6 skipped, frontend 85 passed/build, demo refs verified, H2/Mock smoke passed with SUCCESS review, report, feedback and logs.
- Sandbox full verify passed 75 tests with one Windows symlink-privilege skip.
- `npm_config_registry=https://registry.npmjs.org npm audit --audit-level=high` reported 0 vulnerabilities.
- Model-service temporary pinned venv tests: 9 passed; deprecation warnings recorded.
- `scan-structure.py` was fixed for UTF-8 on Windows and passed; `scan-package-deps.py` passed with repository-relative root.
- Changes are intentionally not marked complete: full source/dead-code/security review, generated-output cleanup, Docker/Trivy/Compose runtime checks, and exact Node22 verification remain.
- Generated cleanup allowlist executed after verification; deleted targets are recorded in `research/deletion-manifest.md`. Demo nested `.git` was intentionally preserved as regenerable smoke state.
