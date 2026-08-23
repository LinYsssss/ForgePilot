# Implementation plan — 顶部导航、界面统一与演示数据重置

## 1. Decision and contracts

- [x] Add D018 for centered top navigation and single-Logo-per-surface placement.
- [x] Update Architecture, AGENTS status, and frontend design/component specs without changing six routes or product boundaries.

## 2. Shell and login

- [x] Replace the signed-in sidebar with a centered desktop top navigation in `AppShell.vue` and base styles.
- [x] Preserve project-aware navigation targets, skip link, account/password popover, logout, responsive and reduced-motion behavior.
- [x] Rework `LoginPage.vue` so only `logo-app.png` appears there, with a clearer brand story and focused access card.

## 3. Product surface refinement

- [x] Reformat and refine Workspace, Knowledge and Repository page templates/styles.
- [x] Harmonize shared page heads, project selectors, summary cards, panels, records and empty states across all six top-level pages.
- [x] Keep AI chain/vector metadata prominent and all data/API behavior unchanged.

## 4. Focused validation

- [x] Update existing Shell/route/journey expectations only where the layout contract changed.
- [x] Run frontend lint, strict typecheck, focused tests, full tests and production build once.
- [x] Audit dependencies, routes, Logo placement, query preservation, responsive overflow and `git diff --check`.

## 5. Deploy and destructive cleanup

- [x] Reconfirm the exact `ysainlin` account row and current business row counts.
- [x] Deploy the updated frontend through the existing `fp-demo` Compose project without removing volumes.
- [x] Execute the explicit 15-table transactional truncate, leaving `user_account` untouched.
- [x] Prove `ysainlin` is the only enabled account and all other business tables are empty.
- [x] Verify container health, health endpoints, product routes and brand assets.

## 6. Finish

- [x] Write `result.md` with real command outputs and the irreversible data-cleanup record.
- [x] Run Trellis full-scope check, update specs, present commit grouping, and wait for explicit commit/push authorization.

## Rollback points

- Before section 5: all changes are code-only and fully reversible.
- After deployment but before truncate: roll back the frontend image if needed; database remains untouched.
- After truncate: business test data is intentionally irreversible; keep the sole account and database schema intact.
