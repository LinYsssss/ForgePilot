# Evidence and discarded heavier options

The audit at `/root/forgepilot-reports/call-chain-20260914/report.md` is the
evidence authority. `probe/probe-results.json` records the original production
counterexamples. Application source matches the deployed application commit at
the start of this task; the worktree was clean.

| Reuse point | Confirmed source | Consequence |
|---|---|---|
| Existing Review FK | `AiCallContext.java`, `AiCallLog.java`, V6 schema | Map review_id; no tracing migration needed. |
| Existing lease/token fencing | `ReviewExecutor`, `ReviewClaimRepository` | Check ownership before requests; keep existing write protections. |
| Existing coverage types | `ChangedFileBatcher.Plan/Coverage`, `ReviewPrompts` | Correct the final budget calculation; do not add a second planner. |
| Existing document processing states | `KnowledgeDocument`, `KnowledgeStatus` | Durable PENDING/READY/FAILED needs no new table or enum/migration. |
| Existing read APIs | Knowledge/review list and detail APIs | Status polling needs no new read endpoints or transports. |
| Existing scheduler | `ReviewExecutorConfig`, `ReviewReconciliationScheduler` | Reuse Spring scheduling while giving reconciliation independent capacity. |

An increased proxy timeout alone was rejected: it does not release upload
transactions or make completion visible after another proxy/client disconnects.
A new MQ, task table, outbox, tracing framework and generic retry engine were
rejected because existing document/review rows and the AI gateway already own
the necessary facts and policies.

The asynchronous document path changes creation completion semantics and is
therefore explicit in the final human-review summary. Its processor is serial
under the current single-backend deployment. Distributed document leases are
outside this minimum implementation. No production fault injection is needed
to validate the change.

Optional audit improvements remain separate: persisting phase/attempt/upstream
request/model columns and a dedicated completion timestamp would require a
larger data contract. This task maps the existing FK and adds controlled phase
diagnostics; it does not claim to provide that larger trace schema.
