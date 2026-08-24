# Technical design

## 1. Architecture and ownership

This change keeps the modular-monolith boundary and all eight existing top-level backend packages.

| Package | Owns | May call |
| --- | --- | --- |
| `auth` | account display name, self profile, read-only account search facade | common only |
| `project` | membership lifecycle, role sets, candidate search, atomic batch add, Leader transfer | `auth`, common |
| `scm` | global SCM identities, one-time provider verification, repository access checks, binding history/approval | `auth`, `project`, common |
| `requirement` / `knowledge` / `review` | existing capabilities migrated from single-role equality to role-set membership | `project` through `ProjectAccessService` |

`project` does not query SCM tables. The frontend composes candidate/member data from `project` with readiness and binding summaries from `scm`. This avoids a `project -> scm -> project` cycle.

The frontend keeps six primary navigation entries. It adds a non-primary `/account` route linked from the account menu for display-name/password/SCM-identity management; the project member route remains the project-specific role and binding workspace.

## 2. Data model (V8, 19 business tables)

### 2.1 Existing-table changes

`user_account`:

- `display_name VARCHAR(120) NOT NULL`
- `CHECK (btrim(display_name) <> '')`

`project_member` stops carrying role and SCM identity fields:

- keep `id`, `project_id`, `user_id`, timestamps, `UNIQUE(project_id,user_id)`, and `(project_id,user_id)` as the project-scoped reference target.

`scm_repository`:

- add `identity_approval_required BOOLEAN NOT NULL DEFAULT FALSE`.

### 2.2 `project_member_role`

| Column | Contract |
| --- | --- |
| `project_id`, `user_id` | composite FK to `project_member(project_id,user_id)` |
| `role` | `LEADER|DEVELOPER|REVIEWER` |
| `created_at` | audit timestamp |

Primary key: `(project_id,user_id,role)`. A partial unique index over `project_id WHERE role='LEADER'` enforces at most one Leader.

All role mutations lock the project row and replace a member's role set in one transaction. The existing architectural pattern remains: the partial unique index enforces at most one Leader, while the Service prevents an empty role set and guarantees every project retains a Leader. No new trigger or generalized invariant framework is introduced.

### 2.3 `scm_identity`

| Column | Contract |
| --- | --- |
| `id`, `user_id` | identity id and owning ForgePilot account |
| `provider` | nullable only for unresolved legacy rows; otherwise `GITHUB|GITLAB` |
| `instance_identity` | normalized host/host:port; nullable only for unresolved legacy rows |
| `external_user_id` | Provider's stable numeric id serialized as text; never browser-asserted for verified rows |
| `external_username` | display snapshot, never an authorization key |
| `label` | required user label, e.g. “公司 GitHub” |
| `usage_type` | `WORK|PERSONAL|CLIENT|OTHER` |
| `verification_status` | `VERIFIED|LEGACY_UNCONFIRMED|REVOKED` |
| `verification_method` | `ONE_TIME_TOKEN|LEGACY_ADMIN_ASSERTED` |
| `verified_at`, `last_synced_at`, timestamps | proof/synchronization history |

Constraints:

- `UNIQUE(user_id,id)` is the ownership target for bindings.
- A verified row requires provider, instance, external id, username, and verification timestamps.
- A partial unique index on `(provider,instance_identity,external_user_id) WHERE verification_method='ONE_TIME_TOKEN'` prevents two ForgePilot users from ever claiming one proven remote identity while permitting conflicting legacy assertions to remain quarantined. Revocation retains ownership; only the same ForgePilot user may re-verify it.
- The same user verifying a matching legacy assertion upgrades/reuses it when unambiguous; otherwise the verified row is distinct and old assertions remain visible as unconfirmed history.
- Revocation prevents new project bindings but does not delete history. A row referenced by any binding is never hard-deleted.

### 2.4 `project_member_scm_binding`

| Column | Contract |
| --- | --- |
| `id`, `project_id`, `user_id` | binding identity and member context |
| `scm_identity_id` | selected user-owned identity |
| `repository_id` | repository against which access was checked; nullable only for unresolved legacy rows |
| `status` | `PENDING_APPROVAL|ACTIVE|REJECTED|REVOKED|SUPERSEDED|LEGACY_UNCONFIRMED` |
| `access_level` | normalized non-secret result such as `READ|WRITE|ADMIN` |
| `access_checked_at` | repository check time |
| `requested_by`, `approved_by` | global account ids; request actor must equal member in normal flows |
| `requested_at`, `decided_at`, `activated_at`, `ended_at`, timestamps | immutable transition history |

Composite FKs enforce:

- `(project_id,user_id) -> project_member`;
- `(user_id,scm_identity_id) -> scm_identity`;
- `(project_id,repository_id) -> scm_repository`.

Partial unique indexes permit at most one `ACTIVE` and at most one `PENDING_APPROVAL` row per `(project_id,user_id)`. Simple status/field checks cover approval timestamps; the Service establishes verification and Provider/instance match.

## 3. Role and membership behavior

### 3.1 Capability union

`ProjectMember` exposes a role set loaded from `project_member_role`. `ProjectAccessService.requireRole(...)` succeeds if any allowed role intersects the member's roles. Direct `getRole()` equality branches are removed throughout backend and frontend. No generic permission engine is added.

Examples:

- `LEADER + DEVELOPER` receives Leader administration and Developer author capabilities.
- `DEVELOPER + REVIEWER` receives author and review-decision capabilities.
- Reviewer/Leader capabilities never require SCM binding merely because another one of the member's roles does; only “my PR” paths require an active compatible binding.

Project APIs return sorted `roles`/`myRoles` arrays in the stable order `LEADER,DEVELOPER,REVIEWER`. The old singular fields are removed in the coordinated backend/frontend release; ForgePilot has no supported external API client contract requiring a compatibility alias.

### 3.2 Leader transfer

Leader is excluded from normal batch-add and role-edit requests. `POST /api/projects/{projectId}/leader-transfer` takes a target member id and an explicit confirmation flag. In one project-row-locked transaction it:

1. verifies actor is the current Leader and target is a project member;
2. removes only the incumbent's `LEADER` role;
3. adds only the target's `LEADER` role;
4. preserves every other role on both users;
5. relies on the unique index and the existing project-row locking pattern before commit.

Normal role editing may add/remove `DEVELOPER` and `REVIEWER`; it cannot remove the last role. A Leader may edit non-Leader roles while retaining `LEADER`.

### 3.3 Candidate search and atomic batch add

`GET /api/projects/{projectId}/member-candidates?q=...&page=...&size=...` is Leader-only, requires at least two trimmed characters except an all-digit exact id, and caps `size` at 20. It returns display name, username, platform id, enabled status, and membership status. Search is case-insensitive over display name/username plus exact numeric id; it never offers an unbounded list or pre-query SCM identities.

`POST /api/projects/{projectId}/members/batch` accepts 1–50 distinct rows of `{userId, roles[]}`. Roles may contain Developer and/or Reviewer but never Leader. The Service locks the project, validates the entire list (existence, enabled state, duplicate input, existing membership, legal nonempty role set), and only then writes all rows. Errors identify the input row. Any error rolls the whole transaction back. Member removal remains outside this version, matching the current product boundary.

## 4. SCM identity verification

### 4.1 Provider client contract

A small SCM-internal interface returns only sanitized data:

```text
verifyCurrentUser(provider, apiBase, oneTimeToken)
  -> provider, normalizedInstance, externalUserId, externalUsername

verifyRepositoryAccess(repository, expectedIdentity, oneTimeToken)
  -> effectiveAccessLevel, checkedAt
```

GitHub uses `/user` and `/repositories/{id}`; GitLab uses `/user` and `/projects/{id}`. Every URL passes `OutboundUrlPolicy`, and `InstanceIdentity` remains the single normalization implementation.

The token is deliberately absent from returned values, entities, audit data, exceptions, and HTTP responses. Token-bearing DTO stringification is redacted and tests search logs/database/JSON for a sentinel secret.

### 4.2 Identity API

- `GET /api/scm/identities`: current user's identities, including revoked/legacy status but never secrets.
- `POST /api/scm/identities/verify`: verify one-time token and create/refresh a `VERIFIED` identity.
- `PATCH /api/scm/identities/{identityId}`: owner changes label/use type only.
- `DELETE /api/scm/identities/{identityId}`: owner revokes identity and its active/pending bindings; history remains.

No endpoint accepts `externalUserId`, `externalUsername`, verification status, owner id, or verified time as caller-controlled facts.

### 4.3 Project binding API and state machine

- `GET /api/projects/{projectId}/scm/binding-options`: current member's compatible verified identities plus active/pending state.
- `POST /api/projects/{projectId}/scm/bindings`: current member submits `{identityId, oneTimeToken}`; current-user and repository checks happen in the same request.
- `GET /api/projects/{projectId}/scm/bindings`: project members see display-safe binding summaries; Leaders see all, non-Leaders see their own plus status-only summaries needed by the member directory.
- `POST /api/projects/{projectId}/scm/bindings/{bindingId}/approve|reject`: current Leader acts on pending state.
- `POST /api/projects/{projectId}/scm/bindings/{bindingId}/revoke`: the owning member revokes an active/pending binding.

State transitions:

```text
verified request + repository access
  default project: old ACTIVE -> SUPERSEDED; new -> ACTIVE
  strict project:  new -> PENDING_APPROVAL (old ACTIVE stays active)

PENDING_APPROVAL --approve--> old ACTIVE -> SUPERSEDED; pending -> ACTIVE
PENDING_APPROVAL --reject----> REJECTED
ACTIVE/PENDING_APPROVAL --revoke/identity revoke--> REVOKED
LEGACY_UNCONFIRMED --member verifies and selects--> historical legacy row stays; new verified flow runs
```

Every transition locks the project and current rows, validates project/user/identity ownership, and writes actor/time. Leader cannot choose or alter another member's identity; approval changes only pending state already chosen and verified by that member.

## 5. PR mapping and authorization

PR ingestion still stores immutable `author_external_user_id` and `author_username`. It resolves `author_user_id` by joining the repository's provider/instance to the one active binding whose verified identity has the same stable external id.

Binding activation/revocation/replacement remaps `author_user_id` for that project's PR snapshots deterministically:

- clear stale mappings for the affected user where the author external id no longer matches the active binding;
- set mappings where repository context and stable external id match;
- never edit the immutable external-id/username snapshots.

“My PR” authorization does not trust `author_user_id` alone. It requires the caller to have `DEVELOPER` among their roles and an active binding whose verified identity matches repository provider + normalized instance + `pull_request.author_external_user_id`. Username, label, use type, and legacy assertions never authorize.

## 6. Frontend behavior

### 6.1 Account settings

- Registration requires display name, username, and password.
- `/account` shows immutable platform id and login username, editable display name/password, and all SCM identities.
- Identity cards show Provider/instance, external username and numeric id, label/use type, verification state/time, and affected project binding summaries. The one-time token input is cleared on submit success or failure and is never repopulated.

### 6.2 Project members

- Every person is rendered as display name, `@username`, and `ID {id}` with role chips.
- Leader search is debounced, paginated, minimum-length gated, selectable in bulk, and shows aggregate SCM readiness only.
- The batch form applies common roles then permits per-row overrides, previews all rows, and submits once. Per-row validation errors map back to the preview.
- Role edits use checkboxes for Developer/Reviewer. Leader transfer is a separate confirmed action. Member removal controls are not added.
- Each member card shows binding state, selected identity label/use type, external username/id, access result, and checked time. It never exposes a token.
- The signed-in member selects and verifies their own binding in their card. In a strict project the UI explains that the old binding remains active until approval.

## 7. Compatibility, deployment, and rollback

V8 is appended; V1–V7 and all immutable formal-evaluation assets remain untouched. The backend/frontend API cutover deploys together. Existing accounts get username as display name. Existing role values copy exactly. Existing administrator-entered SCM data becomes visible but non-authorizing legacy evidence.

Deployment gate:

1. take a database backup;
2. run the empty schema and one representative V7-to-V8 migration fixture;
3. deploy backend and frontend together;
4. run Compose health plus browser flows;
5. roll forward for application defects; restore the backup only if V8 itself must be reversed.

## 8. Security and concurrency checks

- Candidate search and all ids preserve the existing non-enumeration rule: outsiders receive the same not-found behavior as nonexistent projects.
- Cross-project and cross-user ids are rejected by composite foreign keys and service-scoped queries.
- Batch add, role edits, Leader transfer, binding replacement, and approval reuse project-row locking so invariant decisions serialize.
- Provider/API errors do not disclose whether another ForgePilot user owns an identity; claim conflicts return a generic already-claimed response.
- Repository credentials remain encrypted and separate. One-time personal tokens never enter `ScmSecretCipher` or persistence.
- Provider payloads are reduced to one normalized access level; arbitrary response documents are not stored.
