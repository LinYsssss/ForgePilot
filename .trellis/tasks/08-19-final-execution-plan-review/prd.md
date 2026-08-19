# Review ForgePilot V2 final execution plan

## Goal

Review and approve the consolidated V2 scope, architecture boundaries, migration policy, phases, and implementation gates before any application code is written.

## Requirements

- Initialize official Trellis assets for Claude Code, Codex, and Pi with developer identity `LinYsssss`.
- Preserve one shared project rule source and one shared Trellis task/spec/workspace system across all three tools.
- Consolidate the existing product, architecture, ADR, implementation, and Legacy migration conclusions into one review entry document.
- Mark all product documents as candidates awaiting explicit user approval.
- Keep the repository free of application source code, dependency manifests, database migrations, frontend scaffolding, and CI implementation.
- Leave this task in `planning`; approval of this PRD is not approval to start Phase 1.

## Non-goals

- Implementing any ForgePilot feature or engineering scaffold.
- Modifying RepoSage or migrating Legacy code.
- Starting, completing, or archiving the Phase 1 implementation task.
- Enabling automatic commits, pushes, or unapproved sub-agent dispatch.

## Acceptance Criteria

- [x] `trellis platforms` reports Claude Code, Codex, and Pi.
- [x] Trellis developer identity resolves to `LinYsssss`.
- [x] Root instructions route all three tools to the same final plan and review gate.
- [x] `docs/v2/FINAL-EXECUTION-PLAN.md` covers final scope, cuts, architecture limits, phases, tests, Legacy extraction, and approval rules.
- [x] PRD, Architecture, Implementation Plan, README, and AI handoff no longer claim implementation is authorized.
- [x] Trellis automatic commits are disabled and Codex dispatch defaults to inline.
- [ ] User reviews the 12 final decisions and explicitly approves or requests revisions.
- [ ] Only after approval may a separate Phase 1 planning task be created.

## Notes

- Review artifact: `docs/v2/FINAL-EXECUTION-PLAN.md`.
- This task intentionally remains `planning`; do not run `task.py start` merely to mark documentation work complete.
