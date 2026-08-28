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
4. `.trellis/workflow.md`

## Current shape

8 backend top-level packages, 20 business tables across 10 Flyway migrations, a 6-entry / 11-route frontend, GitHub and GitLab providers, and a completed three-arm evaluation. `./mvnw -B -ntp verify` and the frontend suite are green with zero skips. Quote the structural counts; they are asserted by tests. Do **not** quote a test-case count from any document — run the suite and read it, or it will be stale.

There is no JDK on this host. Backend builds and tests run through the pinned container path documented in `docs/v2/DEFENSE-GUIDE.md`.

## Product and architecture guardrails

- ForgePilot is a requirement-driven AI R&D collaboration and PR review platform, not Jira, a general chat product, a coding agent, or a full DevOps suite.
- There is exactly one Review Engine.
- Backend top-level packages are limited to `common`, `auth`, `project`, `requirement`, `scm`, `knowledge`, `ai`, and `review`.
- Agent, Patch, RabbitMQ/Outbox, Risk Model, Sandbox, a second AI runtime, and a second Review pipeline are out of scope.
- AI may produce requirement checks, one-shot implementation guidance, and review findings; it must not change business state or code automatically.
- `finding_key`, `evidence_hash` and `basis_hash` must never cover model prose. `explanation`, `suggestion` and `confidence` are the only outputs allowed to move with the model's wording, which is exactly why no hash may see them — a suppression that drifts with wording stops working silently, and the failure only surfaces a round later.
- `ReviewPrompts.VERSION` and `FindingKeys.RULE_VERSION` are different constants that move for different reasons. The first must be bumped whenever an instruction or output schema changes. The second must stay put unless a deterministic rule changes, because incrementing it rewrites every `basis_hash` and discards every inherited suppression.

## The formal evaluation is immutable

The configuration freeze, corpus manifest, holdout ledger, and raw outputs must never be deleted, overwritten, or re-run. The holdout ran exactly once, after freeze; re-running it or tuning against it permanently destroys the only unbiased estimate in the thesis. A different experiment requires a new case-set and configuration identity.

The original freeze recorded the wrong provider endpoint and was corrected once by an independently content-addressed wrapper that changed only the endpoint. Preserve that record; never fold it into the original freeze or rewrite it to conceal the protocol deviation.

The evaluation tool-chain does not call the backend, so changing prompts or schemas cannot invalidate the frozen results — but it also means those results prove that requirement and knowledge context helps, not that any particular retrieval pipeline works. Keep that distinction exact.

## Execution discipline

- Use Trellis Plan -> Execute -> Finish and keep one independently verifiable task active at a time.
- Complex implementation tasks require `prd.md`, `design.md`, and `implement.md`, followed by human review before `task.py start`.
- Do not spawn sub-agents unless the user explicitly authorizes delegation or parallel work.
- Do not push automatically. Present the planned commit grouping and obtain confirmation first.
