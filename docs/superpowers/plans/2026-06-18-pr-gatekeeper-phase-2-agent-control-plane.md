# PR Gatekeeper Phase 2 Agent Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent, budgeted, recoverable Agent runtime while preserving the existing review APIs.

**Architecture:** Introduce `agent_run`, `agent_step`, and `tool_invocation` as a new control plane. A deterministic Java state machine owns transitions; RabbitMQ schedules execution; registered typed tools perform work; an adapter projects completed runs into existing review reports.

**Tech Stack:** Java 17, Spring Boot, JPA, Flyway, RabbitMQ, Jackson JSON Schema-style validation, SSE, Micrometer.

---

## Control-plane boundaries

- State transitions and outbox insertion occur in one database transaction.
- RabbitMQ uses at-least-once delivery; every handler and tool invocation is idempotent.
- Clean PRs and non-fixable findings may skip Patch generation, validation, and approval.
- Cancellation prevents new work and requests termination of an active sandbox job.
- Model plans, tool arguments, and tool results are redacted and size-limited before persistence.
- Model output cannot directly select Java classes, Spring beans, queues, images, paths, or shell commands.

### Task 1: Persist Agent runs and steps

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__agent_control_plane.sql`
- Create: `backend/src/main/java/com/example/codereview/agent/run/AgentRun.java`
- Create: `backend/src/main/java/com/example/codereview/agent/run/AgentStep.java`
- Create: `backend/src/main/java/com/example/codereview/agent/run/AgentRunRepository.java`
- Create: `backend/src/main/java/com/example/codereview/agent/run/AgentStepRepository.java`
- Test: `backend/src/test/java/com/example/codereview/agent/run/AgentRunPersistenceTest.java`

- [ ] Write a failing JPA integration test that saves a `RECEIVED` run, appends a step, reloads both, and verifies optimistic versioning.
- [ ] Run `mvn -s .mvn/settings.xml -Dtest=AgentRunPersistenceTest test`; expect failure because entities/tables do not exist.
- [ ] Create enums `AgentRunStatus` and `AgentStepStatus` with only the states defined in the approved design.
- [ ] Add `@Version` to `AgentRun`; add unique `(agent_run_id, sequence_no)` and an index on `(status, updated_at)` in V3.
- [ ] Add immutable `trigger_key`, `head_sha`, `current_step_sequence`, `cancellation_requested`, and timestamps to `agent_run`.
- [ ] Re-run the test; expect PASS.
- [ ] Commit with `feat: persist agent runs and steps`.

### Task 2: Implement and test legal state transitions

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/run/AgentStateMachine.java`
- Create: `backend/src/main/java/com/example/codereview/agent/run/IllegalAgentTransitionException.java`
- Test: `backend/src/test/java/com/example/codereview/agent/run/AgentStateMachineTest.java`

- [ ] Test the main path plus branches: no findings goes from `VERIFYING_FINDINGS` to `PUBLISHING_RESULT`; findings without a safe Patch go from generation/validation to publishing; only an applied Patch enters `WAITING_APPROVAL`. Reject `COMPLETED -> EXECUTING_TOOLS`, `CANCELED -> RETRY_WAIT`, and `WAITING_APPROVAL -> COMPLETED`.
- [ ] Run the focused test; expect compilation failure.
- [ ] Implement transitions as an immutable `Map<AgentRunStatus, Set<AgentRunStatus>>`.
- [ ] Ensure terminal states have no outgoing transitions and approval continues only to `PUBLISHING_RESULT`.
- [ ] Run the focused test; expect PASS.
- [ ] Commit with `feat: enforce agent state transitions`.

### Task 3: Add budgets and classified failures

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/budget/AgentBudget.java`
- Create: `backend/src/main/java/com/example/codereview/agent/budget/BudgetUsage.java`
- Create: `backend/src/main/java/com/example/codereview/agent/budget/BudgetGuard.java`
- Create: `backend/src/main/java/com/example/codereview/agent/error/AgentFailureType.java`
- Test: `backend/src/test/java/com/example/codereview/agent/budget/BudgetGuardTest.java`

- [ ] Write tests proving elapsed time, tool calls, model calls, input/output tokens, and estimated cost each trigger `BUDGET_EXCEEDED`.
- [ ] Implement `BudgetGuard.check(AgentBudget, BudgetUsage, Instant now)` returning a typed result rather than a boolean.
- [ ] Add configuration under `app.agent.budget` with conservative defaults.
- [ ] Run tests and commit with `feat: enforce agent execution budgets`.

### Task 4: Build the typed tool registry

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/tool/AgentTool.java`
- Create: `backend/src/main/java/com/example/codereview/agent/tool/ToolContext.java`
- Create: `backend/src/main/java/com/example/codereview/agent/tool/ToolResult.java`
- Create: `backend/src/main/java/com/example/codereview/agent/tool/ToolRiskLevel.java`
- Create: `backend/src/main/java/com/example/codereview/agent/tool/AgentToolRegistry.java`
- Create: `backend/src/main/java/com/example/codereview/agent/tool/ToolInvocation.java`
- Test: `backend/src/test/java/com/example/codereview/agent/tool/AgentToolRegistryTest.java`

- [ ] Test duplicate names, unknown tools, approval-required tools, malformed input, and successful execution.
- [ ] Implement registry construction from `List<AgentTool<?, ?>>`; reject duplicate names at startup.
- [ ] Persist sanitized JSON input/output, duration, status, and budget usage for every invocation.
- [ ] Add a unique `invocation_key` and enforce input/output byte limits so duplicate delivery cannot execute a side-effecting tool twice.
- [ ] Ensure the registry never accepts a raw command field for execution.
- [ ] Run tests and commit with `feat: add typed agent tool registry`.

### Task 5: Validate and persist review plans

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/plan/ReviewPlan.java`
- Create: `backend/src/main/java/com/example/codereview/agent/plan/ReviewPlanValidator.java`
- Create: `backend/src/main/java/com/example/codereview/agent/plan/ReviewPlanRepository.java`
- Create: `backend/src/main/resources/db/migration/V4__review_plan_and_tool_invocation.sql`
- Test: `backend/src/test/java/com/example/codereview/agent/plan/ReviewPlanValidatorTest.java`

- [ ] Test valid ordered tools, unknown tools, repeated tools beyond budget, approval-required tools before approval, and empty plans.
- [ ] Represent each plan item as `toolName`, typed JSON `arguments`, `purpose`, and `expectedEvidence`.
- [ ] Persist the model response separately from the validated plan and record validation errors.
- [ ] Reject plans that exceed per-tool limits, reference unavailable plugins, request write tools before approval, or contain oversized arguments.
- [ ] Run tests and commit with `feat: validate model-generated review plans`.

### Task 6: Add structured model output and prompt-injection boundaries

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/model/AgentModelClient.java`
- Create: `backend/src/main/java/com/example/codereview/agent/model/StructuredModelResponse.java`
- Create: `backend/src/main/java/com/example/codereview/agent/model/ModelOutputValidator.java`
- Create: `backend/src/main/java/com/example/codereview/agent/model/PromptEnvelope.java`
- Test: `backend/src/test/java/com/example/codereview/agent/model/ModelOutputValidatorTest.java`

- [ ] Test valid JSON, Markdown-wrapped JSON, unknown fields, oversized output, invalid tools, and repository text containing injected instructions.
- [ ] Separate trusted policy, untrusted repository content, tool evidence, and output schema in `PromptEnvelope`.
- [ ] Permit one bounded JSON repair attempt, then return `INVALID_MODEL_OUTPUT`.
- [ ] Persist provider, model, prompt/schema version, token usage, and redacted failure reason.
- [ ] Run tests and commit with `feat: validate structured agent model output`.

### Task 7: Add a transactional outbox

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__agent_outbox.sql`
- Create: `backend/src/main/java/com/example/codereview/agent/outbox/AgentOutboxEvent.java`
- Create: `backend/src/main/java/com/example/codereview/agent/outbox/AgentOutboxRepository.java`
- Create: `backend/src/main/java/com/example/codereview/agent/outbox/AgentOutboxPublisher.java`
- Test: `backend/src/test/java/com/example/codereview/agent/outbox/AgentOutboxPublisherTest.java`

- [ ] Test state transition and outbox insertion commit or roll back together.
- [ ] Test MQ failure leaves the event pending with attempt and next-attempt fields.
- [ ] Claim rows with database locking, publish, then mark sent.
- [ ] Test repeated publication produces one effective step through consumer idempotency.
- [ ] Commit with `feat: publish agent steps through transactional outbox`.

### Task 8: Schedule one idempotent Agent step through RabbitMQ

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/queue/AgentStepMessage.java`
- Create: `backend/src/main/java/com/example/codereview/agent/queue/AgentStepPublisher.java`
- Create: `backend/src/main/java/com/example/codereview/agent/queue/AgentStepConsumer.java`
- Modify: `backend/src/main/java/com/example/codereview/config/RabbitMqConfig.java`
- Test: `backend/src/test/java/com/example/codereview/agent/queue/AgentStepConsumerTest.java`

- [ ] Test duplicate delivery after a successful step is ignored.
- [ ] Test retryable failure enters `RETRY_WAIT`; security and budget failures enter `FAILED` without blind retries.
- [ ] Add dedicated work, delay, cancellation, and dead-letter queues; do not reuse legacy review routing keys.
- [ ] Use `(agentRunId, sequenceNo, attempt)` as message identity.
- [ ] Publish only from `AgentOutboxPublisher`; business services may not call `RabbitTemplate` directly.
- [ ] Run tests and commit with `feat: schedule idempotent agent steps`.

### Task 9: Implement restart recovery

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/run/AgentRecoveryService.java`
- Test: `backend/src/test/java/com/example/codereview/agent/run/AgentRecoveryServiceTest.java`

- [ ] Test that stale running steps are marked interrupted and republished once.
- [ ] Test `WAITING_APPROVAL`, terminal runs, and recently updated active runs are not republished.
- [ ] Implement a startup recovery query using a configurable stale threshold.
- [ ] Protect recovery with an atomic database status update so multiple instances cannot republish the same step.
- [ ] Run tests and commit with `feat: recover interrupted agent runs`.

### Task 10: Expose timeline APIs and SSE

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/api/AgentRunController.java`
- Create: `backend/src/main/java/com/example/codereview/agent/api/AgentRunDtos.java`
- Create: `backend/src/main/java/com/example/codereview/agent/api/AgentEventService.java`
- Test: `backend/src/test/java/com/example/codereview/agent/api/AgentRunControllerTest.java`

- [ ] Test project authorization, run detail, ordered timeline, cancel, retry, and SSE reconnect behavior.
- [ ] Return persisted state first; use SSE only as a notification channel.
- [ ] Emit event IDs based on step sequence so clients can resume with `Last-Event-ID`.
- [ ] Bound emitter lifetime/subscriber count and remove emitters on completion, timeout, and connection error.
- [ ] Run controller tests and commit with `feat: expose agent run timeline`.

### Task 11: Project completed Agent findings into legacy reports

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/compat/LegacyReviewProjectionService.java`
- Test: `backend/src/test/java/com/example/codereview/agent/compat/LegacyReviewProjectionServiceTest.java`

- [ ] Test one completed Agent Run creates one legacy report and repeated projection is idempotent.
- [ ] Preserve `review_report`/`review_issue` response compatibility for the existing frontend.
- [ ] Add a unique projection key rather than relying on application-only checks.
- [ ] Run all backend tests and commit with `feat: project agent results to legacy reports`.

### Task 12: Add Agent metrics and phase verification

**Files:**
- Create: `backend/src/main/java/com/example/codereview/agent/observability/AgentMetrics.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `README.md`

- [ ] Add counters for created/completed/failed/recovered runs and timers for steps/tools.
- [ ] Tag only bounded values such as status and tool name; never tag run IDs, repository names, or error messages.
- [ ] Propagate a correlation ID through HTTP, outbox, RabbitMQ, and tool logs; full OpenTelemetry export is completed in Phase 4.
- [ ] Run `mvn -s .mvn/settings.xml verify`.
- [ ] Run `npm test` and `npm run build`.
- [ ] Commit with `feat: instrument agent control plane`.
