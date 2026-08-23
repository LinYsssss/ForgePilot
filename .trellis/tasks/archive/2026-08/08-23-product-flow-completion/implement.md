# Implementation plan — 前端完整功能展示与后端链路补全

## 1. Contract and decision update

- [x] Add D017 for the approved six-entry product surface, read-only Workbench, prominent contextual AI features, and visible vector metadata.
- [x] Update `docs/v2/README.md`, `PRD.md`, `ARCHITECTURE.md`, `IMPLEMENTATION-PLAN.md`, `AGENTS.md`, and the frontend design/component specs so route/navigation rules match the approved product.
- [x] Keep all evaluation evidence and the eight-package/16-table/single-engine rules unchanged.

## 2. Backend Knowledge and attachment flow

- [x] Add `KnowledgeDocumentView` and one grouped Knowledge read repository that returns document status plus real chunk/vector profile metadata without text or vectors.
- [x] Add project Knowledge list/create/promote endpoints in the existing `knowledge` package.
- [x] Add `RequirementAttachmentService` and Requirement controller endpoints for list/create/promote.
- [x] Ensure attachment Document ingestion and `requirement_attachment` save share one transaction.
- [x] Change vector search SQL/signature to accept Requirement scope and hard-filter public/current Requirement documents.
- [x] Pass snapshotted `review.requirementId` from the single Review pipeline.
- [x] Add one focused Knowledge/attachment integration scenario covering relation persistence, promotion copy, and same-project cross-Requirement exclusion.

## 3. Backend Guidance and SCM read

- [x] Make Guidance build a retrieval query, embed it with the configured Knowledge profile, and use Requirement-scoped TopK retrieval.
- [x] Define one strict Guidance JSON schema and parse `checklist`, `rules`, and `risks`; return recalled Knowledge references.
- [x] Update focused Guidance tests for schema, Knowledge prompt content, structured serialization, roles, and one-shot/no-persistence behavior without duplicating AI Gateway tests.
- [x] Add `GET /api/projects/{projectId}/scm/repositories` returning zero/one secret-free item and add one focused API assertion.

## 4. Frontend shell, routes, and branding

- [x] Copy `logo-app.png` and `logo-lockup.png` to stable `frontend/public/brand/` paths and wire favicon.
- [x] Rebuild `AppShell` as desktop sidebar + workspace header; keep skip link, account/password/logout, route transition, reduced motion, and existing breakpoints.
- [x] Add the six approved navigation entries and route helpers; preserve project query for all scoped destinations.
- [x] Add `/workspace`, `/knowledge`, and `/repositories`; make `/projects/:id/settings` a compatibility redirect.
- [x] Update the existing route/shell test rather than adding a parallel navigation suite.

## 5. Frontend product pages

- [x] Add typed Knowledge API functions and `KnowledgePage.vue` with project selection, leader-only file upload, real document/vector summaries, status list, and errors.
- [x] Add typed SCM list function and `RepositoryPage.vue` with reloadable safe state, conditional register/update forms, no internal-id input, and write-only credentials.
- [x] Add `WorkspacePage.vue` that aggregates real Requirement/Review/Knowledge/SCM lists, prominently explains the three contextual AI stages, shows vector indexing totals, recent records, and shortcuts.
- [x] Update project cards/members navigation to reach Knowledge, Repository, Requirements, and Reviews naturally.
- [x] Remove the obsolete unavailable Knowledge/settings implementation after the compatibility redirect exists.
- [x] Extend Requirement API/detail page with attachment list/upload/promote and structured Guidance sections plus Knowledge sources.
- [x] Update Review text/labels so the single AI engine and vector semantic similarity are obvious while Finding state and final human Decision remain distinct.

## 6. Focused validation and refinement

- [x] Run targeted backend tests for Knowledge/attachments, Guidance, SCM API, and Review pipeline compilation/regression.
- [x] Run frontend lint, typecheck, and focused route/SCM/Requirement/Review/new-page tests during implementation.
- [ ] Inspect the application at 1440, 768, and 390 CSS px using the existing manual acceptance rules; verify both Logo assets, sidebar collapse, long model/title/path text, empty/error/disabled states, and no page-wide horizontal overflow.
- [x] Run `backend/./mvnw -B -ntp verify` once after focused tests are green.
- [x] Run frontend full `npm test` and `npm run build` once after lint/typecheck are green.
- [x] Audit: 16 business tables, 8 backend top-level packages, no new runtime dependency, no raw vector response, no credential response, no second AI/Review pipeline, and immutable evaluation assets untouched.

## 7. Finish gate

- [x] Run full-scope `trellis-check` against `prd.md`, `design.md`, and this plan; fix only concrete findings.
- [x] Update relevant Trellis specs for the accepted navigation/Workbench/AI/vector display convention.
- [x] Write `result.md` with actual validation outputs and any honest remaining limitation.
- [x] Present logical commit grouping and all user-owned/unrecognized files; do not commit or push without confirmation.

## Rollback points

- After section 3: backend contracts are independently verifiable; no schema rollback is needed.
- After section 4: old saved Settings links already redirect before the obsolete page is removed.
- After section 5: every new page consumes typed APIs; route/page changes can be reverted without data migration.
- If structured Guidance proves incompatible with the configured provider, stop and report it; do not add fallback prose, a second repair call, or a second runtime.
