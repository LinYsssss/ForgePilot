# ForgePilot V2 开发方案

ForgePilot 是围绕需求驱动 Pull Request 审查建设的轻量级 AI 研发协作平台。

状态：**Finding 问题说明、修复建议与模型置信度（2026-08-24）**。Phase 0–8 及 R2.5 基线保持有效；D020 以 V8 迁移补充显示名、成员多角色、用户自有 SCM 多身份和项目身份绑定，D021 以 V9 追加 Finding 的说明、建议、类别与置信度四列。正式评测证据仍不可重跑或覆盖。

## 权威文档

本目录只保留少量用于开发与答辩的权威文档。每类事实只在一个地方定义，其他文档只引用：

| 文档 | 权威内容 | 使用时机 |
|---|---|---|
| [PRD.md](./PRD.md) | 产品定位、角色权限、范围、状态与产品验收 | 判断做什么、谁能做 |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 模块边界、19 张表、数据库约束、流程与运行边界 | 判断怎么实现、不能越过什么边界 |
| [API.md](./API.md) | 当前账户、成员目录与 SCM 身份接口契约 | 联调身份与成员管理 |
| [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) | Phase 顺序、授权闸门、任务级规划要求、测试与退出条件 | 安排开发和验收 |
| [DECISIONS.md](./DECISIONS.md) | D001 起各条决策的理由与不可逆后果 | 需要理解为何这样定或要提出变更 |
| [DEFENSE-GUIDE.md](./DEFENSE-GUIDE.md) | 无凭据条件下的部署、构建、评测复现与演示步骤 | 准备答辩或复现实验 |
| [LEGACY-MIGRATION-MATRIX.md](./LEGACY-MIGRATION-MATRIX.md) | Legacy 资产的 KEEP/REWRITE/REFERENCE/DROP | 实施某模块前判断旧代码能否参考 |
| 本页 | 阅读入口、当前状态和不可违反的总边界 | 新会话或新开发者接手 |

推荐阅读顺序：本页 → `PRD.md` → `ARCHITECTURE.md` → `IMPLEMENTATION-PLAN.md` → 需要时查 `API.md`、`DECISIONS.md`、`DEFENSE-GUIDE.md` 和迁移矩阵。

## 当前状态与已交付能力

Phase 0–8 全部完成，逐阶段验收证据在 `.trellis/tasks/archive/2026-08/` 下各任务的 `result.md`。

交付形态：后端 8 个顶层包、19 张业务表、9 个 Flyway 迁移、323 个测试零跳过；前端保持 6 个一级导航、11 条产品路由和 35 个测试，并增加非一级账户页。已交付能力：

- **auth**：本地账号、显示名、进程内会话、Cookie CSRF、会话版本失效、改密。
- **project**：可按显示名/用户名/平台 ID 搜索的成员目录、原子批量添加、成员多角色、唯一 LEADER 与角色能力并集。
- **requirement**：需求与验收条件、不可变修订、稳定 `ac_key`、`.txt/.md` 需求文档阅读/下载、结构化 Markdown 导出、需求质量检查与知识增强 Guidance。
- **knowledge / ai**：pgvector 项目知识库、按项目与当前需求隔离的附件检索、可见的真实向量索引元数据、统一 AI 网关、Prompt 净化与调用审计。
- **scm**：GitHub 与 GitLab 双 Provider、用户自有多 SCM 身份、项目身份绑定/可选 Leader 审批、Webhook 签名校验、PR 同步与需求关联、出站 URL 策略。
- **review**：单一 Review Engine、分批审查与抢占围栏、Finding 生命周期与血缘、可读的问题说明/修复建议/分档置信度、人工决策闭环、对账调度。
- **evaluation**：三臂对照实验工具链、确定性打分器、配置冻结与一次性 holdout 台账。

后续改动仍受下列总边界约束，且必须走 Trellis 任务流程。

## 已知缺口

这些是**已交付基线的真实缺口**，在此单列而不是分散在各阶段 `result.md` 里，避免后续会话把它们当成已完成：

| 缺口 | 性质 | 依据 |
|---|---|---|
| 语义检索没有向量索引，走顺序扫描 | **已决策接受**：冻结的 4096 维 Profile 在 pgvector 0.8.6 下建不出任何精确索引，可建的两种形态都是有损预筛 | [D019](./DECISIONS.md#d019) |
| 需求状态转换（DRAFT→READY、指派、CANCELED、DONE）不单独留痕 | 明确接受的 MVP 缺口 | [D013.3](./DECISIONS.md#d013) |
| changed-file 超限整条 422 拒绝且不留痕 | 明确接受的可观测性缺口 | [D016.1](./DECISIONS.md#d016) |
| 浏览器点击闭环、1440/768/390 响应式与视觉漂移检查为人工验收 | 各批次均如实记为部分通过 | 批次 1/3 `result.md` |

R2.5 复核时另有两条曾被列为「计划中未兑现」，现已各自收口：PRD P1 的 DEVELOPER 半条授权**已实现**（[D016.2](./DECISIONS.md#d016) 执行状态），向量索引**已由 D019 决策接受不建**。补上表中任何一条都要先立 Trellis 任务。

## 不可违反的总边界

- 后端是模块化单体，顶层包仅为 `common/auth/project/requirement/scm/knowledge/ai/review`。
- 当前数据模型为 19 张表；V8 新增的三张表只表达角色集合、用户 SCM 身份与项目绑定历史，V9 只给 `finding` 加列、不加表。Finding 仍内聚于 `review`，只有一个 Review Engine。
- `scm` 发布 `PullRequestChanged` 事件但不依赖 `review`；AI 不直接改变业务状态或代码。
- 禁止 Agent、Patch、MQ/Outbox、第二 AI runtime、本地 clone/Git、第二 Review Pipeline、代码向量库和未经过产品决策的额外一级菜单；D017 批准、D018 重新布局的六个入口不属于额外扩张。
- PostgreSQL 15+ 与 pgvector 是业务事实源；所有项目内引用和查询必须保持 `project_id` 隔离。
- Legacy RepoSage 只读，按迁移矩阵逐项提取，不整包复制，也不继承其迁移历史。

## 证据与不可变资产

- 每个阶段/批次的验收结论在 `.trellis/tasks/archive/2026-08/<任务>/result.md`，容量与评测实测证据在同级 `evidence/`。
- 正式实验的配置冻结、语料清单、holdout 台账与原始输出是**不可变证据**：不得删除、覆盖或重跑。换实验必须换新的 case-set 与配置身份。
- 正式冻结曾因端点记录错误做过一次内容寻址的修正（仅改 endpoint），原始冻结与失败记录一并保留，答辩时须主动说明该协议偏离。

## 一句话主流程

负责人创建并指派带 AC 的需求，开发者获得一次性实现建议并提交关联 PR；ForgePilot 结合需求、项目知识和 Diff 生成可核验 Finding，Reviewer 退回或通过，开发者修复后复审，最后由 LEADER 确认需求完成。
