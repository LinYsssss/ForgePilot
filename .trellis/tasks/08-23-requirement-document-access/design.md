# Design — 需求文档阅读与导出

## 1. Boundaries

- `requirement` 继续拥有需求与附件关系，所有新 HTTP 路由位于现有 `RequirementController`。
- `knowledge` 继续拥有文档正文，通过一个最小的内容 DTO 和 `KnowledgeService` 读取方法对外提供，不暴露 JPA 实体或 Repository。
- 前端只修改现有 requirement feature，不新增路由、全局 store 或组件层级。

## 2. HTTP contracts

### Read content

`GET /api/projects/{projectId}/requirements/{requirementId}/attachments/{documentId}/content`

```json
{
  "documentId": 42,
  "fileName": "payment.md",
  "mediaType": "text/markdown",
  "text": "# Payment requirement\n..."
}
```

The service first proves that the Requirement belongs to the project and that the document id is attached to that Requirement. `KnowledgeService` then applies normal project membership and returns the stored title/text. Missing, cross-project, cross-requirement and non-member reads all use the existing 404 isolation contract.

### Download content

`GET /api/projects/{projectId}/requirements/{requirementId}/attachments/{documentId}/download`

The controller reuses the same content lookup and returns UTF-8 text with `Content-Disposition: attachment` and the stored filename. `.txt` maps to `text/plain`; `.md` maps to `text/markdown`. There is no second stored copy and no temporary file.

### Upload policy

Keep the existing POST body unchanged. Before ingestion, the requirement attachment service checks the filename suffix against `.txt/.md` case-insensitively. Existing title, Unicode and size validation remains authoritative for content validity.

## 3. Frontend interaction

- Keep the current structured Revision panel and place a visible `导出 Markdown` button in its heading. A small page-local formatter serializes title, optional background/description and ordered AC into a Blob download.
- Keep the existing attachment upload/list panel. Each record gains `查看原文` and a normal same-origin `下载` link. Selecting a document requests only that document's content and renders it below the list in a wrapped `<pre>` region.
- Loading, failure, no-selection and empty-list text are explicit. Document selection is local `ref` state; no cache, retry, polling or global store is introduced.
- `.md` is shown as source text in V1. This preserves content safely and avoids both a runtime dependency and handwritten Markdown parsing.

## 4. AI data flow

No production AI path changes:

```text
Requirement + AC
      |
      v
existing scoped vector search ---- current Requirement attachment chunks
      |
      v
existing one-shot Implementation Guidance
```

The implementation only strengthens focused regression evidence and UI wording so the attachment source is visible. Guidance still takes the top relevant chunks within its existing prompt budget; it does not insert an entire 5 MiB document.

## 5. Word/PDF compatibility

Endpoint and frontend transport names say `document content`, not `text attachment`, so a later extracted-text response can keep the same reading surface. That is the only preparation made now. Supporting Word/PDF later requires an explicit raw-file storage and extraction design; adding an unused extractor interface or binary column in this task would not make V1 safer or more functional.

## 6. Rollback and risk

- No migration or persisted data rewrite exists; rollback removes the two GET routes and frontend controls without touching stored attachments.
- The main risks are authorization scope and filename/header correctness. Cover those by extending the existing attachment/API test path, not by creating a combinatorial role/format matrix.
