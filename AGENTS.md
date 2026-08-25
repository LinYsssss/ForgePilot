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

- **Phase 0 through Phase 8 are complete and gated as of 2026-08-22.** Per-phase acceptance evidence lives in `.trellis/tasks/archive/2026-08/<task>/result.md`.
- Two product completions followed on 2026-08-23, both implemented and validated: D017 (`08-23-product-flow-completion`, six approved entries plus the minimal supporting APIs) and D018 (`08-23-topnav-ui-data-reset`, centered top navigation, single visible Logo per surface, and one explicitly authorized deployment-data reset that preserved `ysainlin`). Neither added a table, a top-level package, a global-admin model, or a schema change.
- The later `08-23-requirement-document-access` task minimally completed `.txt/.md` Requirement document reading/download and structured Markdown export while reusing the existing scoped Guidance retrieval. It added no table, migration, dependency, top-level frontend route, or AI runtime.
- **Current delivered shape, re-verified on 2026-08-25**: 8 backend top-level packages, 20 business tables across 10 Flyway migrations, `./mvnw -B -ntp verify` green at 331 tests with zero skips, a 6-entry / 11-route frontend with 37 green tests, GitHub and GitLab providers, and a completed three-arm formal evaluation. Quote these numbers, not the older 323 / 19-table / 9-migration / 35-test figures, and not the much older 317 / 316 / 16-table / 10-route ones.
- D021 appends `V9__finding_explanation.sql`, which adds `category`, `explanation`, `suggestion` and `confidence` to `finding`. It adds no table. Two version constants are involved and they move in opposite directions: `ReviewPrompts.VERSION` had to become `"review-2"` because its own contract requires a bump whenever an instruction or schema changes, while `FindingKeys.RULE_VERSION` stays `"1"` because no deterministic rule changed. None of the four new columns may ever enter `finding_key`, `evidence_hash` or `basis_hash`.
- **Two items that R2.5 first recorded as unfinished have since been closed, each in its own way**: PRD P1's DEVELOPER half of the PR-to-requirement association is now **implemented** (author-only, gated per head by `PullRequestDecisionGate` — an interface in `scm` implemented in `review`, so the compile-time edge stays `review → scm`), and the vector index is now a **decided non-goal** (D019: the frozen 4096-dimension profile exceeds every exact pgvector 0.8.6 index form; the buildable ones are lossy pre-filters). Do not re-open either as a "missing migration". Remaining gaps are listed in `docs/v2/README.md` under 已知缺口.
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
