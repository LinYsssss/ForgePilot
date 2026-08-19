# Deletion/preservation manifest — 2026-08-18 checkpoint

## Deleted disposable outputs

- `backend/target/`
- `sandbox-runner/target/`
- `frontend/dist/`
- `frontend/node_modules/`
- `frontend/.npm-cache/` (when present)
- `backend/.work/`
- `scripts/__pycache__/`
- `model-service/tests/__pycache__/`
- `model-service/__pycache__/` (when present)
- `.trellis/scripts/common/__pycache__/`
- `.pytest_cache/`
- tracked `model-service/tests/__pycache__/test_main.cpython-312-pytest-8.3.4.pyc`

All paths were resolved under the fresh ForgePilot audit clone before deletion. They are reproducible from lockfiles/scripts and have no product/evidence value.

## Preserved

- `demo-repos/*/.git/`: required regenerable demo runtime state for deterministic smoke/demo verification; not tracked and recreated by `scripts/init-demo-repos.ps1 -Verify`.
- `.trellis/.developer` and `.trellis/.runtime/`: current Trellis session/task runtime state; not product artifacts.
- `.trellis/tasks/**` screenshots, QA, evaluation and migration evidence.
- `demo-repos/`, `evaluation/cases`, model-service model artifact, and all intentional fixtures.

## Next cleanup

After future tests, rerun the same explicit cleanup allowlist; never use `git clean -fdx` because it could remove Trellis runtime and intentional audit assets.
