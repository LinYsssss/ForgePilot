# PR Gatekeeper Phase 5 LangChain4j Agent and RAG Integration Plan

> This plan extends the completed Phase 1-4 codebase. Implement it task by task with TDD. Every task must be an independent commit and must run the backend, frontend, and sandbox-runner regression gates.

**Goal:** Integrate LangChain4j into RepoSage without replacing the deterministic Agent control plane, connect the existing Hybrid Review Context and pgvector RAG to real Agent execution, and complete a verifiable Webhook-to-SCM review loop.

**Architecture:** LangChain4j is an AI integration adapter for chat models, embeddings, structured messages, and bounded tool requests. PostgreSQL, the existing Agent state machine, transactional Outbox, RabbitMQ, AgentToolRegistry, evidence rules, sandbox policy, Patch approval, and SCM publication remain authoritative. A custom LangChain4j retriever delegates to the existing ReviewContextService so project isolation, hybrid ranking, source references, and byte budgets are preserved.

**Tech Stack:** Java 17, Spring Boot 3.5, LangChain4j, OpenAI-compatible Chat and Embedding APIs, PostgreSQL 16 with pgvector, RabbitMQ, Flyway, WireMock, Testcontainers, Docker Compose, OpenTelemetry, Prometheus, Vue 3.

---

## 1. Verified starting point

The implementation must begin from current source evidence, not from the earlier completion summary:

- backend/pom.xml contains no LangChain4j or Spring AI dependency.
- AgentStepHandler currently returns only Agent step accepted plus the message identity; it does not execute the state-specific Agent workflow.
- StructuredAgentModelService and AgentModelClient exist, but no production component invokes them.
- ReviewContextService and HybridContextRanker implement deterministic hybrid retrieval, but ReviewContextService currently has no production caller.
- RagService already supports full-context retrieval, in-memory cosine retrieval, and PostgreSQL pgvector retrieval.
- OpenAiCompatibleEmbeddingClient already supports a real OpenAI-compatible embeddings endpoint.
- The Agent state machine, Outbox, RabbitMQ dispatch, recovery, timeline, sandbox tools, language plugins, evidence, confidence, Patch validation, approval, SCM publication, evaluation, and observability foundations already exist and must be reused.
- Flyway V1-V13 already exist. V1-V4 are frozen. No existing migration may be edited; the first new migration is V14.
- Docker and the three skipped Testcontainers tests are still unverified on the current host. They cannot be recorded as passed until executed on a Docker-capable host.

This phase is not complete if it only adds LangChain4j dependencies or adapter unit tests. Completion requires the real Agent step handler to execute the persisted workflow and requires dynamic end-to-end evidence.

---

## 2. Architectural decisions

### 2.1 Responsibilities

LangChain4j may:

- Build provider-neutral chat and embedding requests.
- Map system, user, assistant, tool-request, and tool-result messages.
- Expose model token usage and finish reasons.
- Represent tool specifications and tool requests.
- Provide a ContentRetriever adapter boundary.
- Call configured OpenAI-compatible providers.

LangChain4j must not:

- Own Agent Run state transitions.
- Publish RabbitMQ messages directly.
- Execute a Java method annotated as a tool without AgentToolRegistry validation.
- Execute arbitrary shell commands or repository-provided command strings.
- Decide project authorization, tool approval, retry policy, budgets, blocking gates, or Patch publication.
- Persist raw secrets, hidden chain-of-thought, or unrestricted private repository content.
- Automatically submit, push, merge, or publish a Patch.

### 2.2 Tool-calling strategy

Do not use an autonomous AI Services loop that invokes tool methods directly.

Use an explicit backend-owned loop:

1. The state-specific step executor selects the tools allowed for that Agent state.
2. LangChain4j receives only those tool specifications.
3. A returned tool request is schema-validated and checked against the current Review Plan.
4. AgentToolRegistry performs risk, approval, byte-limit, idempotency, and budget checks.
5. The ToolInvocation is persisted before and after execution.
6. The sanitized tool result is appended to the next model request.
7. The loop stops on a valid final response, budget exhaustion, cancellation, timeout, invalid output, or the configured iteration limit.

Approval-required tools are not included in model-visible tool specifications until the approval state permits them.

### 2.3 RAG strategy

Do not create a second independent vector knowledge base.

- Keep KnowledgeChunk, RagService, PgVectorIndexService, and PostgreSQL pgvector as the authoritative knowledge store.
- Implement a LangChain4j EmbeddingModel-backed EmbeddingClient adapter so existing ingestion and search can use LangChain4j.
- Implement a custom LangChain4j ContentRetriever backed by ReviewContextService.
- Preserve project and optional document scoping before any content reaches the model.
- Preserve hybrid weights: vector 0.40, lexical 0.25, changed-symbol 0.20, document-type 0.15.
- Preserve source version, exact source/chunk reference, untrusted marker, threshold, deduplication, and UTF-8 byte budget.
- Never treat retrieved instructions as trusted system instructions.

### 2.4 Compatibility and rollout

- Add an explicit runtime switch with legacy and langchain4j modes.
- Keep the current review flow available until the LangChain4j Agent path passes evaluation and end-to-end gates.
- Default local and test profiles to deterministic mock behavior.
- Fail fast in production when langchain4j mode is selected without required provider configuration.
- Do not silently fall back from a failed real provider to mock output.
- Persist provider, model, prompt version, embedding version, and token usage so runs remain auditable.

---

## 3. Target execution flow

    GitHub or GitLab Webhook
              |
              v
    Verified and idempotent Agent Run
              |
              v
    Existing state machine and RabbitMQ step dispatch
              |
              v
    State-specific AgentStepExecutor
              |
              +--> repository and sandbox tools through AgentToolRegistry
              |
              +--> ReviewContextService
              |       |
              |       +--> RagService
              |               |
              |               +--> LangChain4j EmbeddingModel adapter
              |               +--> memory cosine or PostgreSQL pgvector
              |
              +--> LangChain4j chat adapter
              |       |
              |       +--> bounded tool requests
              |       +--> structured final response
              |
              v
    Evidence, confidence, verification, and deterministic gate
              |
              v
    Candidate Patch and sandbox validation when eligible
              |
              v
    Human approval when required
              |
              v
    Existing GitHub or GitLab publication adapter

---

## 4. Common quality gate for every task

Every task below must start with a failing focused test, make the smallest production change that satisfies the task, then refactor while green.

After the focused tests, run all of:

    cd backend
    mvn -s .mvn/settings.xml test

    cd ..\frontend
    npm test
    npm run build

    cd ..\sandbox-runner
    mvn -s .mvn/settings.xml test

    cd ..
    git diff --check

Rules:

- Record exact counts, failures, errors, and skipped tests in docs/PR守门Agent实施进度.md.
- A Docker or Testcontainers skip remains a skip; do not describe it as a pass.
- Do not commit target, dist, evaluation/results, secrets, local model responses, or credentials.
- Each task gets exactly one focused implementation commit unless a failing external gate requires a separate documented fix.
- No task may modify Flyway V1-V13. Schema changes start at V14.

---

### Task 1: Freeze the integration contract and dependency baseline

**Files:**

- Modify backend/pom.xml.
- Create backend/src/test/java/com/example/codereview/ai/langchain4j/LangChain4jDependencyCompatibilityTest.java.
- Create backend/src/main/java/com/example/codereview/ai/langchain4j/LangChain4jRuntime.java.
- Modify backend/src/main/resources/application.yml.
- Modify backend/src/test/resources/application-test.yml if present.

- [x] Add a failing compatibility test proving the chosen LangChain4j version supports Java 17 and the project Spring Boot version.
- [x] Pin one explicit LangChain4j version through dependency management; do not use a floating range or latest alias.
- [x] Add only the modules needed for the selected OpenAI-compatible chat/embedding integration and core message/tool/retriever APIs.
- [x] Run Maven dependency tree and reject a second logging implementation, incompatible Jackson override, or conflicting HTTP stack.
- [x] Introduce app.ai.runtime with allowed values legacy and langchain4j.
- [x] Keep test behavior deterministic and make production langchain4j configuration fail fast when URL, key, or model is absent.
- [x] Document the exact selected LangChain4j version, modules, Java requirement, and known API names in the test and progress document.
- [x] Commit with: build: add pinned langchain4j integration boundary

**Exit evidence:**

- The application context starts in legacy and langchain4j mock profiles.
- An invalid runtime value or incomplete real-provider configuration fails during startup with a bounded, secret-free message.

### Task 2: Implement the LangChain4j chat-model adapter

**Files:**

- Create backend/src/main/java/com/example/codereview/ai/langchain4j/LangChain4jModelConfiguration.java.
- Create backend/src/main/java/com/example/codereview/ai/langchain4j/LangChain4jAgentModelClient.java.
- Modify backend/src/main/java/com/example/codereview/agent/model/AgentModelClient.java only if provider-neutral response metadata must expand.
- Modify backend/src/main/java/com/example/codereview/agent/model/StructuredAgentModelService.java.
- Create backend/src/test/java/com/example/codereview/ai/langchain4j/LangChain4jAgentModelClientTest.java.
- Create or extend WireMock provider contract fixtures.

- [x] First test system/user message ordering, model selection, temperature, timeout, response text, finish reason, and token usage mapping.
- [x] Test OpenAI-compatible base URLs with and without a trailing slash.
- [x] Test 401, 429, 5xx, malformed JSON, timeout, empty choices, and provider responses that omit token usage.
- [x] Map retryable provider failures separately from invalid model output and permanent authentication/configuration failures.
- [x] Preserve the existing AgentModelClient boundary so Agent state and persistence do not depend on LangChain4j classes.
- [x] Keep bounded one-attempt JSON repair in StructuredAgentModelService; the repair call consumes model-call and token budget.
- [x] Persist provider, model, input/output token counts, response hash, prompt schema version, latency, and terminal status.
- [x] Never persist API keys, authorization headers, complete private prompts, or chain-of-thought.
- [x] Add Micrometer and OpenTelemetry observations with bounded tags only.
- [x] Commit with: feat: adapt agent model calls through langchain4j

**Exit evidence:**

- WireMock proves the exact request contract and failure classification.
- Existing mock/legacy model tests still pass.

### Task 3: Implement the LangChain4j embedding adapter and versioned embeddings

**Files:**

- Create backend/src/main/java/com/example/codereview/ai/langchain4j/LangChain4jEmbeddingClient.java.
- Modify backend/src/main/java/com/example/codereview/rag/EmbeddingClient.java if metadata needs a provider-neutral result record.
- Modify backend/src/main/java/com/example/codereview/rag/RagService.java.
- Modify backend/src/main/java/com/example/codereview/knowledge/KnowledgeService.java.
- Create backend/src/main/resources/db/migration/V15__embedding_model_metadata.sql (V14 is used by Task 2 model-call audit metadata).
- Create backend/src/test/java/com/example/codereview/ai/langchain4j/LangChain4jEmbeddingClientTest.java.
- Extend RagService and knowledge ingestion tests.

- [x] Start with tests for real numeric embedding mapping, empty input, provider error, dimension mismatch, NaN/infinite values, and maximum input size.
- [x] Add nullable legacy metadata for embedding provider, model, dimension, and version without editing V1.
- [x] Mark existing rows as legacy or unknown; do not falsely label mock vectors as real-model vectors.
- [x] Persist metadata atomically with each indexed chunk.
- [x] Reject search across incompatible embedding model/version/dimension instead of silently returning meaningless cosine scores.
- [x] Define a re-index operation that is project-scoped, idempotent, resumable, and does not expose one project's chunks to another.
- [x] Keep mock embeddings available only for deterministic tests and explicitly configured demonstrations.
- [x] Preserve both memory and pgvector retrieval modes.
- [x] Verify deletion removes both chunk metadata and pgvector rows.
- [x] Commit with: feat: index versioned embeddings through langchain4j

**Exit evidence:**

- A real-provider WireMock fixture indexes and retrieves known vectors.
- Mixed mock/real or wrong-dimension data is rejected with an explicit re-index-required result.

### Task 4: Expose the existing Hybrid Review Context as a LangChain4j retriever

**Files:**

- Create backend/src/main/java/com/example/codereview/ai/langchain4j/LangChain4jReviewContentRetriever.java.
- Create backend/src/main/java/com/example/codereview/context/ReviewRetrievalQuery.java if needed to avoid framework types in the domain.
- Modify backend/src/main/java/com/example/codereview/context/ReviewContextService.java.
- Modify backend/src/main/java/com/example/codereview/context/HybridContextRanker.java only when tests expose a correctness defect.
- Create backend/src/test/java/com/example/codereview/ai/langchain4j/LangChain4jReviewContentRetrieverTest.java.
- Extend backend/src/test/java/com/example/codereview/context/ReviewContextServiceTest.java.

- [x] Test changed paths, symbols, imports, annotations, strings, and tool rule IDs are carried into retrieval.
- [x] Test project scope is mandatory and document scope cannot widen it.
- [x] Test the adapter returns no cross-project content even when chunk IDs or references collide.
- [x] Test vector, lexical, symbol, and document-type scores retain their deterministic weights.
- [x] Test thresholding, normalized-content deduplication, stable ordering, top-K, and UTF-8 byte budget.
- [x] Map source name, chunk index, document type, source version, score, and untrusted marker into LangChain4j Content metadata.
- [x] Preserve the exact citation reference used by Finding evidence and SCM output.
- [x] Reject retrieval calls missing project ID, source version, or bounded budget.
- [x] Do not use a global EmbeddingStoreContentRetriever that bypasses project/document isolation.
- [x] Commit with: feat: expose hybrid review context to langchain4j

**Exit evidence:**

- Contract tests prove LangChain4j receives the same scoped, ranked, cited evidence as the existing domain service.

### Task 5: Build injection-resistant prompt and citation assembly

**Files:**

- Create backend/src/main/java/com/example/codereview/agent/prompt/AgentPromptAssembler.java.
- Create backend/src/main/java/com/example/codereview/agent/prompt/PromptTemplateRegistry.java.
- Create versioned prompt resources under backend/src/main/resources/prompts/agent/.
- Modify backend/src/main/java/com/example/codereview/agent/model/PromptEnvelope.java.
- Create backend/src/test/java/com/example/codereview/agent/prompt/AgentPromptAssemblerTest.java.
- Add prompt-injection fixtures under evaluation/cases/.

- [ ] Test system policy, task instruction, changed code, tool evidence, and retrieved knowledge occupy distinct delimited sections.
- [ ] Mark repository text, comments, diffs, tool logs, and RAG documents as untrusted data.
- [ ] Test instructions embedded in code or documents cannot add tools, change project scope, request secrets, disable evidence rules, or authorize publication.
- [ ] Apply independent byte/token budgets to diff, code context, tool output, and RAG context.
- [ ] Use deterministic truncation that retains source references and records which sections were truncated.
- [ ] Require every knowledge-backed claim to reference one or more supplied citation IDs.
- [ ] Reject unknown, duplicated, or fabricated citation IDs during structured-output validation.
- [ ] Version every prompt and persist the version/hash, not an unrestricted full private prompt.
- [ ] Add redaction tests for tokens, passwords, private keys, authorization headers, and environment variables.
- [ ] Commit with: feat: assemble cited agent prompts safely

**Exit evidence:**

- Prompt-injection evaluation cases cannot broaden tools or bypass approval.
- Structured findings with fabricated citations are rejected.

### Task 6: Replace the placeholder handler with typed state executors

**Files:**

- Refactor backend/src/main/java/com/example/codereview/agent/queue/AgentStepHandler.java.
- Create backend/src/main/java/com/example/codereview/agent/orchestration/AgentStepExecutor.java.
- Create backend/src/main/java/com/example/codereview/agent/orchestration/AgentStepExecutorRegistry.java.
- Create backend/src/main/java/com/example/codereview/agent/orchestration/AgentStepResult.java.
- Create one executor class per active AgentRunStatus under agent/orchestration/steps/.
- Create backend/src/test/java/com/example/codereview/agent/orchestration/AgentStepExecutorRegistryTest.java.
- Extend AgentStepExecutionService and recovery tests.

- [ ] Start with a failing test proving AgentStepHandler no longer returns a generic accepted string.
- [ ] Require exactly one executor for every executable state and reject duplicate or missing registrations at startup.
- [ ] Make executor input/output provider-neutral, bounded, JSON-serializable, and versioned.
- [ ] Persist enough output to resume the next state without rerunning a successful external side effect.
- [ ] Keep AgentStepExecutionService responsible for locks, attempts, retry classification, metrics, and terminal failure.
- [ ] Keep state transitions in AgentRunTransitionService and AgentStateMachine; executors return outcomes and never mutate status ad hoc.
- [ ] Verify cancellation before model, retrieval, tool, sandbox, and SCM calls.
- [ ] Verify duplicate RabbitMQ deliveries do not repeat completed model/tool/publication work.
- [ ] Classify invalid model output, budget exhaustion, environment incomplete, security violation, retryable provider error, and permanent provider error separately.
- [ ] Commit with: feat: dispatch persisted agent steps by state

**Exit evidence:**

- Every nonterminal state has an executable, tested handler.
- Recovery tests resume from persisted state-specific output.

### Task 7: Implement planning and the bounded LangChain4j tool loop

**Files:**

- Create or modify planning and executing-tools state executors.
- Create backend/src/main/java/com/example/codereview/agent/orchestration/AgentToolLoop.java.
- Create backend/src/main/java/com/example/codereview/agent/model/LangChainToolSchemaMapper.java.
- Modify backend/src/main/java/com/example/codereview/agent/plan/ReviewPlanValidator.java.
- Modify backend/src/main/java/com/example/codereview/agent/tool/AgentToolRegistry.java only through provider-neutral additions.
- Create focused tests for planning, tool requests, budgets, approvals, duplicates, and cancellation.

- [ ] Test the planning state produces a schema-valid Review Plan for the detected language plugins.
- [ ] Expose only tools compatible with the current state, plugin capabilities, project authorization, and remaining budget.
- [ ] Convert AgentTool input types to LangChain4j tool specifications without exposing executable paths or shell strings.
- [ ] Validate tool name, JSON arguments, plan membership, risk level, approval, byte limits, and budget before execution.
- [ ] Persist the model tool-request ID and use it in the ToolInvocation idempotency key.
- [ ] Return sanitized tool results to the model with explicit success, environment-incomplete, policy-rejected, or execution-failed status.
- [ ] Bound iterations, tool calls, model calls, input/output tokens, duration, retries, and estimated cost.
- [ ] Reject parallel or repeated destructive/generative requests unless the plan and idempotency policy explicitly allow them.
- [ ] Test unknown tools, arbitrary command fields, path traversal, prompt-injected tools, approval-required tools, and oversized arguments.
- [ ] Commit with: feat: execute bounded langchain4j agent tools

**Exit evidence:**

- A deterministic model fixture requests git.diff and code.search, receives persisted results, and returns a valid final plan.
- An injected scm.comment, patch.apply, or shell-like request is rejected before any external call.

### Task 8: Wire repository analysis, RAG, and evidence-backed findings

**Files:**

- Implement or modify preparing-repository, analyzing-change, retrieving-context, and verifying-findings executors.
- Reuse language plugins under backend/src/main/java/com/example/codereview/language/.
- Reuse ReviewContextService, FindingDeduplicator, FindingVerifier, FindingConfidenceService, and GateDecisionService.
- Create integration tests under backend/src/test/java/com/example/codereview/agent/orchestration/.

- [ ] Test repository preparation uses only signed sandbox jobs and fixed command IDs.
- [ ] Build RepositoryProfile and ChangeSet from persisted SCM event and bounded repository tools.
- [ ] Select Java, Python, and JavaScript/TypeScript plugins deterministically, including mixed-language PRs.
- [ ] Retrieve context through LangChain4jReviewContentRetriever using changed-code signals and tool rule IDs.
- [ ] Ask the model for schema-valid candidate findings with explicit code/tool/knowledge citations.
- [ ] Normalize and persist model, static-tool, knowledge, and verifier evidence without double-counting a source.
- [ ] Reject model-only blocking findings, stale locations, fabricated citations, and cross-head-SHA evidence.
- [ ] Run deduplication, independent verification, deterministic confidence, and gate decision in backend code.
- [ ] Ensure clean PRs and rejected findings skip Patch generation and proceed to publication.
- [ ] Commit with: feat: produce agent findings from tools and rag

**Exit evidence:**

- Java, Python, TypeScript, and mixed-language fixtures produce persisted, cited, confidence-scored findings.
- Prompt-only claims cannot block a PR.

### Task 9: Integrate model-generated Patch candidates with the existing safety workflow

**Files:**

- Implement or modify generating-patch and validating-patch executors.
- Reuse backend/src/main/java/com/example/codereview/patch/.
- Reuse patch.apply.check, patch.apply, and patch.validate runner commands.
- Extend Patch generation, validation, stale-head, and approval tests.

- [ ] Generate a Patch only for verified eligible findings and only within remaining model/tool budgets.
- [ ] Require unified diff output tied to Agent Run, head SHA, finding IDs, model, prompt version, and content hash.
- [ ] Apply all existing path, protected-file, binary, rename, size, file-count, and line-count policies before sandbox submission.
- [ ] Reject model attempts to alter CI, CODEOWNERS, Flyway history, secrets, or files outside the reviewed repository.
- [ ] Run baseline and patched checks with the same pinned image, dependency cache policy, resource limits, and network policy.
- [ ] Require the target fingerprint or reproducer to disappear; unrelated passing tests do not prove repair.
- [ ] Preserve independent apply, build, test, and scan statuses.
- [ ] Enter WAITING_APPROVAL only for an approval-eligible Patch; otherwise publish the findings without exposing Patch content.
- [ ] Confirm no model or framework callback can approve, upload, commit, push, or merge a Patch.
- [ ] Commit with: feat: verify langchain4j generated patches safely

**Exit evidence:**

- Known-patch evaluation cases apply and remove the target defect.
- Malicious, stale, oversized, protected-file, or non-repairing patches remain unapprovable.

### Task 10: Complete approval-aware SCM publication and idempotent recovery

**Files:**

- Implement or modify waiting-approval and publishing-result executors.
- Reuse SCM publication clients and PatchApprovalService.
- Modify Agent recovery and legacy projection only where tests prove an integration gap.
- Add GitHub and GitLab WireMock end-to-end publication tests.

- [ ] Test clean, blocking, nonblocking, failed, environment-incomplete, Patch-pending, Patch-approved, and Patch-rejected publication states.
- [ ] Publish summary, gate result, cited findings, Agent Run URL, and safe Patch status.
- [ ] Never publish unapproved Patch content.
- [ ] Revalidate authorization, head SHA, Patch hash, and current decision immediately before publication.
- [ ] Use a persisted publication idempotency key so duplicate messages and restarts do not create duplicate comments/checks/notes/statuses.
- [ ] Resume WAITING_APPROVAL without consuming a worker or repeating prior model/tool calls.
- [ ] Project the terminal Agent result into the legacy report exactly once.
- [ ] Ensure SCM errors are classified as retryable or permanent and never trigger a second Agent Run.
- [ ] Commit with: feat: publish completed langchain4j agent reviews

**Exit evidence:**

- GitHub and GitLab contract tests each show one publication for duplicate delivery and retry scenarios.

### Task 11: Add rollout controls, observability, and regression evaluation

**Files:**

- Modify backend and runner metrics/configuration.
- Extend evaluation/manifest.json with LangChain4j runtime metadata.
- Extend scripts/run-agent-evaluation.ps1.
- Extend EvaluationMetrics and report exporter only where required.
- Update README and operational documentation.

- [ ] Add bounded metrics for runtime, provider, model family, outcome, step, retrieval mode, tool name, and failure class.
- [ ] Do not use run ID, project ID, repository name, prompt, query, citation, error message, or trace ID as metric tags.
- [ ] Trace webhook, MQ, each state executor, retrieval, model calls, tool calls, sandbox jobs, approval, and SCM publication.
- [ ] Persist prompt/model/embedding/retrieval versions in evaluation output.
- [ ] Run the same development corpus against legacy and langchain4j modes and produce a comparison report.
- [ ] Enforce recall at least 0.80, precision at least 0.70, location accuracy at least 0.90, and repairable Patch apply rate at least 0.70.
- [ ] Add explicit gates for fabricated-citation rate 0, cross-project retrieval rate 0, unauthorized-tool execution rate 0, and unapproved-Patch publication rate 0.
- [ ] Define rollout stages: disabled, shadow, selected projects, and default.
- [ ] In shadow mode, prohibit SCM writes and Patch approval while recording comparable sanitized metrics.
- [ ] Document rollback to legacy runtime without deleting LangChain4j-generated audit records.
- [ ] Commit with: feat: evaluate and observe langchain4j agent runtime

**Exit evidence:**

- Comparison reports identify quality, latency, tokens, cost, and safety regressions.
- Rollback changes runtime selection without schema rollback or data loss.

### Task 12: Execute Docker, Testcontainers, and real end-to-end release acceptance

**Files:**

- Modify deploy/docker-compose.yml only when an acceptance failure proves it necessary.
- Add or extend scripts/verify-langchain4j-agent.ps1.
- Update docs/PR守门Agent实施进度.md.
- Update deployment, security, and demonstration documentation.

- [ ] Run docker version and docker compose version.
- [ ] Run docker compose config, build, up, health checks, and ps.
- [ ] Run all backend tests with InfrastructureIntegrationTest, LegacySchemaMigrationIntegrationTest, and GitHubWebhookAgentRunIntegrationTest enabled.
- [ ] Run PostgreSQL pgvector indexing/search with a real compatible embedding endpoint.
- [ ] Run RabbitMQ to backend and RabbitMQ to sandbox-runner message flows.
- [ ] Run a signed repository tool job and prove network, root filesystem, non-root user, CPU, memory, PID, timeout, Docker Socket, host path, and credential isolation dynamically.
- [ ] Execute GitHub and GitLab webhook-to-Agent-to-RAG/tool-to-SCM scenarios against controlled test endpoints.
- [ ] Run Patch apply/validate and dependency-cache mounts inside real containers.
- [ ] Verify W3C trace continuity and Prometheus collection across webhook, backend, RabbitMQ, runner, model, and publication.
- [ ] Run development and holdout corpora with pinned tool images, prompt/model/embedding versions, temperature zero, and fixed budgets.
- [ ] Record exact test counts, skipped tests, benchmark values, image digests, model versions, and known limitations.
- [ ] Do not mark Phase 5 or final release complete while any required Docker, Testcontainers, security, publication, or holdout gate is skipped.
- [ ] Commit with: docs: record langchain4j agent release acceptance

**Exit evidence:**

- One reproducible command produces the full release report.
- All required dynamic gates have authoritative logs and no required skip remains.

---

## 5. Required test matrix

| Area | Required proof |
| --- | --- |
| Dependency | Pinned LangChain4j modules resolve on Java 17 with no conflicting runtime |
| Chat adapter | WireMock request/response, tokens, finish reason, timeout, 401, 429, 5xx, malformed output |
| Embedding | Real vector mapping, metadata, dimension/version mismatch, re-index, memory and pgvector |
| Retrieval | Project/document isolation, hybrid weights, threshold, dedup, budget, exact citations |
| Prompt safety | Injection, fake citations, secret redaction, deterministic truncation |
| State execution | Every executable state registered, persisted, retryable, cancelable, recoverable, idempotent |
| Tool loop | Allowlist, typed arguments, approval, budget, unknown tools, arbitrary command rejection |
| Findings | Multilanguage fixtures, evidence fusion, model-only nonblocking, stale/fake evidence rejection |
| Patch | Scope policy, stale SHA, apply/build/test/scan, target disappearance, approval boundary |
| SCM | GitHub/GitLab exact-once publication and unapproved Patch zero disclosure |
| Evaluation | Quality, location, Patch, safety, latency, token, and cost gates |
| Dynamic security | Real container restrictions, no Docker Socket, no credentials, no unrestricted network |
| Observability | End-to-end trace and bounded Prometheus labels |

---

## 6. Completion definition

Phase 5 is complete only when all of the following are true:

1. LangChain4j is the configured chat and embedding integration for the selected runtime.
2. The existing deterministic Agent state machine remains authoritative.
3. AgentStepHandler executes real typed state handlers rather than returning a placeholder.
4. ReviewContextService is used in the production Agent flow through a scoped LangChain4j retriever.
5. Tool requests can execute only through AgentToolRegistry and the sandbox boundary.
6. Findings contain valid code/tool/knowledge citations and deterministic confidence decisions.
7. Patch generation, validation, approval, and publication preserve all existing safety boundaries.
8. Duplicate MQ deliveries, restarts, provider retries, and SCM retries do not duplicate side effects.
9. Backend, frontend, runner, Docker, Testcontainers, dynamic security, observability, and evaluation gates pass with exact evidence.
10. No required test is skipped and described as passed.

Adding a dependency, successfully calling a chat endpoint, or demonstrating a standalone RAG query is not sufficient completion evidence.

---

## 7. Explicit non-goals

- Replacing the Agent control plane with a free-running LangChain4j Agent.
- Multi-Agent free conversation or delegation.
- Arbitrary Shell generation or execution.
- Automatic commit, push, merge, or unapproved SCM write.
- Replacing PostgreSQL/pgvector with another vector database.
- Editing Flyway V1-V13.
- Expanding language support beyond Java, Python, and JavaScript/TypeScript in this phase.
- Claiming hostile multi-tenant isolation from the current single-host Compose architecture.

---

## 8. Recommended execution order

Tasks 1-5 establish safe LangChain4j model, embedding, retrieval, and prompt boundaries. Tasks 6-10 connect those boundaries to the actual persisted Agent lifecycle. Task 11 measures rollout quality and safety. Task 12 is the non-optional dynamic release gate.

Do not start Task 7 before Task 6 replaces the placeholder handler. Do not start production rollout before Tasks 8-12 prove evidence, Patch, publication, evaluation, and dynamic security behavior.
