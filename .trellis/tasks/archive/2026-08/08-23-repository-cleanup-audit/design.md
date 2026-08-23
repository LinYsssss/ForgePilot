# Repository cleanup technical design

## Boundaries

This task changes documentation paths and project metadata only. Product code,
API contracts, database schema, runtime configuration, and evaluation logic are
read-only. Ignored workspace cleanup is performed only on four exact,
pre-resolved directories; no glob, repository-wide clean, or volume deletion is
allowed.

## Link repair

The broken links share one cause: task directories moved from
`.trellis/tasks/<task>/` to `.trellis/tasks/archive/2026-08/<task>/`.

- In the 16 affected task-root Markdown files, replace
  `../../../docs/v2/` with `../../../../../docs/v2/` in Markdown targets.
- In the affected `research/finding-constraint-trigger-measured.md`, replace
  `../../../../docs/v2/` with `../../../../../../docs/v2/` in Markdown
  targets.
- Do not rewrite prose, code blocks, results, or links that already resolve.
- Validate every tracked Markdown link target after the rewrite.

## Protected assets

Before mutation, record secret-safe fingerprints for the exact protected
evaluation trees and stat metadata for `.env`. Recheck after all edits and
cleanup. The validation commands must not read or print secret contents and
must never call a provider or the formal `run` command.

The formal config and post-freeze wrappers are content-addressed by the freeze
and correction records. Their pre-archive evidence paths therefore cannot be
rewritten safely. A real compatibility directory is created at
`.trellis/tasks/08-22-phase-8-gitlab-evaluation-defense/` with only one relative
`evidence` symlink to
`../archive/2026-08/08-22-phase-8-gitlab-evaluation-defense/evidence`. It has no
`task.json`, so Trellis active-task discovery ignores it. This restores the
frozen filesystem contract without copying or changing evidence.

## Workspace cleanup order

1. Preserve fingerprints/stat metadata.
2. Apply tracked documentation and metadata fixes.
3. Add and verify the narrow Phase 8 evidence compatibility link.
4. Run all validation while dependencies/build output are present.
5. Delete exact ignored targets using individually named paths.
6. Confirm the targets are absent and protected assets unchanged.

This order avoids reinstalling dependencies after cleanup and makes every
deletion recoverable by a normal build/install, except the old visual study.
That study is untracked, already superseded, and authorized for removal by the
user's instruction to resolve every reported issue.

## Rollback

- Tracked edits can be reverted file-by-file from Git if validation fails.
- `backend/target/`, `frontend/node_modules/`, and `frontend/dist/` are restored
  by the documented build/install commands.
- The old visual study is not part of Git. Its removal is therefore delayed
  until after all tracked fixes and validations succeed.
- No cleanup command may target `.env`, `evaluation/`, a workspace root,
  Docker volumes, or an unresolved variable.
