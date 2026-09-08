# ForgePilot 文档入口

ForgePilot 是围绕需求驱动 Pull Request 审查建设的轻量级 AI 研发协作平台。

## 文档

每类事实只在一个地方定义，其他文档只引用。

| 文档 | 权威内容 | 使用时机 |
|---|---|---|
| [PRD.md](./PRD.md) | 产品定位、角色权限、范围、业务状态与产品规则 | 判断做什么、谁能做 |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 模块边界、21 张表、数据库约束、流程契约与运行边界 | 判断怎么实现、不能越过什么边界 |
| [API.md](./API.md) | 账户、成员、SCM 身份、审查人指派、最终决定与知识原文接口 | 联调身份、权限与评审协作 |
| [DEFENSE-GUIDE.md](./DEFENSE-GUIDE.md) | 无凭据条件下的部署、构建、评测复现与演示步骤 | 复现实验或准备演示 |
| [TESTING-GUIDE.md](./TESTING-GUIDE.md) | 空库起步的全功能测试流程：账号、项目成员、仓库接入、知识、需求、推送触发 PR、审查与决策，含负向用例与排错 | 逐项验证功能是否按契约工作 |

阅读顺序：本页 → `PRD.md` → `ARCHITECTURE.md`，需要时查 `API.md` 与 `DEFENSE-GUIDE.md`。要动手把功能走一遍时用 `TESTING-GUIDE.md`。

## 当前形态

后端 9 个顶层包、21 张业务表、14 个 Flyway 迁移；前端 6 个一级导航、11 条产品路由（含非一级 `/account`）。最新验证记录见 [测试报告](../deliverables/TEST-REPORT.html)。

已交付能力：

- **auth**：本地账号、显示名、进程内会话、Cookie CSRF、会话版本失效、改密。
- **project**：可按显示名/用户名/平台 ID 搜索的成员目录、原子批量添加、成员多角色、唯一 LEADER 与角色能力并集、成员移除、项目归档与恢复（重输项目名确认）。
- **requirement**：需求与验收条件、开发/审查人指派、独立生命周期与派生当前阶段、不可变修订、稳定 `ac_key`、`.txt/.md` 需求文档阅读与下载、结构化 Markdown 导出、需求质量检查、知识增强的一次性实现建议、作废需求软删除。
- **knowledge / ai**：pgvector 项目知识库、批量上传与删除、公共知识原文阅读/下载、按项目与当前需求隔离的附件检索、折叠的真实索引元数据、统一 AI 网关、Prompt 净化与调用审计。
- **scm**：GitHub 与 GitLab 双 Provider、用户自有多 SCM 身份、项目身份绑定与可选 Leader 审批、Webhook 签名校验、PR 同步与需求关联、出站 URL 策略。
- **review**：单一 Review Engine、分批审查与抢占围栏、Finding 生命周期与跨轮血缘、可读的问题说明与修复建议、分档模型置信度、指定审查人最终决定、退回理由/轮次、按审查 SHA 合并 GitHub PR / GitLab MR、对账调度。
- **notification**：项目级钉钉 AI 完成/失败、人工退回/合并摘要，包含处理人和项目详情链接，以及 LEADER 发起的测试消息。
- **evaluation**：三臂对照实验工具链、确定性打分器、配置冻结与一次性 holdout 台账。

## 不可违反的总边界

- 后端是模块化单体，顶层包仅为 `common/auth/project/requirement/scm/knowledge/ai/review/notification`。
- 数据模型为 21 张表，Finding 内聚于 `review`，只有一个 Review Engine。
- `scm` 发布 `PullRequestChanged` 事件但不依赖 `review`；AI 不直接改变业务状态或代码。
- 禁止 Agent、Patch、MQ/Outbox、第二 AI runtime、本地 clone/Git、第二 Review Pipeline、代码向量库和额外一级菜单。
- PostgreSQL 15+ 与 pgvector 是业务事实源；所有项目内引用和查询必须保持 `project_id` 隔离。

## 不可变资产

正式评测的配置冻结、语料清单、holdout 台账与原始输出**不得删除、覆盖或重跑**。holdout 只跑过一次；换实验必须换新的 case-set 与配置身份。冻结记录中保留了一次仅修正 endpoint 的内容寻址订正，演示时须主动说明该协议偏离。

## 一句话主流程

负责人创建带 AC 的需求并指派开发与审查人，开发者获得一次性实现建议并提交关联 PR；ForgePilot 结合需求、项目知识和 Diff 生成可核验 Finding，指定审查人或 LEADER 填写理由退回，开发者更新原分支后复审；人工通过时确认合并被审查的 SHA，最后由 LEADER 确认需求完成。
