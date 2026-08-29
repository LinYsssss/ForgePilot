# Claude Code Entry Point

Read and follow `AGENTS.md` in full before doing any work. It is the shared ForgePilot instruction source for Claude Code, Codex, and Pi.

ForgePilot is a requirement-driven AI code review platform: 9 backend packages, 21 tables across 13 Flyway migrations, a 6-entry / 11-route frontend, GitHub and GitLab providers, and a three-arm evaluation whose holdout was consumed exactly once. Both test suites are green with zero skips; read the count from a run rather than from a document.

Changes go through Trellis Plan -> Execute -> Finish, one verifiable task at a time, and must respect the boundaries in `docs/v2/README.md`. The formal evaluation assets — configuration freeze, corpus manifest, holdout ledger, raw outputs — are immutable: never delete, overwrite, or re-run them. To reproduce the application or the reports without credentials, follow `docs/v2/DEFENSE-GUIDE.md`.
