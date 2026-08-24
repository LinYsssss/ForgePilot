# Current-state evidence

## Account and member model

- `backend/src/main/resources/db/migration/V2__auth_project.sql:6` creates `user_account` without a display-name column.
- `backend/src/main/resources/db/migration/V2__auth_project.sql:35` combines membership, one role, and one project-level SCM identity in `project_member`.
- `backend/src/main/resources/db/migration/V2__auth_project.sql:59` enforces at most one Leader by a partial index over the single `role` column; the Service enforces the at-least-one half.
- `backend/src/main/java/com/forgepilot/auth/AuthController.java:30` registers only username/password, while `UserDirectory` is the established cross-package account-query facade.
- `backend/src/main/java/com/forgepilot/project/ProjectMemberService.java:44` resolves one exact username and inserts one member. Its update path lets a Leader assign an unverified external id and username to another member.
- `backend/src/main/java/com/forgepilot/project/ProjectAccessService.java:30` authorizes against `member.getRole()`. Direct single-role branches also exist in requirement, review, SCM, and frontend code, so changing only the member response would be unsafe.

## SCM and PR identity

- `backend/src/main/resources/db/migration/V5__scm.sql:11` gives each project one repository identified by provider, normalized instance, and stable external repository id.
- `backend/src/main/resources/db/migration/V5__scm.sql:65` stores immutable PR-author external-id and username snapshots. `author_user_id` is explicitly recomputable and must not become the authorization key.
- `backend/src/main/java/com/forgepilot/scm/PullRequestAssociationService.java:112` currently decides “my PR” from the member row's single role and external id. It must instead resolve the active project binding in the repository's provider/instance context.
- Existing `GitHubClient`, `GitLabClient`, `OutboundUrlPolicy`, `InstanceIdentity`, `ScmSecretCipher`, and repository DTO patterns are reusable. The member verification flow must not reuse `encrypted_token`: repository integration credentials and a person's one-time proof are different security domains.

## Frontend and compatibility surface

- `frontend/src/features/project/ProjectMembersPage.vue:43` assumes one `myRole`; the page adds one exact username and exposes Leader-editable SCM-id fields.
- Single-role assumptions also occur in project, requirement, knowledge, SCM, and review pages. The API model therefore needs one coordinated `myRole -> myRoles` cutover.
- The account menu currently supports password change only. A non-top-level account settings route can host display-name and reusable SCM-identity management without adding a seventh primary navigation entry.
- The repository currently has 8 backend top-level packages, 16 business tables / 7 Flyway migrations, 316 backend tests, 6 primary navigation entries / 10 frontend routes, and 35 frontend tests. The planned data model adds three tables and one migration; route and test counts must be re-measured after implementation rather than pre-declared.

## Boundary conclusion

- `auth` owns accounts and the read-only directory facade.
- `project` owns membership, role sets, Leader transfer, candidate search, and batch writes.
- `scm` owns global SCM identities, provider verification, repository access checks, and project bindings; it may depend on `project`, but `project` must not depend back on `scm`.
- The members page composes the project member/candidate responses with SCM readiness/binding summaries. This preserves the dependency direction while presenting one workflow to the user.
