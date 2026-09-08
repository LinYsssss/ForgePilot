# Design

- Add V14 reviewer_id with composite project-member FK; extend existing requirement views and member-removal cleanup; dedicated reviewer POST with nullable userId.
- Read requirement decision context through RequirementDirectory; enforce current roles and lifecycle in ReviewDecisionService. Expose decision eligibility from backend for UI consistency.
- Keep decision write-once transaction and PR lock. SCM action accepts expected SHA. Reject has no remote writes. Both provider clients validate merge result, recover ambiguous response by read-only merged-state confirmation, and recognize already-merged same-head retries. Remote/DB atomicity is not promised; unresolved outcomes require manual verification.
- Publish decision notification event after successful transaction, reuse existing notification repository and sender. Resolve effective responsible member, default leader; message excludes reason text.
- Frontend shares a pure phase formatter. Review history supplies round and previous reason; no new history persistence. Public knowledge content/download mirrors attachment API with project/source checks.
- Add only nullable reviewer column. Old null reviewers and empty historical reasons remain valid. Existing PR title is exposed without new storage.
