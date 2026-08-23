# Implementation plan — 需求文档阅读与导出

## 1. Product and API contract

- [x] Update the authoritative PRD/Architecture sections for member read/download, LEADER upload, `.txt/.md`, structured Markdown export, and existing Guidance reuse.
- [x] Add the smallest document-content DTO and Knowledge service read method; do not expose entity/repository types.
- [x] Add attachment ownership lookup plus backend suffix/MIME mapping in the existing Requirement attachment service.
- [x] Add the content and download GET routes with existing project isolation and UTF-8 download headers.

## 2. Frontend

- [x] Extend `frontend/src/features/requirement/api.ts` with the typed content read contract and stable download URL builder.
- [x] Refine `RequirementDetailPage.vue` into clearly labelled structured-content and requirement-document sections without moving existing workflow actions.
- [x] Add `导出 Markdown`, `查看原文`, and `下载` controls; render selected text safely with wrapping and explicit load/error/empty states.
- [x] Preserve LEADER-only upload/promote controls and member-wide read/download visibility at 1440/768/390 layouts.

## 3. AI evidence and focused tests

- [x] Extend existing attachment/HTTP tests to cover readable content, download filename/MIME, and one cross-scope 404; do not add a broad role/extension matrix.
- [x] Extend the existing Guidance scoped-knowledge test so a current Requirement attachment chunk is present in the prompt/source response; do not change the AI runtime.
- [x] Extend the existing requirement page test for document selection, download/export controls and exported Markdown; reuse its fetch fixture rather than adding a parallel suite.

## 4. Verification

- [x] Run focused backend tests for Requirement attachments and Guidance.
- [x] Run frontend lint, strict typecheck, focused requirement test, full test and production build.
- [x] Run full backend `./mvnw -B -ntp verify` once after the final change.
- [x] Run `git diff --check` and confirm there is no migration, dependency, route/menu, Markdown parser or second AI flow.

## Expected touched areas

- `backend/.../requirement`: controller, attachment service/repository, focused tests.
- `backend/.../knowledge`: one content read DTO/facade only.
- `frontend/src/features/requirement`: API types and existing detail page.
- Existing product/architecture/manual-acceptance documentation only where the new observable contract belongs.

## Stop gate

Do not run `task.py start` or edit product code until the user reviews and explicitly approves this PRD/design/implementation plan.
