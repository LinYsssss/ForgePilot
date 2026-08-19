# ForgePilot V2 第二轮三视角交叉审查

审查角色：企业软件架构师、高级产品经理、接手遗留系统的 Staff Engineer。  
审查对象：第一轮 `design.md`、`implement.md`、PRD 和 Legacy 迁移矩阵。  
约束：仅更新规划资料，不修改任何产品代码。

## 1. 总体结论

第一轮已经正确删除 Agent、Patch、MQ/Outbox、双 Review、Sandbox 主链和复杂观测，但仍残留旧 ForgePilot 的一种思维习惯：**每发现一个有价值的概念，就把它升级为一个模块、一个服务、一套状态和一层基础设施。**

如果照第一稿实施，短期不会出现 AgentRun，但很可能出现另一种膨胀：8 个 feature、每个 feature 四层目录、多个 Context/Parser/Service、Knowledge active version、相关代码检索、多仓库、多入口编排。这些不是当前业务事实要求的复杂度。

第二轮建议从“完整企业平台”退回“可验证的毕业设计产品”：

> 负责人创建并指派带 AC 的需求，开发者提交关联 PR 后，ForgePilot 结合需求、项目知识与 Diff 生成可核验 Finding，Reviewer 据此退回或通过，开发者修复后复审闭环。

## 2. 三个视角的独立判断

### 2.1 企业软件架构师

1. `finding` 不是独立上游业务域，而是 Review 的问题明细与处理生命周期。独立顶层包会产生 `review → finding`，同时 Finding 权限/查询又需要 review/project，未来极易反向依赖。应并入 `review`。
2. 强制每个 feature 都建 `domain/application/infrastructure/web` 是形式化分层，不是实际复杂度要求。小模块会产生大量空层、DTO 映射和接口。应采用 package-by-feature，内部按需要建立 `model/service/repository/web`。
3. 第一稿没有解决 Webhook 自动触发的依赖方向：如果 `scm` Controller 直接调用 `review`，而 Review 又依赖 SCM 获取 Diff，就形成 `scm ↔ review`。候选版改为 SCM 发布进程内 `PullRequestChanged` 事件，Review 单向消费。
4. `DevelopmentContextBuilder` 可以存在，但不得演变为全系统通用 Context 平台。Requirement Quality 和 Review 各自拥有业务上下文组装，只共用 `AiGateway` 和 Knowledge 查询接口。
5. 多仓库/多连接、active embedding version 原子切换、semantic relevant-code retrieval 都没有当前业务证据。MVP 采用一个项目一个活动仓库、单一 Embedding 配置、PR patch 作为代码上下文。
6. Review 长调用需要异步，但 RabbitMQ 不是唯一解。候选版用有界进程内执行器 + Review 数据库状态 + 人工 retry；崩溃恢复不伪装成可靠队列。

### 2.2 高级产品经理

1. 第一稿的主线仍有 10 步且夹杂 Requirement Assistant、相关代码检索、Knowledge reindex 等技术动作，用户价值不够突出。
2. 需求助手删除后，核心代码审查产品完全成立，因此不应进入 MVP。它保留为 P1，避免为了“AI 指导”建设聊天历史、SSE、上下文预算和新 UI 状态。
3. Requirement Quality Check 虽然删除后产品仍能运行，但它直接提高 Review 输入质量，并能展示“需求质量影响审查质量”的毕业设计逻辑，因此保留为一个简单按钮，不建独立工作流。
4. “代码仓库”一级菜单与“项目设置/代码审查”重复；仓库配置归项目，PR 归审查。MVP 一级导航只需项目、研发需求、代码审查。工作台和知识管理页都不是核心入口。
5. 项目管理只支持名称、成员、角色、一个仓库、项目知识，不扩展 Sprint、任务、工时、看板、里程碑、审批流。
6. Finding 置信度不能自动决定 PR。AI 输出、Finding 人工状态、PR Decision 三者必须在 UI 上明确分开。

### 2.3 Staff Engineer

1. 第一轮 KEEP 标准过松。根包、DTO、状态和输入模型都变化时，“纯代码”也可能语义不兼容，应优先迁特征测试而不是复制实现。
2. `DiffSplitter` 不应 KEEP：它按字符截断、达到 maxFiles 后直接丢文件，且只覆盖简单 unified diff；V2 从 SCM API 获取标准化 changed-file patch，应重写分片器。
3. `FindingDeduplicator` 不应 KEEP：它把路径转小写，可能错误合并大小写敏感仓库文件；fingerprint 也不包含 requirement/AC/head SHA 等 V2 语义。
4. `FindingCandidate/Evidence` 不应 KEEP：旧模型绑定 Agent sourceVersion/evidenceType，V2 需要 review/requirement/ac/sourceId 证据结构。
5. `RequirementStatus` 不应 KEEP：候选版删除 `NEEDS_IMPROVEMENT`，质量检查不再驱动工作流状态。
6. `RequirementCheckParser` 不应 KEEP：严格拒绝思想值得保留，但新输出 Schema 不同，应 REWRITE。
7. `NormalizedPullRequestEvent/Snapshot` 不应 KEEP：旧记录包含 cloneUrl/installation/Agent publication 假设，候选版不做本地 clone。
8. `GitInputValidator` 不应 KEEP：V2 不接收本地 clone 路径，保留它会把旧 Git CLI 路线带回来。
9. `AuthCookieService` 代码很小且绑定旧配置，复制收益低于重写；应降为 REWRITE。
10. 被低估的成熟资产主要是测试和协议：SCM Provider contract fixtures、Webhook verifier tests、Requirement rule tests、AC 全量补齐/证据降级测试、上传边界测试、`evaluation/score.py` 的确定性判分。它们比旧 Service 类更值得迁移。

## 3. 对十个重点问题的回答

| 检查项 | 第一稿问题 | 候选版修正 |
|---|---|---|
| 1. 旧复杂度 | 仍有 8 feature、四层包、相关代码、active version | 7 个顶层模块+common；按需子包；删除相关代码检索与在线双版本 |
| 2. 企业级过度设计 | 多仓库、原子切换、熔断/复杂运行策略倾向 | 一个项目一个活动仓库；单模型；超时+有限重试；无 circuit breaker/MQ |
| 3. 重复职责 | Review/Finding、多个 Context、多个业务 AI client 风险 | Finding 并入 Review；业务自有 Prompt/Context；统一 AiGateway |
| 4. 两模块同题 | scm 触发 Review 与 Review 依赖 scm；requirement/knowledge 附件写入 | SCM 只发布事件；Review 消费；Requirement 只保存 KnowledgeDocument 引用 |
| 5. 循环依赖 | `scm ↔ review`、`review ↔ finding`、feature→auth 风险 | 单向事件、合并 finding、userId 作为参数，业务模块不依赖 auth |
| 6. 错误 KEEP | DiffSplitter、Deduplicator、Finding model、RequirementStatus/Parser、Git validator | 全部降为 REWRITE/REFERENCE；只迁测试与不变量 |
| 7. 错误 DROP | 成熟测试、AC evidence validation、score.py 方法被低估 | 明确 KEEP TEST/DATA/TOOL，按新契约改 fixture |
| 8. 组件对应需求 | 部分组件服务未来扩展而非当前用户 | 组件逐项绑定项目隔离、PR 接入、检索、AI 审查、可复现评测 |
| 9. 一句话主流程 | 第一稿可讲清但太长 | “需求+知识+Diff→Finding→人工退回/通过→修复复审” |
| 10. 删除测试 | Assistant、Workbench、Repo menu、Finding module 删除后产品仍成立 | 移出 MVP或合并；核心只留 Project/Requirement/SCM/Knowledge/AI/Review |

## 4. 模块删除测试

| 候选能力 | 删除后核心产品是否成立 | 结论 |
|---|---|---|
| Auth | 技术演示可成立，但角色/人工决策不可信 | 保留最小实现 |
| Project/Member | 无项目隔离、指派和权限 | 核心保留 |
| Requirement/AC | 退化为普通 AI Code Review | 核心保留 |
| SCM | 可手工贴 Diff，但失去真实 PR 流程 | 产品核心保留 |
| Knowledge | 仍有需求增强，但失去项目规范差异化 | 核心保留 |
| AI | 不再是智能审查 | 核心保留 |
| Review（含 Finding） | 产品消失 | 核心保留 |
| 独立 Finding 模块 | Review 内仍可完整实现 | 删除顶层模块，合并 |
| Requirement Assistant | Review 主线不受影响 | P1 延后 |
| Requirement Quality | Review 可运行但输入质量不可控 | 保留简单能力，不建工作流 |
| Workbench | 三个核心页面可完成流程 | P1 延后 |
| Code Repository 一级菜单 | 项目内配置、Review 内看 PR 即可 | 删除一级菜单 |
| 在线 Embedding 双版本切换 | 停机/维护窗口可重建 | 删除；保留 model/version 记录 |
| 相关代码语义检索 | PR patch 仍可审查 | P1 延后，MVP 不建代码索引 |
| AI call log | 产品可运行但无法量化成本/失败 | 保留最小表，服务论文评测 |
| Finding event | 当前态可运行但人工决策不可追溯 | 保留，属于业务审计而非“企业级装饰” |

## 5. 第一稿到候选版的关键变更

1. 顶层包从 `auth/project/requirement/scm/knowledge/ai/review/finding/common` 收敛为 `auth/project/requirement/scm/knowledge/ai/review/common`。
2. Finding 实体、状态机、事件和 API 全部归入 review。
3. 删除强制四层架构，改为按 feature 聚合、内部按需分包。
4. 一个项目只绑定一个活动 SCM 仓库；`scm_connection` 与 `scm_repository` 合并成 `scm_repository`。
5. 核心表从 15 张减为 14 张。
6. Requirement 状态简化为 DRAFT/READY/IN_DEVELOPMENT/IN_REVIEW/DONE/CANCELED；AI 质量结果不改变状态。
7. Requirement Assistant 从 MVP 移到 P1；不做聊天历史和 SSE。
8. MVP 不做 relevant-code semantic retrieval、本地 clone/Git CLI、代码索引。
9. Knowledge 只支持单一配置的 Embedding 模型；保留 model/version，重建索引采用维护操作，不做在线 active-version 切换。
10. Webhook 与 Review 通过进程内领域事件保持单向依赖；失败可人工重试，不引入 Outbox。
11. 前端 MVP 一级导航从 5 个减为 3 个：项目、研发需求、代码审查。
12. KEEP 迁移从“迁实现优先”改为“迁测试和不变量优先”。
