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

- **Phase 0 through Phase 8 are all complete and gated as of 2026-08-22.** Per-phase acceptance evidence lives in `.trellis/tasks/archive/2026-08/<task>/result.md`. The repository is in the defense-preparation period: feature development is closed, not paused.
- Delivered: 8 backend business packages, 16 business tables across 7 Flyway migrations, 307 backend tests green; a Vue 3 frontend with exactly 3 top-level navigation entries and 7 product routes, 32 tests green; GitHub and GitLab providers; and a completed three-arm formal evaluation.
- Any further change still goes through Trellis Plan -> Execute -> Finish with one independently verifiable task active at a time. "Everything is done" is not a licence to edit the repository ad hoc.
- **The formal evaluation assets are immutable.** The configuration freeze, corpus manifest, holdout ledger, and raw outputs must never be deleted, overwritten, or re-run. The holdout ran exactly once, after freeze; re-running it or tuning against it permanently destroys the only unbiased estimate in the thesis. A different experiment requires a new case-set and configuration identity.
- The original freeze recorded the wrong provider endpoint and was corrected once by an independently content-addressed wrapper that changed only the endpoint. Preserve that record; never fold it into the original freeze or rewrite it to conceal the protocol deviation.
- D012's other non-relaxable rules remain in force for any future work: Phase 6's runtime bounds are measured outputs rather than pre-written constants, and D006's schema feedback loop still sends conflicts back to a decision instead of a compatibility branch in code.

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
