# Current attachment flow audit

## Confirmed implementation facts

- `RequirementDetailPage.vue` already accepts `.txt,.md`, reads the selected file as text, and sends `file.name + text` through the existing attachment API. Only LEADER sees the upload form.
- `RequirementAttachmentService.list` already permits every project member and returns metadata through `KnowledgeDocumentView`; that view deliberately omits `knowledge_document.text`.
- `knowledge_document` already stores `title` and `text`; `requirement_attachment` and database constraints pin each private document to one project and one requirement. No schema change is needed for text viewing.
- `KnowledgeUploadValidator` already enforces nonblank input, valid Unicode and a 5 MiB UTF-8 limit. The backend does not currently enforce the `.txt/.md` suffix.
- `ImplementationGuidanceService` embeds the structured Requirement/AC query and calls `KnowledgeService.search(projectId, actorId, requirementId, ..., 8)`. That query includes public project knowledge plus the current requirement's attachment chunks and excludes other requirements' private attachments.
- The frontend has no dependency capable of safely rendering Markdown. A wrapped `<pre>` reader preserves all stored text without adding a parser or an HTML/XSS surface.

## Minimal implementation conclusion

Add one content DTO/facade and two GET representations (JSON content for reading, attachment response for downloading), both reached only after project/requirement/document ownership checks. Keep metadata lists small. Generate the structured Requirement Markdown in the already-loaded frontend page. Reuse Guidance unchanged and extend its focused regression evidence to cover a requirement attachment.

## Deferred Word/PDF boundary

The current database stores extracted text, not uploaded bytes. Word/PDF support therefore cannot honestly be prepared by adding MIME labels alone: it will later need a decision about raw binary storage and extraction dependencies. This task keeps route/type names document-generic and centralizes the two allowed suffix/MIME mappings, but deliberately does not add storage columns, extractor interfaces, OCR hooks, plugin registries, or dormant implementations.
