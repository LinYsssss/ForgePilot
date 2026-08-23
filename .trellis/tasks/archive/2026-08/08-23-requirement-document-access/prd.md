# 需求文档阅读与导出

## Goal

允许 LEADER 直接上传 `.txt` / `.md` 需求文档，让项目成员在需求详情页阅读和下载；同时保留现有标题、背景、描述、AC 的结构化展示，并可导出为 Markdown。员工请求 AI 实现建议时，继续由现有一次性 Guidance 读取当前需求可见的附件 Chunk，不建立新的 AI 流程。

## Background

- 现有需求详情页已有 `.txt/.md` 选择和上传，但附件列表只返回元数据，项目成员不能阅读或下载正文。
- 附件已使用 `knowledge_document.text` 保存文本，并由 `requirement_attachment` 固定需求归属；本任务不需要新表或迁移。
- 现有 Implementation Guidance 已经按 `project_id + requirement_id` 召回公共项目知识和当前需求附件，只需用现有测试钉住这个用户结果。

## Requirements

### R1 — 文件范围与上传

- 第一版只接受扩展名不区分大小写的 `.txt` 和 `.md`，服务端必须校验，不仅依赖前端 `accept`。
- 继续使用现有 JSON `title + text` 上传契约、UTF-8 文本与 5 MiB 上限；不改 multipart，不保存第二份原文。
- 只有 LEADER 可上传和提升附件；不新增替换、删除、版本化或批量上传。

### R2 — 员工阅读与下载

- LEADER、DEVELOPER、REVIEWER 三种项目成员都可阅读和下载该项目内、该需求所属的附件。
- 正文仅在用户点击某个附件后单独读取，列表契约继续只返回元数据。
- 阅读区使用可换行的等宽原文展示 `.txt/.md`，不手写 Markdown 解析器，不将用户文本渲染为 HTML。
- 下载使用上传时的文件名、UTF-8 正文和对应的 `text/plain` / `text/markdown` 类型。第一版不承诺非 UTF-8 编码或字节级完全相同。
- 不属于该需求、不属于该项目或非项目成员的读取统一按现有资源隔离契约返回 404。

### R3 — 两种合理展示

- “结构化需求”继续展示当前不可变 Revision 的标题、背景、描述和 AC，它仍是工作流与 Review 的权威结构化上下文。
- “需求文档”保留上传列表，并增加查看原文、下载和选中文档阅读区；两者是互补视图，不做自动同步或字段映射。
- 结构化视图提供“导出 Markdown”按钮，由浏览器使用已加载 Revision 生成 `.md`，不新增后端导出端点。
- 390 px 宽度下按钮与阅读区不造成页面级横向滚动；阅读、空、加载和失败状态都有明确文字。

### R4 — AI 辅助

- 复用现有一次性 Implementation Guidance：LEADER 和被指派 DEVELOPER 可请求建议，REVIEWER 只读需求与文档。
- Guidance 使用结构化 Revision、AC，并语义召回当前需求附件的相关 Chunk；不承诺把最大 5 MiB 文件全量放入 Prompt。
- 不新增聊天、会话、文档专用 AI 按钮、第二个 AI runtime 或回答持久化。

### R5 — 后续 Word/PDF 扩展边界

- 第一版的路由和前端类型使用通用“文档内容”命名，不把产品契约写死为文本框。
- 文件后缀与响应 MIME 的判定集中在一个现有服务边界内，不建立类型注册表、插件机制或预留空实现。
- Word/PDF 所需的原始二进制存储、文本提取、可选 OCR、渲染与安全扫描全部延后；等真正引入该格式时再做 schema 和依赖决策。

## Acceptance Criteria

- [x] AC1：LEADER 可上传 `.txt/.md`；服务端拒绝其他扩展名，现有文本大小与 Unicode 校验保持有效。
- [x] AC2：三种项目成员可从需求详情页选中附件、阅读完整存储正文并按原文件名下载；跨项目、跨需求与非成员不可读取。
- [x] AC3：需求详情页同时提供清晰的结构化内容区和文档阅读区；结构化 Revision 可导出为包含标题、背景、描述、AC 的 `.md`。
- [x] AC4：现有 Guidance 的一次性、授权、无会话和不改变业务状态契约不变，回归验证当前需求附件 Chunk 会进入建议 Prompt 并在知识来源中返回。
- [x] AC5：不新增表、迁移、依赖、顶层包、一级路由、Markdown 解析器或 AI 流程；不做防御性兼容分支。
- [x] AC6：只扩展现有附件、Guidance 和需求页测试，不新建重复测试矩阵；后端 `verify` 与前端 lint/typecheck/test/build 全绿。

## Out of Scope

- `.doc` / `.docx` / `.pdf`、图片、二进制文件与非 UTF-8 文本。
- Markdown HTML 渲染、预览主题、目录、文档内搜索与在线编辑。
- 附件删除、替换、版本历史、批量上传、结构化字段自动提取或双向同步。
- 通用文档中心、第七个一级菜单、聊天或 Agent。

## Blocking Open Questions

无。
