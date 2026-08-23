# Claude Code Entry Point

Read and follow `AGENTS.md` in full before doing any work. It is the shared ForgePilot instruction source for Claude Code, Codex, and Pi.

**Phase 0 through Phase 8 are complete and gated as of 2026-08-22**, with two product completions on 2026-08-23 — D017 (six-entry product surface) and D018 (centered top navigation, single visible Logo per surface). Per-phase evidence is in `.trellis/tasks/archive/2026-08/<task>/result.md`. The delivered shape, re-verified on 2026-08-23, is 8 backend packages, 16 tables across 7 Flyway migrations, 316 green backend tests, a 6-entry / 10-route frontend with 35 green tests, both GitHub and GitLab providers, and a three-arm formal evaluation whose holdout was consumed exactly once. The repository is in the defense-preparation period: feature development is closed.

Two items that the R2.5 review first recorded as unfinished are now closed, each differently: PRD P1's DEVELOPER half of the PR-to-requirement association is **implemented**, and the vector index is a **decided non-goal** under D019 — the frozen 4096-dimension embedding profile exceeds every exact pgvector 0.8.6 index form, and the buildable ones are lossy pre-filters. Neither is a missing migration; do not re-open them as one. The remaining gaps are listed under 已知缺口 in `docs/v2/README.md`.

Further changes still go through Trellis Plan -> Execute -> Finish, one verifiable task at a time, and must respect the boundaries in `docs/v2/README.md`. The formal evaluation assets — configuration freeze, corpus manifest, holdout ledger, raw outputs — are immutable: never delete, overwrite, or re-run them. To reproduce the application or the reports without credentials, follow `docs/v2/DEFENSE-GUIDE.md`.
