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

- Phase 0 and the R2.3 contract/document consolidation are complete as of 2026-08-20.
- **Phase 1 is authorized to enter task-level planning, not immediate implementation.** Create and obtain confirmation for its Trellis `prd.md`, `design.md`, `implement.md`, then run `task.py start` before editing application code.
- Phase 1 scope is defined in `docs/v2/IMPLEMENTATION-PLAN.md`: Spring Boot + Vue skeleton, PostgreSQL 15+ with pgvector, Flyway, Testcontainers, ArchUnit, basic CI, frontend scaffolding plus the visual contract, the evaluation contract skeleton, and the 4 GB deployment memory measurement.
- Do not implement login, project, requirement, knowledge, SCM, or review business logic in Phase 1.
- **Phase 2 and every later phase still require a separate explicit authorization.** Stop at each phase review gate; approval of the overall plan does not authorize every phase.

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
