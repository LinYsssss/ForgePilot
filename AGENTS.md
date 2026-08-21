<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->

# ForgePilot Project Rules

The block above is managed by Trellis. The rules below are ForgePilot-owned and must be preserved by future Trellis updates.

## Mandatory reading order

Before planning or changing this repository, read these files completely:

1. `docs/v2/README.md`
2. `docs/v2/PRD.md`
3. `docs/v2/ARCHITECTURE.md`
4. `docs/v2/IMPLEMENTATION-PLAN.md`
5. `docs/v2/DECISIONS.md`
6. `docs/v2/LEGACY-MIGRATION-MATRIX.md` before consulting Legacy code
7. `.trellis/workflow.md`

## Current execution gate

- Phase 0, the R2.3 contract/document consolidation, **Phase 1 (minimal greenfield foundation)** and **batch 1 (Phase 2 + Phase 3)** are complete. Phase 1 was accepted on 2026-08-21; batch 1 completed on 2026-08-21 with evidence in `.trellis/tasks/08-21-batch-1-auth-project-requirement/result.md`.
- Authorization is **batched** per `docs/v2/DECISIONS.md` D012: batch 1 = Phase 2+3, batch 2 = Phase 4+5, batch 3 = Phase 6+7, Phase 8 alone and last.
- **Batch 2 (Phase 4 + Phase 5) is authorized under `docs/v2/DECISIONS.md` D014**, which records that the user delegated the per-batch review gate to the orchestrating session. The gate itself is not removed: before opening a batch, the previous batch's `result.md` must show a green build with no skips, a passing Compose cold start, all CI jobs green, the boundary checks clean, and every partial pass honestly recorded as partial. If any of those fails, stop.
- D012's three non-relaxable rules survive D014 untouched: the holdout stays locked to Phase 8 and runs once, Phase 6's runtime bounds are measured outputs rather than pre-written constants, and D006's schema feedback loop still sends conflicts back to a decision instead of a compatibility branch in code.
- What batch 1 delivered: local accounts with in-process sessions and cookie CSRF, Project/ProjectMember with exactly one LEADER, project-level SCM identity, Requirement/AC with immutable Revisions and stable `ac_key`, and the login, project, member and requirement screens. Six of the sixteen tables exist; the other ten arrive with the phase that uses them.
- **The holdout set stays locked to Phase 8 and runs exactly once, after configuration freeze.** Running it early, running it repeatedly, or tuning against it permanently destroys the only unbiased estimate in the thesis and cannot be undone by re-running.

## Product and architecture guardrails

- ForgePilot is a requirement-driven AI R&D collaboration and PR review platform, not Jira, a general chat product, a coding agent, or a full DevOps suite.
- There is exactly one Review Engine.
- Backend top-level packages are limited to `common`, `auth`, `project`, `requirement`, `scm`, `knowledge`, `ai`, and `review`.
- Agent, Patch, RabbitMQ/Outbox, Risk Model, Sandbox, a second AI runtime, and a second Review pipeline are outside the V2 mainline.
- AI may produce requirement checks, one-shot implementation guidance, and review findings; it must not change business state or code automatically.

## Legacy boundary

The legacy implementation is in `https://github.com/LinYsssss/reposage` and is read-only reference material.

- Check `docs/v2/LEGACY-MIGRATION-MATRIX.md` before reading or extracting Legacy code.
- Never copy a Legacy package wholesale or inherit its Flyway history.
- `KEEP` still means migrate tests and the smallest proven implementation, not copy surrounding architecture.
- Keep `DROP` assets out of ForgePilot even when they still run in RepoSage.

## Execution discipline

- Use Trellis Plan -> Execute -> Finish and keep one independently verifiable task active at a time.
- Complex implementation tasks require `prd.md`, `design.md`, and `implement.md`, followed by human review before `task.py start`.
- Do not spawn sub-agents unless the user explicitly authorizes delegation or parallel work.
- Use PowerShell 7 (`pwsh`) for PowerShell commands.
- Do not push automatically. Present the planned commit grouping and obtain confirmation first.
