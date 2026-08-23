# Pre-implementation audit

## Current shell and brand evidence

- `AppShell.vue` owns the only signed-in navigation and currently renders it inside `aside.app-sidebar`; replacing that single boundary is sufficient to move all routes to a top navigation without duplicating markup.
- Project-query preservation is isolated in `navigationTarget`; the layout change must not touch its allowed-path list.
- `LoginPage.vue` is the only surface that currently places `logo-app.png` and `logo-lockup.png` next to each other. The Shell uses only the lockup and `index.html` uses only the app icon as favicon.
- Base responsive rules already use only the approved `64rem` and `42rem` boundaries. The new header can reuse both and needs no token or breakpoint addition.

## Test impact

Existing route behavior assertions remain valid. Only a focused semantic layout assertion is needed:

- `frontend/tests/routes.spec.ts`: assert signed-in navigation belongs to `header.app-header`, the sidebar is absent, lockup is present, and six links remain.
- `frontend/tests/journey.spec.ts` and `frontend/tests/scm.spec.ts`: their six-link assertions remain compatible and need no parallel test suite.
- Login behavior is already exercised through the full App journey. A single DOM assertion that the login page renders one visible brand image is sufficient if an existing login-focused test has a natural location; do not build a screenshot matrix in unit tests.

## Database target proof

Flyway defines exactly 16 business tables. `user_account` is the only preserved table. The explicit destructive target is:

1. `project`
2. `project_member`
3. `requirement`
4. `requirement_revision`
5. `acceptance_criterion`
6. `knowledge_document`
7. `requirement_attachment`
8. `knowledge_chunk`
9. `scm_repository`
10. `pull_request`
11. `pull_request_requirement_event`
12. `review`
13. `finding`
14. `finding_event`
15. `ai_call_log`

The operation is executed only after a final read confirms `user_account` still contains exactly enabled `ysainlin`. All 15 table names are resolved constants from tracked migrations; no glob, environment-derived table name, volume deletion, or recursive target is used.

## Planning conclusion

No backend code, schema migration, dependency, route, or new token is required. The change is one Shell boundary, one login surface, shared/base styling, three compressed top-level templates, focused test updates, documentation decision D018, deployment, and the separately authorized one-time database transaction.
