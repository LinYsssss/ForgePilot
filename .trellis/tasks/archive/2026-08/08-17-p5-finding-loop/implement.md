# P5 Finding 闭环与门禁扩展 — Implementation Plan

> Status checkpoint: 2026-08-17 06:03 PDT. Current branch `main`, task `in_progress`; implementation and Phase 2.2 check are complete, commit/archive remain.

## Current progress

### Completed / present in working tree

- [x] Child task created and linked to `08-16-forgepilot-upgrade`.
- [x] PRD and Trellis implement/check context manifests created.
- [x] V35 draft adds finding lifecycle fields and `agent_run.gate_verdict`.
- [x] `FINDING_TRANSITION_ILLEGAL` added.
- [x] `FindingLifecycle` state graph added.
- [x] `Finding` lifecycle/assignee/fix/verification/suggestion fields and mutation methods added.
- [x] `FindingRepository` project/lifecycle query and active-finding query draft added.
- [x] `FindingLifecycleService` draft added for list/transition/assign.
- [x] Backend compilation passed: `mvn -f backend/pom.xml -DskipTests compile` (2026-08-17 01:38 PDT).
- [x] Codex Trellis integration installed; current Codex thread bound to this task.

### Remaining verification / finish items

- [ ] Full `mvn verify` and `npm test` were intentionally not run because the user requested non-essential tests be skipped.
- [ ] Runtime Flyway/JPQL/MVC integration remains to be exercised by the deferred full test suite.
- [ ] Commit, archive, and parent-task completion remain.

## Ordered execution

### Phase A — finish lifecycle backend vertical slice

- [x] Correct assignment authorization to LEADER-only.
- [x] Append lifecycle fields to `AgentFindingDtos.AgentFindingResponse`.
- [x] Add lifecycle/assign request records with validation.
- [x] Add project finding controller endpoints.
- [x] Add service and MVC tests for happy path, terminal state, 403, cross-project 404 and illegal 409.
- [x] Compile main/test sources; focused test cases were added but not executed per user request.

**Review gate A:** API can drive `OPEN -> ... -> CLOSED`, with negative role cases passing.

### Phase B — automatic review suggestion

- [x] Locate the run publication point after verified findings are persisted.
- [x] Add repository queries for same-PR historical runs/findings without N+1 expansion where practical.
- [x] Implement best-effort `FindingResolutionSuggester`.
- [x] Add hit/miss/failure/no-lifecycle-mutation tests.

**Review gate B:** publication succeeds even when suggestion calculation fails.

### Phase C — run gate verdict

- [x] Add `GateVerdict` enum/value mapping and `AgentRun.gateVerdict` persistence.
- [x] Implement `RunGateVerdictService` from blocking decisions, coverage and lifecycle state.
- [x] Invoke during publication; persist verdict and append SCM note line.
- [x] Preserve Conclusion mapping: BLOCK action-required; PASS/WARN success.
- [x] Add three-state matrix and publication compatibility tests.

**Review gate C:** BLOCK/WARN/PASS tests pass and previous P4b publication tests remain green.

### Phase D — `/quality` frontend

- [x] Add route and navigation entry.
- [x] Add project finding API methods/types.
- [x] Build lifecycle filter/list/detail and suggestion badge.
- [x] Add member assignment and fix SHA mutation flows.
- [x] Apply role-aware action visibility and mutation refresh.
- [x] Add responsive/accessibility behavior; frontend production build passed (no full npm test).

**Review gate D:** a user can demonstrate Finding -> fix -> review -> verify -> gate PASS.

### Phase E — final verification and Trellis finish

- [ ] `mvn -f backend/pom.xml verify` (deferred per user request; compile/test-compile passed).
- [x] Frontend `npm run build` passed; `npm test` deferred per user request.
- [x] Run Trellis check flow; fix all findings.
- [x] Update relevant backend/frontend specs if new reusable conventions emerged.
- [x] Work commit `cb8b96e` created; archive task in finish-work flow.

## Do not do

- Do not rename or repurpose existing `Finding.status`.
- Do not auto-close findings.
- Do not add `requirement_id` in P5.
- Do not change SCM Conclusion semantics.
- Do not discard the current dirty working tree; it is the active P5 implementation draft.
