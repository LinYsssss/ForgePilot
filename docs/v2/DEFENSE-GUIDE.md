# ForgePilot defense reproduction guide

This guide reproduces the final application and recomputes the formal reports without SCM or model credentials. It assumes Docker with Compose v2, Git, Python 3.11+, and Node 24 only when running the frontend gates outside Docker.

## 1. Clean deployment

Start from a clean checkout. Do not copy a previous PostgreSQL volume into the checkout.

```bash
cp .env.example .env
```

Before using any real repository, replace the database password and `FORGEPILOT_SCM_SECRET_KEY` in the ignored `.env`. Chat and embedding values may remain blank for an empty-stack or deterministic-rescore demonstration. Never paste a provider credential into a command line, log, screenshot, or tracked file.

The automated cold-start proof creates a unique Compose project, builds both application images, creates a brand-new PostgreSQL volume, waits for all three health checks, verifies the reverse proxy, pgvector, Flyway, and the exact twenty-one business tables, then removes only that temporary project and volume:

```bash
FORGEPILOT_BACKEND_PORT=28080 \
FORGEPILOT_FRONTEND_PORT=28081 \
scripts/phase1-compose-smoke.sh forgepilot-phase1-defense-clean
```

Choose two unused loopback ports if those are occupied. For an interactive demonstration, use `docker compose up --build --detach --wait`, then open the configured frontend loopback address. The backend health contract is `/actuator/health`; through the frontend proxy it is `/api/actuator/health`.

The current schema is V14, with 21 business tables. V14 adds only a nullable
requirement reviewer and its project-member foreign key; existing null assignments
fall back to LEADER. Upgrade an existing deployment with its database volume retained,
never with the disposable cold-start cleanup procedure. On 2026-09-08, commit
`746e40b` passed the full CI suite and two fresh-volume Compose cold starts, then
was deployed to the existing `fp-demo` stack. V14 applied successfully; the original
PostgreSQL container, volume and baseline record counts were retained. Container,
loopback and public health checks passed, and served assets matched the running
frontend image. See the [deployment record](../../.trellis/tasks/archive/2026-09/09-07-review-collaboration/deployment.md)
and current test report for the exact evidence and remaining manual checks.

## 2. Build and test gates

The backend requires Java 21. On a host without a JDK, use the pinned container path:

```bash
docker run --rm --network host \
  -v "$PWD/backend:/workspace" \
  -v "$HOME/.m2:/root/.m2" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk \
  ./mvnw -B -ntp verify
```

Frontend and evaluation gates:

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm test -- --run
npm run build

cd ..
python3 -m unittest \
  evaluation/tools/test_run_development.py \
  evaluation/tools/test_formal_evaluation.py \
  evaluation/tools/test_postfreeze_legacy_adapter.py \
  evaluation/tools/test_postfreeze_provider_correction.py
python3 evaluation/tools/score.py --selftest
python3 evaluation/tools/score.py --guard-no-holdout --root evaluation
```

The last guard is for a source checkout. A machine holding the ignored post-freeze private corpus will intentionally fail that pre-holdout guard and should instead verify the configuration freeze and corpus integrity.

## 3. Demonstration path

Use three disposable accounts and one disposable project. The signed-in shell has six top-level entries — Workspace, Projects, Requirements, Project Knowledge, Repository Integration, Reviews — in one centered top application bar.

1. A LEADER creates the project and requirement, adds DEVELOPER and REVIEWER members, assigns the developer and reviewer, uploads project knowledge on **Project Knowledge**, and configures either GitHub or GitLab on **Repository Integration** (`/repositories`; the legacy `/projects/:id/settings` path redirects there). Tokens and webhook secrets are write-only. GitLab's repository token needs `api` and actual merge permission.
2. A merge/pull request webhook authenticates the untouched body, triggers an authoritative provider read, links `REQ-<id>` when present, and creates the shared PENDING Review.
3. The assigned REVIEWER or LEADER returns the completed Review with a reason. Show it on the requirement and Review pages; the PR/MR and original branch remain open for developer updates. Other REVIEWER members can handle findings but cannot make this final decision.
4. The DEVELOPER fixes the code and updates the same branch. Show the new round, previous return reason and author mapping, then explicitly approve a dedicated disposable PR/MR: this merges the reviewed SHA. The requirement must be in development with a valid developer, and DONE remains a separate LEADER action.
5. Read/download public knowledge as a member. Show the distinction between its current original and a historical Review excerpt. If a test DingTalk channel is enabled, demonstrate after-commit AI/decision notifications with responsible names and `/reviews/{id}?project={projectId}` links; configure `FORGEPILOT_BASE_URL` for links.

These are manual acceptance steps, not claims of a live merge or notification in
the 2026-09-08 validation. On `merge_outcome_unknown`, verify the remote merge state
before deciding how to retry; no background compensation is provided.

One thing to state honestly if asked during the walkthrough: semantic knowledge retrieval runs without a vector index. That is a deliberate choice, not an oversight — the frozen 4096-dimension embedding profile exceeds every exact index form pgvector 0.8.6 offers, and the two buildable forms are lossy pre-filters that would need a rerank stage. At MVP corpus scale the sequential scan returns the exact cosine ordering, which `KnowledgeVectorIndexTest` demonstrates at the frozen dimension. None of this affects the recorded evaluation, whose runner builds its own context and never calls the running application's retrieval path.

GitHub receives webhooks at `/api/scm/github/webhook`. GitLab receives them at `/api/scm/gitlab/webhook`; current GitLab uses a `whsec_` signing token, while older instances may use their legacy secret token. Do not expose either value during the demonstration.

## 4. Formal evidence and deterministic rescore

The tracked original configuration freeze identifies the model, original endpoint identity, prompt/schema, runner, scorer, aliases, contracts, source commit, and retry/timeout policy by content hash. The available credential belonged to a third-party OpenAI-compatible service, so the original public endpoint failed with HTTP 401. A separate content-addressed correction records the effective endpoint and explicitly states that it was made after corpus import but before any holdout ledger or provider call. The private normalized corpus and raw model envelopes live in the ignored evaluation workspace on the evidence machine.

Verify that no model-facing or scoring file changed:

```bash
python3 evaluation/tools/formal_evaluation.py verify-freeze
python3 evaluation/tools/postfreeze_provider_correction.py verify
```

The first command proves the original model-facing and scoring files are unchanged. The second binds the effective endpoint while rejecting any model, temperature, prompt/scorer, corpus, or split change. `evidence/formal-run-evidence.json` records both hashes and the effective provider identity. The canonical summary JSON intentionally retains the original frozen config; consult that evidence record for the endpoint actually used.

Recompute development, holdout, and full-corpus metrics plus 95% Wilson intervals from the preserved raw envelopes. This command performs no provider call and does not read `OPENAI_API_KEY`:

```bash
python3 evaluation/tools/formal_evaluation.py report \
  --out-dir /tmp/forgepilot-formal-rescore
```

Compare the resulting `formal-summary.json` and per-arm score files with the preserved artifact hashes. Failed and `NOT_RUN` cases remain explicit; the tool never converts them into empty successful predictions. The report deliberately has no composite score and states that the 12-case holdout and hand-constructed demonstration defects limit generalization.

State one more scope boundary honestly, because the arm names invite a stronger reading than the experiment supports. The third arm supplies **every knowledge file of the case verbatim** — `run_development.py` reads them from the case fixture on disk and renders them all into the prompt. It performs no embedding call, no TopK retrieval, and never touches the running application. So the measured effect is *"project knowledge in context helps the model find requirement violations"*, **not** *"ForgePilot's pgvector retrieval helps"*. The retrieval path is a product capability with its own tests; it is not what these numbers evaluate. Claiming otherwise would over-read the experiment.

## 5. Secret and cleanup rules

- `.env`, the private corpus, and formal runtime outputs are ignored by Git.
- The freeze stores only `apiKeyPresent: true/false`, never a key or authorization header.
- Do not archive Docker inspection output or environment dumps as evidence.
- Remove disposable provider webhooks and rotate their tokens after the defense.
- `docker compose down --volumes` is appropriate only for the explicitly disposable defense project; never point it at a deployment whose database must be retained.
