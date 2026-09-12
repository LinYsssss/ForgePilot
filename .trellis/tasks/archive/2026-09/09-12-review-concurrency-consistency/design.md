# Design

## Page ownership

Use the existing review load generation together with the current project and
review IDs to identify a page visit. Every asynchronous operation captures that
identity and its PR ID before awaiting. Check ownership after every asynchronous
boundary, including error/finally paths. Reset operation state on navigation and
invalidate the generation on unmount. Require the displayed detail to match the
route before sending writes.

## Requirement writes

Acquire a project-scoped PostgreSQL row lock on `requirement` before loading the
entity and current revision. Reuse that entrance for draft edits, revision
publication, lifecycle changes, developer/reviewer assignment, and soft deletion.
Take the scalar row lock separately from the entity graph so joined nullable
revision data is not locked and stale joined data is not reused after waiting.
The lock lasts through commit; read-only paths stay unlocked.

## Review creation

Lock the PR before reading its identity in the manual path and retain that lock
through context capture and insert/retry. Webhook ingestion already owns the same
PR row lock. Keep source-of-truth queries scoped by project in the manual path and
keep automatic creation inside its caller's transaction. Do not add dependencies
on another feature's Repository.

## GitHub reads

Read and validate metadata, fetch all file pages, then read metadata again. Compare
base/head, source update time, and the remaining captured metadata. A mismatch
discards the entire manifest and permits one fresh attempt. Persistent changes
return a conflict before synchronization writes anything. No remote writes or
schema changes are required.

## Compatibility

Existing HTTP bodies, role rules, terminal-state behavior, immutable revision and
Review records remain unchanged. Concurrent publications may serialize into
successive revisions. The extra provider read and brief row-lock contention are
the required cost of consistent snapshots. Rollback is a code revert.
