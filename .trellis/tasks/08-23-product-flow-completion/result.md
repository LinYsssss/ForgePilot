# Result — 前端完整功能展示与后端链路补全

状态：实现与自动化验证完成，尚未提交或推送。

## Delivered

- D017 将正式产品面更新为工作台、项目、研发需求、项目知识、仓库接入、代码审查六个一级入口；桌面侧边栏在既有 `64rem` 断点转为紧凑顶部导航。
- 两份用户 Logo 原样复制到 `frontend/public/brand/`；横版用于登录与 Shell，应用图标用于登录与 favicon。旧 Settings URL 兼容跳转到仓库接入，过期的不可用 Settings 页面已删除。
- 工作台在浏览器中以一个 `Promise.all` 聚合 Requirement、Review activity、Review、Knowledge 与 SCM 列表，展示真实状态分布、最近记录、向量 Chunk 汇总和三段 AI 能力链，没有新增 Dashboard 后端或虚构指标。
- Project Knowledge 提供列表、上传、提升接口及页面；安全读模型展示文档状态、失败原因、Chunk/Embedding 数、维度和 provider/model/version，不返回正文或原始向量。
- Requirement 附件从 `requirement_attachment` 事实源读取；文档入库和关系写入共享事务，提升按复制实现并保留原附件。
- `ChunkSearchRepository` 在 SQL 中同时按项目和当前 Requirement 过滤；空 Requirement 上下文只召回公共项目知识。Review 使用快照中的 `requirementId`。
- Implementation Guidance 使用 Requirement、AC、公共项目知识与当前附件进行 TopK 8 向量召回，向 AI Gateway 传严格 schema，并返回 `checklist/rules/risks/knowledgeSources`。
- SCM 新增成员可读的安全列表接口；前端刷新可恢复配置，更新使用已读取 id，凭据仍只写且不回显。
- Requirement 与 Review 页面突出唯一 AI Review Engine、AI 输出/人工状态边界和“向量语义召回相似度”。

## Validation

- Backend focused scenarios: Knowledge/attachment ownership, promotion, A/B Requirement isolation, null-Requirement isolation, structured Guidance/schema/Prompt/references, SCM safe GET and existing Knowledge guards all passed after two direct compile/test corrections.
- Backend final: containerized JDK 21 `./mvnw -B -ntp verify` — **310 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Frontend: `npm run lint` passed; `npm run typecheck` passed; `npm run test -- --run` — **11 files, 34 tests passed**; `npm run build` passed with 85 modules transformed.
- Static audit: **16** business tables, **8** backend top-level packages, no dependency-manifest change, no evaluation-file change, no raw-vector Knowledge response, no SCM credential response, no second AI runtime or Review pipeline.
- Both copied Logo files are byte-identical to the two root inputs. `git diff --check` passed.
- Full-scope `trellis-check` passed after correcting the backend spec index's stale statement that Phase 8 was still outside the implementation boundary.
- Testcontainers required a containerized JDK because the host has no Java. Ryuk was disabled only for this nested-Docker run; all temporary test containers exited, confirmed by `docker ps`.

## Honest limitation

No Chromium/Playwright/browser executable is installed in this workspace, so automated screenshots at 1440/768/390 CSS px were not produced. The existing `64rem`/`42rem` responsive rules, overflow constraints, landmarks, route access, focus/reduced-motion contracts, and production build were statically and automatically verified. A final visual browser pass remains appropriate before a release/demo, but it does not block the implemented product chain.

## Repository state

- No commit or push was performed.
- Root `logo-app.png` and `logo-lockup.png` remain the user-provided untracked source assets; their frontend copies are part of this implementation.
- Formal evaluation freeze, corpus, ledger, raw outputs, and the known archived-path issue were untouched.
