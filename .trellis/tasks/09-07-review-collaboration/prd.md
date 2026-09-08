# Minimal review collaboration improvements

Approved in conversation on 2026-09-07; user explicitly requested implementation.

- Assign one requirement reviewer; only that active reviewer or the project leader can decide. Leader handles unassigned/unlinked reviews.
- Approve merges GitHub/GitLab at the reviewed SHA; request changes requires a reason, preserves PR and branch, and returns work to the existing developer.
- Keep persisted requirement lifecycle; derive a prominent current phase from existing review activity. Linked requirements must be in development with a valid developer before new human decisions.
- Reuse DingTalk group notifications with responsible person's name and project-aware detail link; no personal mention configuration.
- Show decision reasons, prior review and round, external PR/MR numbering and identity mapping.
- Project members can read/download public knowledge text; preserve attachment isolation and historical evidence.
- Minimal implementation: one nullable reviewer column, no new tables/runtime/dependencies, no new full-text search or diff engine.
- Preserve historical data and formal evaluation. No production deployment or remote repository mutation for testing.

Acceptance: three roles can assign, review, reject with reason, update the same branch, re-review and merge; knowledge is readable; unauthorized decisions and cross-project reads fail.
