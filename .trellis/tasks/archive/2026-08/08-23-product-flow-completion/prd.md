# 前端完整功能展示与后端链路补全

## Goal

让 ForgePilot 的正式前端完整、清晰地呈现 V2 主功能链路，并补齐支撑这些用户操作所缺少的最小后端能力。用户应当能从界面完成“工作台总览 → 项目与成员 → 仓库接入与项目知识 → 需求、AC 与附件 → 知识增强实现建议 → PR Review 与人工闭环”，而不是看到功能留白或刷新即丢失的配置状态。

## Background

- 当前核心 Auth、Project、Requirement、SCM 写入、Review 与人工闭环已经有真实页面和接口。
- 当前顶层导航被固定为“项目 / 研发需求 / 代码审查”三个入口，项目知识与仓库接入藏在项目设置中；用户明确要求不再局限于三个一级入口，以合理布局和完整功能可见性为优先，并提出增加工作台展示。
- `logo-app.png` 与 `logo-lockup.png` 已由用户放在仓库根目录，尚未进入前端资源与页面。
- Project Knowledge 只有服务层，没有上传、列表与状态 HTTP 接口；现有前端明确显示不可用。
- Requirement 附件创建没有写入作为事实源的 `requirement_attachment`，也没有用户操作入口。
- Knowledge 检索只按项目过滤，未按当前 Requirement 排除同项目其他需求的私有附件。
- SCM 只有注册和修改接口，没有读取接口；页面刷新后无法恢复已配置状态，更新还要求手填内部记录 id。
- Implementation Guidance 只使用 Requirement 与 AC，返回自由文本，未使用项目知识和当前需求附件，也没有“实现清单 / 相关规则 / 风险提示”结构。
- 本任务不改变 16 张表、8 个后端顶层包、唯一 Review Engine、AI 不改变业务状态等架构边界。

## Requirements

### R1 — 合理的信息架构

- 已登录应用采用适合多个主要入口的桌面侧边导航，并在窄屏降级为紧凑、可滚动的顶部导航。
- 一级入口固定为：工作台、项目、研发需求、项目知识、仓库接入、代码审查。
- 工作台是只读的项目研发总览与快捷入口，不是 Agent、聊天、自动执行或通用编排界面。
- 项目知识与仓库接入拥有独立页面和项目选择器；成员管理继续作为项目内页面，不制造无项目上下文的全局成员入口。
- 页面保持当前深色 Precision Review Console 视觉方向、语义结构、键盘焦点、响应式与 reduced-motion 约束。
- 项目详情入口能够自然到达成员、需求、知识、仓库和审查，不保留“明知不可用”的占位说明。

### R2 — 工作台与 AI 能力展示

- 用户可选择一个当前项目，并看到该项目的真实概要：需求总量及状态分布、评审活动分布、Knowledge 文档数量/状态、SCM 是否已接入，以及最近的需求和 Review。
- 工作台数据只组合现有列表和本任务新增的 Knowledge/SCM 读取接口，不新增 dashboard 表、缓存、统计服务或第二套状态源。
- 每个区块提供到对应正式页面的快捷入口；缺少数据时展示真实空状态，不生成演示数字。
- 工作台首屏以清晰的 AI 能力链展示“需求质量检查 → 知识增强实现建议 → AI 代码审查”，每项说明真实输入、输出和进入对应业务页面的动作，不显示虚构的 AI 分数或运行状态。
- Requirement 详情把质量检查和结构化 Guidance 组成视觉上优先的“AI 研发辅助”区域；Review 列表与详情明确标识唯一 AI Review Engine、知识/AC/Diff 证据和人工终局决定的分工。
- AI 能力保持上下文内操作，不增加通用 AI/Assistant 一级入口、聊天输入框或对话历史。

### R3 — 品牌资源

- `logo-lockup.png` 用于已登录应用品牌区与登录页品牌展示。
- `logo-app.png` 用于应用图标/登录视觉，并作为浏览器 favicon。
- Logo 保持原始透明背景和比例，不重新生成、不拉伸，也不引入新的图片处理依赖。

### R4 — Project Knowledge 用户流程

- 项目成员可以读取项目知识文档列表及真实状态、失败原因和时间信息。
- LEADER 可以从前端选择 UTF-8 文本/Markdown 文件上传项目知识；前端读取文件内容后调用 JSON HTTP 接口，后端继续使用现有 5 MB 校验、切片与 Embedding 流程。
- 上传成功后页面立即显示服务端返回的文档状态；失败显示真实 API 错误。
- Knowledge 页面明显展示真实向量能力：文档切片数、已向量化 Chunk 数、向量维度、Embedding 模型/版本和语义索引状态；工作台汇总已索引文档与 Chunk。
- Review 的 Knowledge 证据把现有检索分数明确标为“向量语义召回相似度”，让用户能看到项目知识如何进入 AI 审查。
- 页面不展示原始向量数组，不提供手工向量查询调试台，也不伪造检索命中。
- 不引入异步任务、队列、二进制文档解析或新的文档存储模型。

### R5 — Requirement 附件用户流程

- Requirement 详情页展示该需求的附件列表。
- LEADER 可以选择文本/Markdown 文件挂为当前 Requirement 的附件。
- 创建 Document 与写入 `requirement_attachment` 必须在同一事务完成，关系表继续作为唯一事实源。
- LEADER 可以把附件复制提升为 Project Knowledge，原附件及其关系不被改写。

### R6 — Knowledge 检索隔离

- 所有面向 Requirement 的检索只能召回公共 Project Knowledge 和当前 Requirement 的附件。
- 同项目其他 Requirement 的附件不得进入 Guidance 或 Review。
- 无 Requirement 上下文的 Review 只能使用公共 Project Knowledge。
- 隔离必须在 SQL 查询中完成，不采用先召回再在 Java 中过滤。

### R7 — 知识增强 Implementation Guidance

- Guidance 使用当前 Requirement、当前 Revision 的 AC、公共项目知识及当前需求附件。
- Guidance 保持一次性调用、不建对话、不保存回答、不改变任何业务状态。
- API 返回结构化的“实现清单、相关规则、风险提示”，前端分区展示，不再渲染一整段无结构文本。
- 不建立 Prompt Registry、通用 ContextBuilder、第二 AI runtime 或额外持久化。

### R8 — SCM 读取与正常编辑

- 后端提供当前项目 SCM 仓库的安全读取接口，只返回 provider、实例、外部 id、API 地址和时间，不回显 token 与 webhook secret。
- 仓库接入页面刷新后能显示已配置仓库；没有配置时显示注册表单。
- 修改现有仓库直接使用读取到的记录 id，用户不再手填内部 id；凭据字段继续只写且提交后清空。

### R9 — 实现约束

- 不新增数据库表、Flyway migration、后端顶层包、运行时依赖、全局状态库或 UI 框架。
- 不建设聊天、Agent、Patch、代码库浏览器、Metrics 或评测管理页面。
- 不修正与产品主链路无关的正式评测归档路径。
- 只增加关键回归验证：附件跨需求隔离、附件关系原子写入、Guidance 使用知识并返回结构、SCM 读取，以及前端导航/API/关键渲染；不扩张为重复的边界测试矩阵。
- 实现保持直接：复用现有 Service、Repository、请求层、页面模式和设计令牌，不增加防御性包装、fallback 数据或推测状态。

## Acceptance Criteria

- [ ] AC1：登录页、应用 Shell 和 favicon 使用两份用户 Logo；任一 Logo 不变形，构建产物能正确引用。
- [ ] AC2：已登录桌面界面显示经确认的全部一级入口，窄屏仍能访问；当前项目查询参数在工作台、需求、知识、仓库和审查之间保持。
- [ ] AC3：工作台选择项目后，以真实接口数据展示需求、评审、Knowledge、SCM 和最近记录，并能跳转到对应页面；无数据时不显示伪造指标。
- [ ] AC3a：工作台首屏明显呈现三段 AI 能力链；Requirement 与 Review 页面具有清晰的 AI 功能标题、输入依据、输出区域和人工边界，不增加聊天或自动状态变更。
- [ ] AC4：LEADER 可从“项目知识”页面选取文本/Markdown 文件完成上传，随后在真实文档列表中看到标题、状态、更新时间、Chunk/向量数量、维度和模型；非 LEADER 只能查看。
- [ ] AC4a：工作台显示真实语义索引汇总；Review 知识证据显示向量召回相似度；前端和接口均不返回原始向量值。
- [ ] AC5：Requirement 详情页能列出附件；LEADER 上传附件后同时存在 `knowledge_document` 与对应 `requirement_attachment`，并可复制提升为项目知识。
- [ ] AC6：同项目 Requirement A 的私有附件不会被 Requirement B 的检索召回；B 仍能召回公共项目知识和自己的附件。
- [ ] AC7：生成 Guidance 时知识证据进入 Prompt，响应包含非空或明确为空的 `checklist`、`rules`、`risks` 三个数组，前端分别显示实现清单、相关规则和风险提示。
- [ ] AC8：仓库接入页面刷新后可读取并显示当前配置；更新无需填写内部 id；任何响应和页面均不出现 token 或 webhook secret。
- [ ] AC9：项目、成员、需求/AC/修订、质量检查、Review/Finding/Decision 等现有流程与角色权限保持可用。
- [ ] AC10：后端只运行与本任务相关的测试及一次最终 `verify`；前端运行 lint、类型检查、相关测试和生产构建，不新增大规模重复测试。
- [ ] AC11：数据库仍为 16 张业务表，后端仍为 8 个顶层包，且没有新增运行时依赖或第二条 AI/Review 流程。

## Out of Scope

- 异步知识入库、队列、后台重试、删除/重建知识文档。
- PDF、Office、压缩包、图片 OCR 或二进制文件解析。
- 多仓库、仓库内容浏览、代码向量库、相关代码检索。
- 聊天式 Assistant、多轮历史、SSE、自动改代码。
- 新的正式评测实验、重跑 holdout 或改写任何冻结评测证据。
