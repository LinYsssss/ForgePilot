# Migration and constraint research

## Why three new tables are the minimum

The requested facts have different lifecycles and cardinalities:

1. a project membership exists once per project/user;
2. a membership owns one or more project roles;
3. a platform account owns zero or more reusable remote identities;
4. a membership selects one active identity for a specific repository context and keeps replacement/approval history.

Keeping these in `project_member` would require arrays/JSON for roles and repeated identity columns, making uniqueness, ownership, and history unenforceable. `project_member_role`, `scm_identity`, and `project_member_scm_binding` are therefore irreducible normalized facts, taking the schema from 16 to 19 business tables.

## Cross-row invariants

- A partial unique index on `project_member_role(project_id) WHERE role='LEADER'` enforces at most one Leader.
- Composite foreign keys bind a role to `(project_id,user_id)`, and bind a project SCM row both to its membership `(project_id,user_id)` and to an identity owned by that user `(user_id,scm_identity_id)`.
- Partial unique indexes allow at most one `ACTIVE` and at most one `PENDING_APPROVAL` binding per project member. Keeping one active binding while a strict-project replacement awaits approval avoids an authorization gap.
- Once-verified remote ownership uses a partial unique index on `(provider,instance_identity,external_user_id) WHERE verification_method='ONE_TIME_TOKEN'`. Revocation does not release the remote identity to another ForgePilot account; the original owner may re-verify it. Legacy administrator assertions may conflict across ForgePilot users and therefore cannot safely enter that proven-ownership uniqueness domain.
- “Every member has a role” and “every project keeps a Leader” cross tables and cannot be expressed by a simple `CHECK`. The minimal implementation follows the existing D004 pattern: a partial unique index enforces at most one Leader, while project-row locking and the transactional Service prevent empty roles or loss of the last Leader. No new trigger is added.

## Legacy migration

One appended `V8` migration performs a forward data conversion without editing V1–V7:

1. add/backfill/validate `user_account.display_name`;
2. create `project_member_role` and copy every existing role;
3. create identities and bindings from old SCM columns, using the joined repository provider/instance when available;
4. mark all converted assertions `LEGACY_UNCONFIRMED` with method `LEGACY_ADMIN_ASSERTED`; rows lacking repository context keep nullable provider/instance and can never authorize;
5. add the necessary indexes, foreign keys, and checks after the copy validates;
6. add the repository approval-policy flag;
7. drop the old single-role and SCM columns only after row-count/invariant checks pass.

Legacy rows are not silently promoted to verified. The member must verify a token and confirm a compatible project binding before “my PR” permission works. Existing PR author snapshots and recomputable `author_user_id` values remain intact; authorization stops trusting `author_user_id` alone.

## Rollback

Flyway migrations are forward-only in this repository. Because V8 drops old columns after conversion, rollback after V8 requires restoring the pre-deploy database backup or shipping a separately reviewed forward repair migration. Deployment must therefore validate both an empty database and a V7-to-V8 upgrade fixture before release.
