# Minimal fixes for the audited call chain

## Goal

Fix the seven confirmed call-chain defects with the smallest cohesive change,
keeping the existing modules, data model and single Review Engine easy to read.
The user asked: “这些问题能够最小实现修复吗 让我的代码不冗余的情况下解决可以吗”.

## Background

The [call-chain audit](/root/forgepilot-reports/call-chain-20260914/report.md)
contains the reproductions and distinguishes actual incidents from boundary
defects. All 15 recorded reviews completed on their first execution. None of
the frozen evaluation assets may be changed or rerun.

## Requirements

| ID | Requirement | Existing evidence / owner |
|---|---|---|
| R1 / C1, P1 | Never claim code was fully reviewed when the actual AI request omits its tail. Final prompt overflow must be visible. | `ChangedFileBatcher.java:91`, `AiGateway.java:98`; 67,641 characters became 60,000 on the wire while coverage remained complete. |
| R2 / C2, P1 | Every review provider attempt, including embedding, transport retries and format repairs, must check current execution ownership. Losing ownership must stop subsequent calls. | `ReviewPipeline.java:153,362`, `ReviewExecutor.java:137`; repair callbacks omit renewal. |
| R3 / C3, P2 | Recoverable runtime failures in analysis or result persistence must finish the current attempt as FAILED when the database is available, with an actionable diagnostic. | `ReviewExecutor.java:136`; an unexpected runtime exception invoked no failed transition. |
| R4 / C4, P2 | Embedding vectors must correspond to input indexes; missing, duplicate, out-of-range or invalid indexes must fail explicitly. | `AiGateway.java:228`, `KnowledgeService.java:209`; reversed indexes were paired by array order. |
| R5 / C5, P2 | Upload acceptance must complete independently of embedding latency. Users must see durable processing, success or failure for the accepted document. Embedding must not hold a database connection for its duration. | `KnowledgeService.java:74,192`, `KnowledgePage.vue:99`; upload returned 504 but document 6 later became READY. |
| R6 / C6, P2 | New review chat and query-embedding attempts must reference the existing Review ID. Failures must identify the review attempt and execution stage in controlled logs. | `AiCallContext.java:12`, `AiCallLog.java:23`; all 32 stored REVIEW calls have null review_id. |
| R7 / C7, P2 | Pending/running review results and pending uploaded documents must refresh while their page remains open, without erasing input or applying stale responses after navigation. | `ReviewDetailPage.vue:312`, `ReviewsPage.vue:217`, `AppShell.vue:124`. |

## Acceptance Criteria

- [x] AC1: A large accepted patch plus context is either sent fully within the
      final budget or represented accurately as partial/unreviewed; an oversized
      synthesis or repair fails explicitly instead of being silently clipped.
- [x] AC2: Reproductions cover both repair branches and an HTTP retry. Ownership
      failure prevents the next request and cannot change the replacement attempt.
- [x] AC3: Non-ApiException failures in analysis and persistence invoke a fenced
      failure transition after any failed write transaction has rolled back.
      Process-level Errors are not swallowed; database-outage recovery still works.
- [x] AC4: Out-of-order embedding responses are reordered correctly. Invalid
      indexes are rejected before vectors can be saved.
- [x] AC5: A slow mock embedding does not delay the upload's acceptance response.
      Accepted documents remain PENDING until complete, then become READY or
      FAILED. Pending work resumes after restart. Attachment/document creation
      stays atomic, deleted documents are not resurrected, and partial vectors
      are never retrievable. Review reconciliation remains responsive during
      slow document processing.
- [x] AC6: Successful and failed review AI attempts, including recall embeddings,
      persist review_id with existing project isolation. Historical null rows
      are not guessed or rewritten. Logs identify stage and execution attempt
      without storing prompts, credentials or raw model output.
- [x] AC7: Polling reaches a terminal state, does not overlap requests, and stops
      when no work remains or the page is left. Route changes cannot apply old
      responses; background refresh preserves editable form state.
- [x] AC8: Existing backend verification and frontend lint/typecheck/tests/build
      pass. No new dependency, business table, schema migration, top-level package,
      Review Engine, or generic orchestration/observability subsystem is added.

## Scope boundaries

- Preserve the current single-backend deployment model and existing business
  permissions. The only intended user-flow change is explicit asynchronous
  document processing plus automatic status refresh.
- The minimal audit fix is the existing review_id column and controlled stage
  diagnostics. A new persisted trace schema, historical backfill, precise
  completion-time columns and provider model-attestation features are deferred.
- Evidence-line offsets, model false positives, finding continuity keys, remote
  gateway internals and formal accuracy evaluation are outside this task.
- Local implementation and isolated verification are the implementation scope.
  Publishing, deployment, real provider generations and remote notifications
  are not part of this change.

## Planning status

Requirements and the proposed smallest coherent implementation are documented.
The user approved the final planning summary on 2026-09-15 (批准).
Implementation and isolated verification may proceed within the documented scope.
