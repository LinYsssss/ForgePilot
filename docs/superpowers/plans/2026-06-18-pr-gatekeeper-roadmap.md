# PR Gatekeeper Agent Delivery Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved PR gatekeeper design as four independently testable increments.

**Architecture:** Preserve the existing RepoSage review flow while adding a persistent Agent control plane, then SCM adapters and a sandbox data plane, and finally language plugins plus verified patch generation. Each phase must leave the application deployable and backward compatible.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, Flyway, RabbitMQ, PostgreSQL/pgvector, Vue 3, Docker Compose, Testcontainers, OpenTelemetry, Prometheus.

---

## Plan order

1. [Phase 1: Engineering Baseline](2026-06-18-pr-gatekeeper-phase-1-engineering-baseline.md)
2. [Phase 2: Agent Control Plane](2026-06-18-pr-gatekeeper-phase-2-agent-control-plane.md)
3. [Phase 3: SCM and Sandbox](2026-06-18-pr-gatekeeper-phase-3-scm-sandbox.md)
4. [Phase 4: Language Plugins, Patch Verification, and Evaluation](2026-06-18-pr-gatekeeper-phase-4-plugins-patch-evaluation.md)

## Execution precondition

The workspace currently contains substantial user-owned uncommitted changes, including authentication, MQ, Git, AI logging, PR workflow, frontend, configuration, and schema edits. Before implementation:

- Review and commit those changes as their own checkpoint. Do not start the Agent implementation in a dirty workspace.
- Do not create a worktree from `HEAD` until the current changes needed by the feature are committed; otherwise the implementation worktree will omit them.
- Never include `.m2home/`, Vite logs, generated `dist/`, credentials, or local runtime files in commits.

Required checkpoint:

```powershell
git status --short
cd backend
mvn -s .mvn/settings.xml test
cd ..\frontend
npm run build
cd ..
git diff --check
```

After the existing work is reviewed and committed, create an isolated feature worktree and record the checkpoint commit SHA in the implementation handoff.

## Architecture invariants

- PostgreSQL is authoritative; RabbitMQ and SSE are delivery mechanisms.
- State changes and MQ publication use a transactional outbox.
- Clean PRs and non-fixable findings skip Patch approval; only an applied candidate Patch enters `WAITING_APPROVAL`.
- The backend never runs repository-controlled commands and never receives a Docker socket.
- SCM credentials stay in the backend. The runner receives a sanitized archive or temporary object reference.
- Model output is untrusted and must pass schema, permission, path, size, budget, and tool-whitelist checks.
- Single-host Docker Compose is a controlled portfolio/demo boundary, not hostile multi-tenant isolation.

## Release gates

Each phase must pass:

```powershell
cd backend
mvn -s .mvn/settings.xml test

cd ..\frontend
npm run build

cd ..
.\scripts\verify-local.ps1 -SkipSmoke
```

Phase 3 onward must additionally pass Docker Compose integration checks. Phase 4 must publish evaluation metrics for the fixed benchmark corpus.

Initial release gates:

- High-risk recall `>= 0.80`.
- Finding precision `>= 0.70`.
- Valid code-location accuracy `>= 0.90`.
- No duplicate Agent Run for repeated delivery IDs.
- No secret in runner environment, tool output, logs, or persisted previews.
- Patch application success `>= 0.70` for repairable benchmark cases.

## Eight-week delivery schedule

This assumes one developer working consistently and extending the existing RepoSage codebase.

| Week | Deliverable | Exit condition |
| --- | --- | --- |
| 1 | Workspace checkpoint, reproducible builds, CI | Clean worktree; local and CI gates pass |
| 2 | Flyway, Testcontainers, regression coverage | Legacy upgrade and infrastructure tests pass |
| 3 | Agent persistence, state machine, budgets, typed tools | Transition and budget tests pass |
| 4 | Structured output, outbox, MQ idempotency, recovery, timeline | Restart and duplicate-delivery tests pass |
| 5 | GitHub/GitLab webhooks and SCM contracts | Signed fixtures create exactly one Agent Run |
| 6 | Sandbox runner, repository tools, dependency policy | Network, command, and path escape tests pass |
| 7 | Java/Python/JS plugins, evidence, hybrid context | Three language cases produce normalized findings |
| 8 | Patch validation, approval, evaluation, observability | Quality gates and end-to-end demo pass |

Reserve at least 20% of each week for integration failures, deployment differences, documentation, and demo rehearsal.

## Phase entry rules

Do not begin a phase until the previous phase:

- has a clean commit;
- passes its verification commands;
- has no unresolved Critical or Important review findings;
- has updated migration/configuration documentation;
- can be demonstrated independently.

Before each later phase begins, expand that phase into the same code-level TDD detail as Phase 1. Phases 2–4 define the required architecture, files, tests, and boundaries, but are milestone plans rather than line-by-line coding scripts.

## Scope cut line

If delivery slips, cut in this order:

1. GitLab publishing UI polish; retain GitLab webhook ingestion.
2. Automatic dependency preparation; retain prebuilt demo caches and `ENVIRONMENT_INCOMPLETE`.
3. Patch approval UI polish; retain API and Patch download.
4. Full OpenTelemetry dashboard; retain correlation IDs and Micrometer.
5. Advanced Python/TypeScript semantic context; retain static tools and tests.

Never cut webhook verification/idempotency, transactional outbox, sandbox restrictions, recovery, evidence requirements, stale-head Patch rejection, or the fixed evaluation corpus.

## Main schedule risks

| Risk | Early control |
| --- | --- |
| Existing changes conflict with migrations | Freeze and commit the checkpoint before Phase 1 |
| Build tools require internet | Separate dependency preparation from network-disabled tests |
| Model output is unstable | Structured schema, bounded repair, deterministic validation |
| MQ duplicates or publish loss | Transactional outbox and idempotency keys |
| Docker isolation is overstated | Treat runner as trusted and Compose as controlled-demo scope |
| Three languages become too broad | Share contracts; keep deepest semantic work in Java |
| Evaluation is postponed | Add benchmark fixtures with each plugin |
