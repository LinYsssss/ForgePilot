# Minimal call-chain correction design

## Constraints and reuse

Use existing `ai`, `review`, `knowledge` and frontend ownership. Keep 21 business
tables, 14 migrations, one AI gateway, one Review Engine and existing API routes.
The planned new production abstractions are one narrowly scoped document
processor and one small polling composable; other changes belong in current
classes. Prefer removing obsolete comments/branches over layering wrappers.

No field or helper is added solely to cover every observability improvement
mentioned in the audit. The implementation must solve R1–R7, not build a general
job system or a tracing platform.

## 1. Exact prompt budgeting (R1)

- Extend the existing batch planner to account for the actual formatted batch
  prompt, including requirement, ACs, knowledge and per-file framing. Keep one
  prompt renderer; supply its length calculation to the planner rather than
  duplicate the format as a second formula/class.
- Recall needs only the permitted file names before final packing. Construct
  the review context and recall once, then pack the files against that context;
  derive validator-visible files from the completed plan.
- Keep existing file-count limits and coverage objects. If a single file cannot
  fit, retain explicit partial/unreviewed coverage according to the existing
  line-boundary clipping policy. Never count omitted code as fully reviewed.
- Make the AI chat boundary redact, then reject an over-budget payload before
  HTTP; do not silently truncate it. This also protects synthesis and repair
  prompts. Embedding input sanitization remains separately bounded.
- No instruction/schema change is needed for this fix. Bump
  `ReviewPrompts.VERSION` only if implementation changes instruction or schema
  text; do not change `FindingKeys.RULE_VERSION`.

## 2. Attempt ownership and failure boundary (R2–R3)

- Give review-originated gateway calls a small pre-attempt callback. The existing
  HTTP retry loop runs it before each actual request; other AI consumers use the
  existing no-hook entry points. No timer or heartbeat thread is introduced.
- Review supplies a callback that renews the lease and aborts immediately if
  renewal returns false. All review chat/embedding/repair paths share it.
- Guard the whole claimed execution, including result transaction commit, with
  a RuntimeException boundary. A failed write transaction must exit and roll
  back before the fenced FAILED transition runs. Do not catch JVM Error.
- Lost ownership is an ordinary stop: never mark the replacement attempt failed
  or publish its failure notification. If the database itself is unavailable,
  retain a safe diagnostic and the existing lease reconciliation fallback.
- Log phase failures where the phase is known, retaining validation diagnostics
  without raw responses. Do not introduce a second result type merely to pass
  a string through several layers.

## 3. Embedding mapping and review audit (R4, R6)

- Allocate one vector slot per input. Validate each response item's integer
  index, range and uniqueness, assign to that slot, and reject incomplete data.
  This lives in the existing `AiGateway.vectors` parser.
- Add nullable reviewId to `AiCallContext` and a scalar mapping of the existing
  `ai_call_log.review_id`. Preserve project/revision factories for other use cases.
  Review recall and all chat branches construct a review-aware context.
- Keep the independent attempt-audit transaction and existing composite FK.
  Do not add a migration or backfill historical null values.
- Controlled application logs carry review ID, execution attempt and phase.
  The current configured-model column remains configured-model metadata; do not
  relabel it as verified provider identity. Dedicated trace columns and precise
  completion timestamps are outside this minimum change.

## 4. Short upload acceptance and durable document processing (R5)

The only behavioral expansion is needed here: a synchronous 120-second AI
request plus retry cannot reliably finish under a 60-second response deadline.
Increasing Nginx's timeout alone also leaves long database transactions and
ambiguous success at other proxies.

1. Existing create/promote operations validate and store the document as PENDING
   in a short transaction. Requirement attachment relation and document creation
   still commit together. Return the existing metadata response promptly; its
   status is PENDING until processing finishes. Keep the existing creation route
   and HTTP 201 resource-creation semantics.
2. One `KnowledgeIngestionProcessor` in the knowledge package processes pending
   documents serially using the existing Spring scheduler. Query only a bounded
   next item and release the read transaction before external HTTP. Do not add
   a task table, a task queue service, or another AI runtime.
3. Embed outside a database transaction through the same AiGateway. In a short
   transaction save all chunks/vectors and mark READY together. On failure, roll
   back partial writes and mark FAILED with a bounded safe reason.
4. Check the document still exists and remains PENDING before final writes;
   deletion must win without recreation. READY/FAILED are not selected again.
5. Pending rows survive process restart and are naturally selected on the next
   scan. The fixed-delay worker is non-overlapping in the current single-backend
   deployment. Multi-replica document leasing is explicitly not introduced.
6. Ensure the existing Spring scheduler has capacity for the serial ingestion
   callback and review reconciliation independently; a slow embedding must not
   occupy the only scheduler thread. Review worker concurrency remains two.

Search must continue to exclude non-ready documents/partial vectors. The existing
knowledge metadata already exposes status and failureReason. Public knowledge
and requirement attachment screens must label PENDING as processing, then
refresh to READY or FAILED. Failed work stops; no automatic endless retry loop
is added.

This is more code than a one-line proxy timeout, but it fixes the observed
failure contract and connection-pool risk using facts already represented in
the model. One processor is the smallest additional owner of this necessary
background behavior.

## 5. Shared finite polling (R7 and R5 presentation)

- Introduce one small Vue composable using a self-scheduling timeout after the
  previous request completes. Reuse it in review list/detail and document-status
  consumers; no SSE, WebSocket, state library or polling framework.
- Poll only while displayed reviews are PENDING/RUNNING or documents are
  PENDING. Stop at terminal state and component disposal, and do not accumulate
  requests while hidden or navigating away.
- Preserve current per-route request-generation checks. Refresh result data
  separately from initial page loading so comments, association selection,
  filters, selected evidence and other user edits are not cleared.
- Surface polling errors and provide a bounded recovery/manual refresh path;
  do not silently display stale state as current.

## Compatibility, rollout and rollback

- Existing tables and applied Flyway files remain untouched. Older completed
  reviews and findings are never rewritten. No formal evaluation assets run.
- The upload response can now be PENDING. Update every in-repository consumer
  and test that assumed immediate READY; API documentation must state acceptance
  versus indexing completion explicitly. External clients must wait for READY
  using existing metadata reads.
- Tests use loopback provider stubs and isolated PostgreSQL. There is no reason
  to make paid provider calls or send real notifications.
- Keep logical change groups for review: gateway/review correctness; document
  processing; UI refresh/contracts. Before any deployment, review the verified
  diff and pending-document behavior. A rollback to the old binary should drain
  or otherwise explicitly handle newly accepted PENDING documents first.

## Expected source touch points

- `ai/AiGateway`, `AiCallContext`, `AiCallLog`, `PromptSanitizer` as needed.
- `review/ChangedFileBatcher`, `ReviewPipeline`, `ReviewExecutor`, small prompt
  renderer extraction if necessary.
- `knowledge/KnowledgeService`, document repository, one ingestion processor;
  the shared scheduling property in `application.yml`.
- `ReviewDetailPage.vue`, `ReviewsPage.vue`, `KnowledgePage.vue`, requirement
  attachment status consumer, and one shared polling composable.
- Focused existing test suites, API/architecture descriptions and the relevant
  `.trellis/spec` prevention notes.
