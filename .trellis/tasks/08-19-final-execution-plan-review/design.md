# Design: shared Trellis governance for ForgePilot V2

## Sources of truth

- `docs/v2/FINAL-EXECUTION-PLAN.md`: human review and phase authorization entry point.
- `docs/v2/PRD.md`: product behavior and acceptance authority.
- `docs/v2/ARCHITECTURE.md`: technical contracts and limits.
- `docs/v2/IMPLEMENTATION-PLAN.md`: phase order and exit gates.
- `docs/v2/adr/`: decision rationale.
- `docs/v2/LEGACY-MIGRATION-MATRIX.md`: RepoSage extraction policy.

## Platform mapping

| Tool | Native project entry | Shared authority |
|---|---|---|
| Claude Code | `.claude/` + `CLAUDE.md` | `AGENTS.md`, `docs/v2/`, `.trellis/` |
| Codex | `.codex/` + `.agents/skills/` + `AGENTS.md` | `docs/v2/`, `.trellis/` |
| Pi | `.pi/` + automatic `AGENTS.md` loading | `docs/v2/`, `.trellis/` |

Product rules are not copied into three platform folders. Platform folders only adapt Trellis lifecycle and context injection; ForgePilot decisions remain in the shared documents.

## Safety choices

- Trellis-managed content stays inside its managed blocks/files; ForgePilot-specific rules are outside the managed block in `AGENTS.md`.
- `session_auto_commit: false` prevents lifecycle scripts from committing without review.
- Codex uses inline dispatch by default; all platforms require explicit user approval before sub-agent work.
- The current task is a planning/review container, not an implementation task.
