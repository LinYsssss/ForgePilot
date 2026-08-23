# ForgePilot V2 开发方案

ForgePilot 是围绕需求驱动 Pull Request 审查建设的轻量级 AI 研发协作平台。

状态：**Phase 0–8 已于 2026-08-22 全部完成并通过退出闸门**。D017 产品主链路补全已于 2026-08-23 完成实现和自动化验证，交付六入口前端、只读工作台、Knowledge/附件用户流程、知识增强 Guidance 与 SCM 安全读取；当前尚未提交/归档，正式评测证据仍不可重跑或覆盖。

## 权威文档

本目录只保留七份用于开发与答辩的权威文档。每类事实只在一个地方定义，其他文档只引用：

| 文档 | 权威内容 | 使用时机 |
|---|---|---|
| [PRD.md](./PRD.md) | 产品定位、角色权限、范围、状态与产品验收 | 判断做什么、谁能做 |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 模块边界、16 张表、数据库约束、流程与运行边界 | 判断怎么实现、不能越过什么边界 |
| [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) | Phase 顺序、授权闸门、任务级规划要求、测试与退出条件 | 安排开发和验收 |
| [DECISIONS.md](./DECISIONS.md) | D001 起各条决策的理由与不可逆后果 | 需要理解为何这样定或要提出变更 |
| [DEFENSE-GUIDE.md](./DEFENSE-GUIDE.md) | 无凭据条件下的部署、构建、评测复现与演示步骤 | 准备答辩或复现实验 |
| [LEGACY-MIGRATION-MATRIX.md](./LEGACY-MIGRATION-MATRIX.md) | Legacy 资产的 KEEP/REWRITE/REFERENCE/DROP | 实施某模块前判断旧代码能否参考 |
| 本页 | 阅读入口、当前状态和不可违反的总边界 | 新会话或新开发者接手 |

推荐阅读顺序：本页 → `PRD.md` → `ARCHITECTURE.md` → `IMPLEMENTATION-PLAN.md` → 需要时查 `DECISIONS.md`、`DEFENSE-GUIDE.md` 和迁移矩阵。

## 当前状态与已交付能力

Phase 0–8 全部完成，逐阶段验收证据在 `.trellis/tasks/archive/2026-08/` 下各任务的 `result.md`。已交付能力：

- **auth**：本地账号、进程内会话、Cookie CSRF、会话版本失效、改密。
- **project**：项目与成员、唯一 LEADER 约束、项目级 SCM 身份、成员角色鉴权。
- **requirement**：需求与验收条件、不可变修订、稳定 `ac_key`、需求质量检查、知识增强的一次性结构化实现建议。
- **knowledge / ai**：pgvector 项目知识库、按项目与当前需求隔离的附件检索、可见的真实向量索引元数据、统一 AI 网关、Prompt 净化与调用审计。
- **scm**：GitHub 与 GitLab 双 Provider、Webhook 签名校验、PR 同步与需求关联、出站 URL 策略。
- **review**：单一 Review Engine、分批审查与抢占围栏、Finding 生命周期与血缘、人工决策闭环、对账调度。
- **evaluation**：三臂对照实验工具链、确定性打分器、配置冻结与一次性 holdout 台账。

后续改动仍受下列总边界约束，且必须走 Trellis 任务流程。

## 不可违反的总边界

- 后端是模块化单体，顶层包仅为 `common/auth/project/requirement/scm/knowledge/ai/review`。
- 首版数据模型上限为 16 张表；Finding 内聚于 `review`，只有一个 Review Engine。
- `scm` 发布 `PullRequestChanged` 事件但不依赖 `review`；AI 不直接改变业务状态或代码。
- 禁止 Agent、Patch、MQ/Outbox、第二 AI runtime、本地 clone/Git、第二 Review Pipeline、代码向量库和未经过产品决策的额外一级菜单；D017 批准的六个入口不属于额外扩张。
- PostgreSQL 15+ 与 pgvector 是业务事实源；所有项目内引用和查询必须保持 `project_id` 隔离。
- Legacy RepoSage 只读，按迁移矩阵逐项提取，不整包复制，也不继承其迁移历史。

## 证据与不可变资产

- 每个阶段/批次的验收结论在 `.trellis/tasks/archive/2026-08/<任务>/result.md`，容量与评测实测证据在同级 `evidence/`。
- 正式实验的配置冻结、语料清单、holdout 台账与原始输出是**不可变证据**：不得删除、覆盖或重跑。换实验必须换新的 case-set 与配置身份。
- 正式冻结曾因端点记录错误做过一次内容寻址的修正（仅改 endpoint），原始冻结与失败记录一并保留，答辩时须主动说明该协议偏离。

## 一句话主流程

负责人创建并指派带 AC 的需求，开发者获得一次性实现建议并提交关联 PR；ForgePilot 结合需求、项目知识和 Diff 生成可核验 Finding，Reviewer 退回或通过，开发者修复后复审，最后由 LEADER 确认需求完成。
