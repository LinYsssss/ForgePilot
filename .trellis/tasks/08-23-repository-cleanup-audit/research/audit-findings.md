# Repository cleanup audit findings

## Confirmed cleanup targets

| Target | Evidence | Decision |
|---|---|---|
| `ForgePilot-Frontend/` | Ignored and untracked; archived frontend tasks call it a visual study and state that `frontend/` is the production application; study predates the current production frontend | Delete after approval |
| `backend/target/` | Maven output, ignored, about 62 MB | Delete after backend validation |
| `frontend/node_modules/` | npm install cache, ignored, about 132 MB | Delete after frontend validation |
| `frontend/dist/` | Vite output, ignored, about 368 KB | Delete after frontend validation |
| `ForgePilot-Frontend/dist/` | Old study build output | Removed with old study |

## Confirmed tracked defects

1. `docs/v2/IMPLEMENTATION-PLAN.md` references
   `.trellis/tasks/08-23-topnav-ui-data-reset/result.md`, but the task is at
   `.trellis/tasks/archive/2026-08/08-23-topnav-ui-data-reset/result.md`.
2. `backend/pom.xml` describes the completed backend as `ForgePilot V2 Phase 1
   foundation`.
3. The Markdown target audit found 151 broken links in 17 archived task
   documents:
   - 142 links from task-root documents need two additional `../` segments.
   - 9 links from one `research/` document need two additional `../` segments.
   - All affected links target `docs/v2/DECISIONS.md` except one link to
     `docs/v2/PRD.md`.
4. The first evaluation gate found 4 errors in
   `test_postfreeze_provider_correction.py`: `evaluation/formal/config.json`
   and the two post-freeze wrappers still resolve Phase 8 evidence at its
   pre-archive path. The config and wrappers are hashed by the immutable freeze
   and provider-correction records, so editing them would invalidate the
   experiment. A narrow relative evidence symlink is the only zero-copy fix
   that preserves those bytes and the frozen path contract.

The `(url)` link in `.claude/agents/trellis-research.md` is a documented
template placeholder, not a missing local file.

## Items intentionally retained

- Root, backend, frontend, and evaluation README files are scoped entry points,
  not copies of the seven V2 authority documents.
- `.agents/`, `.claude/`, `.pi/`, `.codex/`, and `.trellis/` contain managed
  adapters, workflow instructions, specs, and evidence used by supported
  developer tools.
- Archived task evidence is retained. Only broken link paths may change.
- `package-info.java` and textually single-referenced Spring controllers/config
  classes are runtime or architecture inputs, not dead code.
- Formal evaluation assets are immutable and excluded from cleanup.

## Static checks already run

- `npm run lint`: passed.
- `npm run typecheck -- --noUnusedLocals --noUnusedParameters`: passed.
- Backend dependency analysis could not run on the host because Java is absent;
  the documented Java 21 container path is required during execution.
- Source scan found no actionable TODO/FIXME/HACK marker.
