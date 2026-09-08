# Implementation and validation

1. Add migration, requirement assignment/query/cleanup and review decision authorization.
2. Correct remote actions; implement GitLab merge and exact-head/result checks.
3. Extend existing notifications and public knowledge reading.
4. Update frontend phase, assignment, reasons/history, identity hints and knowledge content.
5. Extend existing tests for meaningful changed behavior; update contracts and structural migration count.
6. Run affected tests while developing; final pinned Docker backend verify and frontend lint/typecheck/tests/build once; rerun only failures/affected changes.
7. Review complete diff and report actual validation and limitations. Commit/push/deploy require user authorization, supplied on 2026-09-08. Schema is additive; a rollback must preserve migration compatibility.

No new testing framework, snapshots, load tests or rerun of immutable evaluation. Remote mutation tests use local HTTP stubs.

## Execution record — 2026-09-08

- Steps 1–5 completed within existing modules; V14 adds one nullable reviewer FK and no business table.
- Step 6 completed: full backend run, correction of the sole stale audit expectation, failed-class recheck with packaging; frontend lint/typecheck/tests/build passed. See `validation.md` for exact evidence and limits.
- Current Markdown contracts, developer specifications and all six HTML deliverables synchronized. HTML tags/local anchors, shell syntax and diff whitespace checks passed.
- User-requested code review completed; see `code-review.md`. Fixed historical Review handler lookup, reopened Finding grouping and missing developer-assignment target-role validation. Reused the existing terminal helper and removed an unused import.
- Audit follow-up validation passed: 24 affected backend cases plus packaging; frontend lint/typecheck, 44 cases and production build. Updated corresponding contracts, guides and HTML.
- At the implementation-validation checkpoint, no commit, push, deployment, live SCM merge or live notification had been performed.
- Final static review passed after the audit updates; the exact one-commit file list is in `commit-plan.md`.
- The user then authorized committing, pushing and deploying the latest code on 2026-09-08. Preflight confirmed that `fp-demo` configuration matched the running containers, the database was at V13 with 21 business tables, and no reviews were pending/running.
- Commit `746e40b` was pushed to `origin/main`; all four CI jobs passed, including the single 379-case backend run, frontend gates and two fresh-volume cold starts.
- Database backup was validated, then backend/frontend were rebuilt and replaced while retaining the PostgreSQL container and volume. V14 applied successfully; baseline counts, container/HTTP health and public asset hashes passed. See `deployment.md` for the completed rollout and `validation.md` for CI evidence.
- Deployment documentation is a separate work commit, followed by the task archive and session journal commits; these follow-up records are included in the user's push authorization.
