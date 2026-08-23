# Design — 前端完整功能展示与后端链路补全

## 1. Design principles

1. The visible product follows the real causal chain: project context → knowledge and repository → requirement and AI guidance → review evidence → human decision.
2. Workbench is a read-only composition of real APIs, not a new domain, table, cache, metrics service, or automation runtime.
3. AI and vector retrieval are prominent through their real inputs, outputs, and evidence. No synthetic confidence, fake telemetry, or raw-vector display is introduced.
4. Backend work fills only user-flow gaps. It reuses the 16-table model and the existing eight packages, AI Gateway, Knowledge ingestion, and single Review Engine.
5. Validation is focused on the new contracts, followed by one full verification pass.

## 2. Product and documentation decision

The user explicitly superseded the former “exactly three top-level entries / no Workbench or standalone Knowledge/Repository pages” choice. Add `D017` and update the authoritative PRD/Architecture/implementation status and frontend design contract.

The new product navigation is:

```text
/workspace       工作台（默认首页）
/projects        项目
/requirements    研发需求
/knowledge       项目知识
/repositories    仓库接入
/reviews         代码审查
```

Existing detail routes remain. `/projects/:id/settings` becomes a compatibility redirect to `/repositories?project=:id`; it is no longer a product page. Members remain at `/projects/:id/members`.

This decision does not authorize a general-purpose Workbench, Agent, chat assistant, repository browser, metrics platform, or second AI/Review pipeline.

## 3. Frontend design

### 3.1 Application shell and branding

- Copy the provided images into `frontend/public/brand/` so Vite and nginx serve stable paths.
- `logo-lockup.png`: sidebar brand and login brand.
- `logo-app.png`: visible login app emblem and favicon from `frontend/index.html`.
- Replace the desktop three-zone header with a two-column application frame:
  - fixed-width sidebar: brand, six navigation links, short product descriptor;
  - workspace column: compact account header and routed main content.
- At the existing `64rem` breakpoint the sidebar becomes a top block and navigation scrolls horizontally. At `42rem` account and content actions stack. No new breakpoint or UI library is added.
- `navigationTarget` preserves `?project=` for Workbench, Requirements, Knowledge, Repositories, and Reviews.

### 3.2 Workbench

`WorkspacePage.vue` owns a project selector. For the selected project it loads in one `Promise.all`:

```text
listRequirements
listReviewActivity
listReviews
listProjectKnowledge
listScmRepositories
```

All counts, distributions, and recent rows are computed locally. A failed request makes the dashboard show the real error rather than partial invented state.

Reading order:

1. AI capability chain: Requirement Quality → knowledge-enhanced Guidance → AI Review, each with truthful inputs/outputs and links.
2. Project pulse: requirement totals/statuses, review-activity distribution, Knowledge documents/embedded chunks, SCM connected state.
3. Recent Requirements and Reviews.
4. Direct actions to members, repository, knowledge, requirements, and reviews.

### 3.3 Project Knowledge

`KnowledgePage.vue` loads project choices and the selected project's public documents. LEADER sees a `.txt/.md` file input and upload button; other members see read-only data.

The browser reads the selected file as text and sends `{title, text}`. Backend remains the authority for title/content/5 MB validation. The form shows the selected filename and request pending/error state.

The top summary exposes the actual vector capability:

- document count and READY count;
- total chunks and embedded chunks;
- observed vector dimension(s);
- configured provider/model/version from stored chunk metadata.

Each document row shows status, chunk/index counts, dimension, profile, and update time. Raw vector values and a manual search playground are deliberately absent.

### 3.4 Repository integration

`RepositoryPage.vue` replaces the SCM portion of the former settings page:

- load project and `GET /scm/repositories` on selection;
- show registration only when no repository exists;
- show safe current metadata and update form when one exists;
- update uses the loaded id; there is no id input;
- token and webhook secret are never stored in reactive state longer than the request and are cleared on success.

### 3.5 Requirement and Review AI surfaces

Requirement detail loads attachments with the existing detail bundle. A dedicated Knowledge Context panel lists indexed attachment metadata, gives LEADER an upload action, and allows copy-promotion to public Project Knowledge.

The current Quality and Guidance panels are visually grouped under an “AI 研发辅助” heading. Guidance output renders:

- `checklist[]` as implementation steps;
- `rules[]` as project/requirement rules;
- `risks[]` as risks;
- `knowledgeSources[]` as actual recalled title/excerpt references.

Review pages retain their current behavior. Text and labels make the AI role explicit: the single Review Engine compares Requirement/AC + vector-recalled Knowledge + Diff; humans own Finding status and final Decision. Existing knowledge scores are labelled “向量语义召回相似度”.

## 4. Backend contracts

### 4.1 Knowledge document view

Add a public read DTO in `knowledge`:

```text
KnowledgeDocumentView
  id, projectId, sourceType, sourceRequirementId, title, status, failureReason,
  createdAt, updatedAt,
  chunkCount, embeddedChunkCount, embeddingDimension,
  embeddingProvider, embeddingModel, embeddingVersion
```

No full document text and no embedding vector are returned.

A small `KnowledgeReadRepository` uses grouped SQL over `knowledge_document` and `knowledge_chunk`. It exists because vector dimension is intentionally not mapped on the entity and the UI needs one read model; it is not a second storage abstraction.

### 4.2 Project Knowledge API

```http
GET  /api/projects/{projectId}/knowledge/documents
POST /api/projects/{projectId}/knowledge/documents
POST /api/projects/{projectId}/knowledge/documents/{documentId}/promote
```

- GET: any project member, public Project Knowledge only.
- POST/promote: LEADER only through existing `KnowledgeService` authorization.
- POST body: `{ "title": string, "text": string }`.
- POST returns the stored `KnowledgeDocumentView` after synchronous ingestion.

`KnowledgeController` lives in the existing `knowledge` package and resolves `Principal` through `UserDirectory`, matching other controllers.

### 4.3 Requirement attachment API and transaction

```http
GET  /api/projects/{projectId}/requirements/{requirementId}/attachments
POST /api/projects/{projectId}/requirements/{requirementId}/attachments
POST /api/projects/{projectId}/requirements/{requirementId}/attachments/{documentId}/promote
```

`RequirementAttachmentService` owns the relationship:

1. verify project membership/LEADER role and project-scoped Requirement;
2. call Knowledge ingestion for a scoped document;
3. save `RequirementAttachment(projectId, requirementId, documentId)`;
4. return the document view.

Steps 2–3 share the outer Spring transaction, so an attachment Document cannot commit without its relationship. Listing starts from `RequirementAttachmentRepository`, then reads the related document views; the relationship remains the ownership fact source.

Promotion delegates to Knowledge's existing copy behavior after confirming the relationship belongs to this Requirement.

### 4.4 Requirement-scoped vector search

Change the search contract to receive nullable `requirementId`:

```java
search(projectId, actorId, requirementId, queryVector, limit)
```

The native SQL joins `knowledge_document` and applies:

```sql
c.project_id = :projectId
AND (d.source_type <> 'REQUIREMENT_ATTACHMENT'
     OR d.source_requirement_id = :requirementId)
```

When `requirementId` is null, the second branch cannot match; only public knowledge remains. `ReviewPipeline` passes its snapshotted Requirement id. Guidance passes the current Requirement id. No Java post-filter exists.

### 4.5 Structured, knowledge-enhanced Guidance

Guidance builds a retrieval query from the current revision and AC text, creates one query embedding with the configured Knowledge embedding model, and searches TopK 8 through the scoped search above.

The chat prompt includes labelled, untrusted Knowledge excerpts. It uses one strict JSON schema:

```json
{
  "checklist": ["string"],
  "rules": ["string"],
  "risks": ["string"]
}
```

The response record adds the three arrays plus `knowledgeSources` derived from recalled chunks. Parsing happens once with the existing Jackson mapper. Invalid structured content is an explicit API error; there is no secondary repair pipeline or fallback prose.

### 4.6 SCM read

Add:

```http
GET /api/projects/{projectId}/scm/repositories
```

It returns an empty or one-item array because the database already enforces one repository per project. Any member may read the secret-free metadata; only LEADER may register or update. The existing response type remains the contract and still contains no credential fields.

## 5. Compatibility and data safety

- No migration or stored data rewrite.
- Existing project, requirement, review, finding, and decision responses remain compatible except the intentionally changed Guidance response shape.
- The former Settings route redirects rather than breaking saved links.
- Formal evaluation config, corpus, ledger, and raw outputs are untouched.
- The two root Logo files are user-provided inputs. Implementation copies them into the frontend asset tree; it does not delete or overwrite the originals until the user decides how to commit them.

## 6. Validation scope

Focused automated checks only:

- SQL retrieval proves public/current attachment included and foreign Requirement attachment excluded.
- Attachment API proves both rows commit together and promotion copies.
- Guidance proves Knowledge appears in the prompt, schema is non-null, and arrays/sources serialize.
- SCM GET proves zero/one safe result and no secret field.
- Frontend route/shell, Workbench aggregation, Knowledge upload/vector metadata, SCM refresh, attachment actions, and structured Guidance rendering.

Then run backend `verify` and frontend lint/typecheck/test/build once. No load test, Compose capacity rerun, browser E2E expansion, or evaluation rerun is planned.

## 7. Rollback

Because there is no schema change, rollback is code-only:

1. revert route/shell and new pages;
2. revert added controllers/read DTOs and restore old Guidance response;
3. restore the previous search signature and query;
4. revert D017/spec changes together so documentation never describes a surface the code lacks.

