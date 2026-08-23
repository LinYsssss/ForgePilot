# Current product-flow gap audit

## Scope inspected

- Product authority: `docs/v2/PRD.md`, `ARCHITECTURE.md`, `IMPLEMENTATION-PLAN.md`, and `DECISIONS.md`.
- Frontend shell, routes, feature pages, tests, and API modules under `frontend/`.
- Backend controllers and the `knowledge`, `requirement`, `scm`, `review`, and `ai` services involved in the main flow.
- Archived frontend capability and batch-2 Knowledge/SCM task artifacts.

## Confirmed capability map

| Product capability | Current frontend | Current backend | Required action |
|---|---|---|---|
| Account/session/password | Complete | Complete | Preserve |
| Project/member/SCM identity | Complete | Complete | Preserve and expose from project navigation |
| Requirement/AC/revision/status/assignment | Complete | Complete | Preserve |
| Requirement quality | Complete | Complete | Make the AI surface more prominent, preserve behavior |
| One-shot Guidance | Free-text result | Requirement + AC only | Add scoped Knowledge retrieval and structured result |
| Project Knowledge | Explicit unavailable placeholder | Internal service only | Add list/create HTTP and real page |
| Requirement attachment | No UI | Document can be created, relationship is not written | Add requirement-owned transaction and detail UI |
| SCM configuration | Write-only page state; refresh loses it | POST/PATCH only | Add safe GET/list and bind update to loaded record |
| Review/Finding/Decision | Complete evidence and human-loop pages | Complete | Preserve; fix Knowledge scope passed into Review search |

## Information architecture finding

The current `AppShell` and route contract are deliberately hard-coded to three entries and seven product routes. The user has explicitly superseded that product choice and approved a read-only Workbench. Six entries are the smallest coherent layout:

1. Workbench
2. Projects
3. Requirements
4. Project Knowledge
5. Repository Integration
6. Reviews

Members remain project-scoped because a global members page without a selected project has no coherent business meaning. A sidebar is more stable than squeezing six entries into the current centered header; at the existing `64rem` breakpoint it can collapse to a horizontal top navigation without adding another breakpoint.

Workbench should aggregate existing real list responses in the browser. It needs no dashboard table, cache, metrics service, or new backend endpoint. Its first section can explain and link the three real AI stages—Requirement Quality, knowledge-enhanced Guidance, and the single AI Review Engine—without inventing scores or activity.

## Backend findings

### Knowledge isolation

`ChunkSearchRepository.search` reads only `knowledge_chunk` and filters only `project_id`. It must join `knowledge_document` and apply the authoritative predicate for `REQUIREMENT_ATTACHMENT`. The search contract needs a nullable Requirement id: a null id means public project knowledge only.

### Attachment ownership

`KnowledgeService.createRequirementAttachment` creates and indexes the scoped Document, but no production caller writes `requirement_attachment`. The smallest correct owner is a requirement-package service that calls Knowledge and writes the relationship inside one transaction. No schema change is needed.

### Knowledge HTTP surface

`KnowledgeDocument` already contains every list/status field needed. Add small DTOs and list/create/promote controller operations; do not expose the stored full text in list responses. Upload can remain JSON title + UTF-8 text, with the browser file picker reading `.txt`/`.md` files before sending. This reuses the existing validator and avoids a second multipart upload contract.

The user also wants the vector capability to be visible. The real metadata already exists on `knowledge_chunk`: provider, model, version, dimension, and whether `embedding` is non-null. A grouped read query can add chunk count, embedded chunk count, dimension, and profile metadata to each document view without returning raw vectors or adding a schema object. Workbench can aggregate those views locally; Review already stores a similarity score for each recalled excerpt, so the frontend only needs to label that score accurately.

### SCM read

`ScmRepositoryRepository` has project-scoped lookup primitives but no project-current controller GET. Because the schema permits zero or one repository, `GET /scm/repositories` can return an empty or one-item array and reuse the existing secret-free response shape.

### Guidance

`ImplementationGuidanceService` passes `schema=null` and builds a prompt from Requirement/AC only. It can reuse the configured embedding model and scoped Knowledge search, then require a three-array JSON schema and parse once into the response record. Recalled document/chunk excerpts can be returned as read-only references so the UI can show what knowledge informed the answer. No persistence or new runtime is needed.

## Validation recommendation

Keep only tests that prove the newly introduced contract rather than repeating existing suites:

- one Knowledge integration scenario for public/current/foreign attachment retrieval plus relationship persistence;
- focused Guidance assertions for knowledge in the prompt and the structured response;
- focused SCM GET API assertion;
- update the existing frontend route/shell test and add focused API/render assertions for Workbench, Knowledge, attachments, SCM refresh, and structured Guidance;
- run the existing full backend verify and frontend lint/typecheck/test/build once at the end.

## Deferred items

- Synchronous ingestion remains synchronous. Introducing status workers or retry queues would violate the requested simplicity and is not needed for the user-visible chain.
- The formal evaluation stale-path issue is unrelated to the product UI chain and remains outside this task.
- Immutable formal evaluation assets remain untouched.
