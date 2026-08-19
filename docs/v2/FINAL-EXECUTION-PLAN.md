# ForgePilot V2 最终执行方案

- 状态：**APPROVED**
- 版本：**Final R1 / 2026-08-19**
- 批准记录：**用户已于 2026-08-19 明确确认方案无问题并授权提交、推送。**
- 当前授权边界：**仅提交并推送治理与方案文件；在用户另行要求开始 Phase 1 前，禁止创建业务代码。**

本文是本轮评审的统一入口，负责把产品目标、裁剪结果、技术边界、迁移策略和实施顺序放在一张执行地图中。字段、约束和决策细节仍分别以 [PRD](./PRD.md)、[ARCHITECTURE](./ARCHITECTURE.md)、[ADR](./adr/README.md) 和 [迁移矩阵](./LEGACY-MIGRATION-MATRIX.md) 为权威；如发现冲突，先修文档，再实施。

---

## 1. 最终结论

ForgePilot V2 定位为：

> **围绕需求驱动 Pull Request 审查建设的轻量级 AI 研发协作平台。**

它解决一条明确的问题链：需求与验收条件说明“应该做什么”，项目知识说明“在本项目里应该怎么做”，PR Diff 说明“实际改了什么”；ForgePilot 比较三者，生成可核验证据，最终由 Reviewer 和项目负责人作出业务决定。

```text
项目、成员、仓库
→ 需求、AC、指派
→ AI 需求质量检查
→ 开发者获得一次性实现建议
→ 提交关联 PR
→ Requirement + AC + Project Knowledge + Diff
→ 唯一 Review Engine
→ Finding、AC 覆盖与证据
→ Reviewer APPROVE / REQUEST_CHANGES
→ 开发修复、按新 head 复审
→ LEADER 确认需求 DONE
```

AI 有且只有三个产品落点：

1. **开发前**：检查需求的完整性、明确性、可测试性、异常场景和项目规则冲突。
2. **开发中**：在需求详情页生成一次性实现清单、相关规则和风险提示；不做聊天会话。
3. **开发后**：结合需求、AC、项目知识和 PR patch 生成结构化 Review 与 Finding。

## 2. 最终范围

### 2.1 必须交付

- 本地账户、项目、成员与 `LEADER / DEVELOPER / REVIEWER` 三角色。
- 一个项目一个活动仓库的 MVP 产品约束，先接 GitHub，再以 GitLab 验证统一 SCM contract。
- Requirement、Acceptance Criteria、负责人、指派和简化状态流转。
- 需求附件与项目知识统一入库、解析、切片、Embedding 和 project-scoped 检索。
- AI Requirement Quality Check。
- 无会话的一次性 Requirement Implementation Guidance。
- PR/MR 同步、`REQ-N` 自动关联与人工纠正。
- 唯一 Review Engine，大 PR 分批但最终只生成一份 Review 结果。
- AC 覆盖、Finding、知识/需求/代码证据和未审文件清单。
- Finding 人工处理、Review 人工通过/打回、修复后新 head 复审。
- 跨项目隔离、审计记录、调用成本/失败记录和轻量任务恢复。
- 可复现评测与答辩演示。

### 2.2 明确不做

- Jira/禅道式 Sprint、看板、Story Point、工时、甘特图和复杂审批。
- HR、组织树、绩效、考勤、请假。
- 通用聊天、聊天历史、SSE、多轮记忆和万能研发助手。
- Agent、Planner、Tool Loop、自动 Patch、自动改代码、自动提交。
- RabbitMQ/Kafka/Redis/Outbox、微服务、第二数据库和第二 AI runtime。
- Risk Model 独立服务、Sandbox 主流程、完整 CI/CD 平台。
- 本地 clone/Git CLI、代码向量库、AST/调用图和全仓代码理解。
- Workbench、Knowledge、Repository、Metrics、AI Logs 等额外一级菜单。

### 2.3 后置但不预埋

多轮 Assistant、多仓库、相关代码读取、报告导出、高级监控只有在核心 E2E 完成且出现真实需求后，才允许新建 ADR 评估；当前 schema、接口和菜单不为它们预留抽象。

## 3. 不可突破的架构边界

- 模块化单体，后端顶层包只能是 `common/auth/project/requirement/scm/knowledge/ai/review`。
- Finding 内聚在 `review`，不增加 `finding` 顶层包。
- 只有一个 Review Engine；自动触发与人工重试共用 `ReviewService.requestReview(...)`。
- `scm` 发布事件但不依赖 `review`；`review` 只能通过其他模块的 Service/Query facade 取数。
- PostgreSQL 是唯一业务事实源；pgvector 只保存项目知识向量，禁止 JSON/影子表双写。
- Review 身份固定为 `(pull_request_id, head_sha)`，并保存审查时的 Requirement/AC/Knowledge 不可变上下文快照。
- 单个 PR APPROVE 只完成当前 Review，Requirement DONE 必须由 LEADER 单独确认。
- AI 只输出建议和分析，不直接改变 Requirement、Finding 或 Review Decision。
- 后台执行采用“先落 PENDING + 有界进程内执行器 + reconciliation + 人工重试”；不以企业级名义提前引入 MQ。
- 数据层按 `project_id` 做复合外键和查询过滤，向量检索也必须先过滤项目。

## 4. 数据与页面上限

首版数据模型控制为 14 张表，详细字段和约束只在 [ARCHITECTURE §2](./ARCHITECTURE.md#2-数据模型) 定义。开发期间按纵向 Phase 增加迁移，首个可发布版本前再 squash 为干净的 `V1__init.sql`，不继承 RepoSage 的历史迁移。

前端一级导航只有：

1. 项目
2. 研发需求
3. 代码审查

知识、成员、仓库配置进入项目详情；实现建议进入需求详情；Finding 与人工决策进入 Review 详情。

## 5. Legacy 提取原则

旧实现完整保存在 [RepoSage](https://github.com/LinYsssss/reposage)。ForgePilot 不在 Legacy 上继续重构，而是按迁移矩阵定向提取：

| 分类 | 执行动作 |
|---|---|
| KEEP | 先迁特征/安全测试，再迁最小纯代码，去除周边依赖 |
| REWRITE | 保留业务事实和验收用例，按 V2 边界重新实现 |
| REFERENCE | 只继承算法、协议、安全策略、Prompt 或测试思想 |
| DROP | 不读取为实现模板，不复制到 V2 运行时 |

高价值来源包括上传安全校验、Webhook 验签、SSRF 策略、PromptSanitizer、Finding 状态边、评测数据和 demo fixtures。Agent、Patch、MQ/Outbox、Risk Model、Sandbox runtime、旧 Review 双轨与 32 个 Flyway migration 永久不迁。

## 6. 分阶段执行与人工闸门

每个 Phase 单独建立 Trellis 任务，先完成 `prd.md`、复杂任务的 `design.md` 与 `implement.md`，由用户评审后才能 `start`。完成一个 Phase 后必须停止，提交验证证据，等待下一 Phase 授权。

| Phase | 目标与核心产物 | 退出标准 | 本阶段禁止 |
|---|---|---|---|
| 0（当前） | Trellis 三端初始化、最终方案、文档一致性 | Claude Code/Codex/Pi 可识别；方案进入用户评审；无业务代码 | 任何应用脚手架或业务实现 |
| 1 | Spring Boot/Vue/PostgreSQL-pgvector/Flyway/Testcontainers/ArchUnit/基础 CI 的最小绿地底座 | 空库启动、pgvector、边界测试和构建全绿；无业务 UI | 登录、项目、需求、知识、SCM、Review |
| 2 | Auth + Project + Member | 登录安全、唯一 LEADER、角色和跨项目越权测试通过 | Requirement/SCM/AI |
| 3 | Requirement + AC + 指派 + 确定性质量规则 | 无 AI/SCM 也能创建、确认、指派；READY 锁定规则通过 | 附件、AI Quality、PR |
| 4 | 统一 AiGateway + Project Knowledge + Requirement Attachment + 一次性 Implementation Guidance | 项目隔离、文档安全、向量维度和失败可见性通过 | Conversation、SSE、代码索引 |
| 5 | GitHub SCM Adapter、Webhook、PR snapshot/patch、需求关联 | 验签、重放幂等、关联纠正和 `scm !→ review` 通过 | GitLab、clone、本地 Git |
| 6 | AI Requirement Quality + 唯一 Review Engine + recovery | 非法 JSON 不假成功；大 PR 不静默丢文件；PENDING 可恢复 | 第二 Pipeline、MQ、Sandbox |
| 7 | Finding 人工闭环 + 三页面 E2E | 三角色完成需求→PR→退回→修复→复审→通过→DONE | P1 扩展功能 |
| 8 | GitLab Adapter + 评测、部署、答辩固化 | 同一 SCM contract；论文数据可重算；干净环境可复现 | 新业务功能 |

## 7. 每阶段统一验收模板

每个 Phase 的 Trellis `result.md` 必须同时给出：

- 实际完成项与明确未完成项。
- 影响的模块、表、API 和页面。
- 单元、集成、架构、前端、构建和安全验证命令及结果。
- 跨项目隔离与角色权限证据。
- 是否触发新 ADR；如没有，写明“无新决策”。
- Legacy 资产使用清单及 KEEP/REWRITE/REFERENCE/DROP 依据。
- 已知风险、回滚方法和下一 Phase 的前置条件。

任一退出标准未通过，不得将任务归档为 completed，不得通过“后续再补”越过闸门。

## 8. 测试与研究验证

### 8.1 工程质量门禁

- 后端：单元测试、Spring 集成测试、Testcontainers PostgreSQL/pgvector、ArchUnit。
- 前端：组件/交互测试、类型检查、构建、关键流程可访问性。
- SCM：GitHub/GitLab contract fixture、Webhook 签名、重放、分页/限流和错误映射。
- AI：结构化输出校验、一次修复后失败、超时/429/5xx、Prompt injection 与敏感信息脱敏。
- Review：小 PR、大 PR、截断清单、AC 补齐、伪造 source/path/line 拒绝、reconciliation。
- 安全：跨项目猜 ID、角色越权、凭据不回显、上传 NUL/超限/非法 UTF-8、SSRF。

### 8.2 毕业设计实验

固定模型、温度、Prompt 版本和语料，至少比较：

1. `Diff + LLM`（Baseline）
2. `Diff + Requirement + AC`
3. `Diff + Requirement + AC + Project Knowledge`（ForgePilot）

报告 Precision、Recall、漏报率、误报率、Requirement Violation Recall、AC Verdict、结构失败率、Token 和耗时。Legacy 38 例可改造使用，但必须明确它们是人工构造的演示缺陷，不冒充真实企业数据。

## 9. Trellis 与三种 AI 工具的协作方式

- 开发者身份统一为 `LinYsssss`。
- Claude Code 使用 `.claude/` 与根 `CLAUDE.md`；Codex 使用 `.codex/`、`.agents/skills/` 与根 `AGENTS.md`；Pi 使用 `.pi/` 并读取同一 `AGENTS.md`。
- 三端共享 `.trellis/workflow.md`、`.trellis/spec/`、`.trellis/tasks/` 和 `.trellis/workspace/`，不分别维护三套产品规则。
- 自动提交关闭；所有提交先展示文件分组与提交信息，获得用户确认后再执行。
- Codex 默认 inline；所有工具只有在用户明确授权时才允许分派子 Agent。
- 当前评审任务保持 `planning`，不得执行 `task.py start`。

## 10. 已批准的最终决定

用户已整体批准以下 12 项决定：

1. 产品定位保留“轻量级 AI 研发协作平台”，PR Review 为旗舰能力。
2. AI 三个落点全部保留，但开发中只做一次性建议，不做聊天。
3. MVP 一个项目一个活动仓库；GitHub 先实现，GitLab 最后验证 Adapter。
4. 每项目逻辑独立知识空间，底层共享 PostgreSQL + pgvector。
5. 只有一个 Review Engine；大 PR 分批但只有一份最终结果。
6. 人工 Reviewer 决定 PR，LEADER 单独决定 Requirement DONE。
7. 14 表、8 顶层包和三个一级页面作为首版上限。
8. 进程内执行器 + PENDING + reconciliation，暂不引入 MQ。
9. Agent、Patch、Risk Model、Sandbox runtime 永久退出 V2 主线。
10. Legacy 只按迁移矩阵提取，不整包复制、不继承数据库历史。
11. Phase 1 只建工程底座；每个后续 Phase 必须再次人工授权。
12. 功能在 Phase 8 冻结，最后集中完成评测、部署、论文和答辩材料。

## 11. 批准后的第一步

只有后续收到用户明确的“开始 Phase 1”或等价指令后，才执行：

1. 为 Phase 1 新建独立 Trellis 任务。
2. 写 Phase 1 的 `prd.md`、`design.md`、`implement.md` 和验证清单。
3. 再次提交 Phase 1 任务级方案供用户快速确认。
4. 确认后 `task.py start`，仅初始化最小工程底座。
5. Phase 1 验证完成后停止，不自动进入 Phase 2。

在此之前，本仓库应保持“方案 + Trellis + 空骨架”状态。
