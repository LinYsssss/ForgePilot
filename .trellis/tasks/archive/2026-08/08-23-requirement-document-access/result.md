# Result — 需求文档阅读与导出

## Delivered

- 后端限制 LEADER 上传 `.txt/.md`，并为项目成员提供带需求归属校验的正文读取和 UTF-8 下载。
- 前端需求详情分为“结构化需求”和“需求文档”，支持 Markdown 导出、文档上传、原文查看和下载。
- AI 继续复用现有 Requirement-scoped Guidance 召回，不新增运行流程。

## Verification

- Backend focused tests: 7/7 passed.
- Backend `./mvnw -B -ntp verify`: 316 passed, 0 failed, 0 errors, 0 skipped; build success.
- Frontend focused requirement tests: 3/3 passed.
- Frontend full tests: 35/35 passed; lint, strict typecheck and production build passed.
- `git diff --check` passed; no migration, dependency, top-level package, top-level frontend route, Markdown parser or second AI flow was added.
