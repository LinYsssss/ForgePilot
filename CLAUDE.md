# Claude Code Entry Point

Read and follow `AGENTS.md` in full before doing any work. It is the shared ForgePilot instruction source for Claude Code, Codex, and Pi.

Then load the current Trellis context and follow the execution gate in `docs/v2/IMPLEMENTATION-PLAN.md`. **Phase 1 is complete and accepted as of 2026-08-21.** Authorization is now batched per `docs/v2/DECISIONS.md` D012: **batch 1 (Phase 2 + Phase 3) is authorized to enter task-level planning**; confirm its `prd.md`, `design.md`, and `implement.md`, then run `task.py start` before implementation. Stop at the batch 1 review gate. Batch 2 and later require separate authorization. The holdout set stays locked to Phase 8 and runs exactly once.
