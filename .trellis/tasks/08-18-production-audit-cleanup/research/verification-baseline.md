# Audit verification baseline — 2026-08-18

## Passing checks

- Backend `mvn -B -s .mvn/settings.xml verify`: **730 tests, 0 failures/errors, 6 skipped**, BUILD SUCCESS.
- Sandbox Runner `mvn -B -s ../backend/.mvn/settings.xml verify`: **75 tests, 0 failures/errors, 1 skipped** on Windows; the skipped test is the symlink escape test gated by Windows symlink privilege, while Linux CI retains it.
- Frontend `npm ci`: passed; host Node was v24.13.0 while `package.json` declares `>=22 <23`, so exact-engine verification remains a CI/Node22 prerequisite.
- Frontend `npm test`: **85 passed**.
- Frontend `npm run build`: passed; existing Rollup warnings only.
- `npm_config_registry=https://registry.npmjs.org npm audit --audit-level=high`: **0 vulnerabilities**. The host default mirror returned an unsupported audit endpoint, so the CI registry was used explicitly.
- Model-service in temporary pinned venv: **9 pytest passed**, with 43 deprecation warnings from FastAPI `on_event`/Starlette test client and NumPy/joblib; it remains historical/non-production by architecture.
- `scripts/init-demo-repos.ps1 -Verify`: all six deterministic refs passed.
- Focused local H2/Mock smoke after fixes: **PASS**; health UP, auth cookie/CSRF, project/repository, knowledge upload/index/search, review SUCCESS, report, feedback, MQ logs and AI logs all completed.
- `python scripts/scan-package-deps.py --root backend/src/main/java`: passed and reports 411 Java files.

## Environment-limited checks

- Docker is unavailable; compose runtime, image build, Trivy image scan and real sandbox container execution were not run locally.
- `trivy` and `shellcheck` are unavailable on the host.
- Host Node 24 was used for local tests/build; CI Node 22 remains the authoritative engine match.

## Confirmed fixes in this audit

- Removed tracked `model-service/tests/__pycache__/test_main.cpython-312-pytest-8.3.4.pyc`.
- Added global Python cache/pytest ignore rules.
- Repaired smoke authentication to use HttpOnly cookie + CSRF and current paginated response shapes.
- Updated `verify-local.ps1` to initialize demo repos and seed a temporary admin through child-process environment.
- Made `scan-package-deps.py` repository-relative with `--root` and `BACKEND_JAVA_ROOT` support.
- Removed Grafana's known `admin` password fallback and aligned deployment docs/example.
- Gated the Windows symlink test while preserving Linux coverage.
