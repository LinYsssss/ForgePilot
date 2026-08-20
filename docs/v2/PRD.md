# ForgePilot V2 产品需求

状态：**Final R2，已获用户批准（2026-08-19）**。本文是**产品权威**：定位、角色、范围、流程与验收标准。Phase 1 已于 2026-08-20 获授权开始。

技术规范（模块、16 表、依赖、运行边界）见 [ARCHITECTURE.md](./ARCHITECTURE.md)，本文不复述。

---

## 1. 定位与价值

> **ForgePilot V2 是围绕需求驱动 PR 审查建设的轻量级 AI 研发协作平台。**

毕业设计题目：**ForgePilot：基于需求与项目知识上下文增强的智能代码审查系统设计与实现**。

项目、成员、需求和知识用于建立真实研发责任与上下文；PR 审查是旗舰能力，需求检查和一次性实现建议分别服务开发前与开发中。系统不扩张为 Jira、通用聊天平台或自动编码 Agent。

核心价值：负责人与 Reviewer 不再只看到通用代码建议，而能看到代码变更与 Requirement、AC、项目规范之间的**可追踪差异**，并由人完成退回、修复、复审与通过。

## 2. 主流程

> 负责人创建并指派带 AC 的需求，开发者获得一次性 AI 实现建议并提交关联 PR；ForgePilot 结合需求、项目知识与 Diff 生成可核验 Finding，Reviewer 据此退回或通过，开发者修复后复审，最终由 LEADER 确认需求完成。

```mermaid
flowchart LR
    PM["项目/成员/一个仓库"] --> REQ["Requirement + AC + 指派"]
    PM --> KB["Project Knowledge"]
    REQ --> GUIDE["一次性 AI 实现建议"]
    GUIDE --> PR["关联 Pull Request"]
    PR --> REV["唯一 Review Engine"]
    REQ --> REV
    KB --> REV
    REV --> FIND["Finding + AC/代码/知识证据"]
    FIND --> HUMAN["Reviewer 人工判断"]
    HUMAN -->|REQUEST_CHANGES| FIX["开发修复并更新 PR"]
    FIX --> REV
    HUMAN -->|APPROVE| REVIEW_DONE["当前 PR Review 完成"]
    REVIEW_DONE --> LEADER["LEADER 确认全部研发工作完成"]
    LEADER --> DONE["需求完成"]
```

任何不能服务这条因果链的能力，默认不进入 MVP。

## 3. 角色与权限

每个项目**恰有一个 LEADER**。不设独立 OWNER。AI 只生成分析结果，**不改变任何业务状态**。

| 动作 | LEADER | DEVELOPER | REVIEWER |
|---|:--:|:--:|:--:|
| 创建项目、管理成员与角色 | ✅ | ❌ | ❌ |
| 配置 SCM 仓库、上传项目知识 | ✅ | ❌ | ❌ |
| 创建/编辑需求与 AC | ✅ | ❌ | ❌ |
| 运行需求质量检查 | ✅ | ❌ | ❌ |
| 需求 DRAFT → READY、指派开发 | ✅ | ❌ | ❌ |
| 生成当前需求的一次性 AI 实现建议 | ✅ | 仅被指派需求 | ❌ |
| 修改 PR↔需求关联 | ✅ | 仅本人 PR 且当前 head 尚无 Review | ❌ |
| 触发/重试 Review（含版本过期后的重审） | ✅ | 仅本人 PR | ✅ |
| Finding 确认 / 拒绝 | ✅ | ❌ | ✅ |
| Finding 认领、标记已修复 | ❌ | ✅ | ❌ |
| Finding 验证通过 / 打回 | ✅ | ❌ | ✅ |
| Review 终局 APPROVE / REQUEST_CHANGES | ✅ | ❌ | ✅ |
| 取消需求 | ✅ | ❌ | ❌ |

跨项目一律不可见、不可操作。

## 4. MVP 范围

### 必须有

- 最小账户、项目成员与三角色。
- Requirement、AC、指派与简化状态机。
- 一个项目一个活动 GitHub/GitLab 仓库；PR/MR 与 Requirement 关联。
- Requirement 附件复用 Project Knowledge 文档，不做双份解析。
- Requirement Quality Check：确定性规则 + 一次结构化 AI 分析。
- Requirement Implementation Guidance：基于 Requirement、AC 与项目知识生成一次性实现清单、相关规则和风险提示，不保存对话。
- Project Knowledge：上传、切片、Embedding、项目内检索。
- 唯一 Review Engine：Requirement/AC + Knowledge + PR patch → Finding。
- Finding 人工生命周期 + PR 的 APPROVE/REQUEST_CHANGES。
- 修复后按新 head SHA 产生新 Review，保留前后结果。
- 可复现评测：漏报率、误报率、AC 判定、结构失败、Token、耗时。

### 不做

- 通用聊天助手、聊天历史、SSE、多轮记忆。
- Workbench、代码仓库/知识/AI 日志一级菜单。
- 多仓库、多 SCM Connection、通用 Commit 审查、本地 clone/Git CLI。
- 相关代码语义检索、代码向量库、AST/调用图。
- Agent、Planner、Tool、Memory、Patch、自动改代码/提交。
- MQ/Redis、微服务、独立 Sandbox、复杂 Observability。

P1（核心 E2E 完成前不得实施）：多轮 Requirement Assistant、Workbench、多仓库、相关代码读取、报告导出、高级监控。

## 5. 业务状态

### Requirement

持久状态（[ADR-011](./adr/ADR-011-requirement-revision-and-state.md)）：

```text
DRAFT → READY → IN_DEVELOPMENT → DONE
  └────────────────────────────→ CANCELED
```

`DRAFT → READY` 由 LEADER 确认；`READY → IN_DEVELOPMENT` 与**首次指派**同事务完成，后续更换负责人不再改变状态；`→ DONE` 由 LEADER 确认全部关联工作完成。**AI、Webhook、PR、Review 一律不得推进这些状态。**

无 `NEEDS_IMPROVEMENT`：**质量检查是建议，不是工作流状态**，也不能自动置 READY。
无 `IN_REVIEW`：评审进展是**只读派生量** `review_activity`（`FAILED > CHANGES_REQUESTED > REVIEWING > PENDING > MIXED > APPROVED > NO_PR`），按所有关联 PR 的**当前 head + 当前需求版本**的 Review 计算，不落表。UI 与需求状态并列展示，两个维度不得合并。

READY 后正文与 AC 锁定；修改由 LEADER 创建新的不可变 Revision 并填写变更原因，旧 AC 永久保留。DRAFT 阶段的 Revision 1 可原地编辑，`DRAFT → READY` 同事务冻结——"不可变"指**已发布的 Revision**。需求版本变更**不自动重审**，关联 PR 显示"审查已过期"，由人工按上表权限触发。需求质量检查结果归属具体 Revision，DRAFT 期间正文一改即失效。

### Finding

```text
主链：OPEN → CONFIRMED → IN_PROGRESS → FIXED → VERIFIED → CLOSED
旁路：OPEN → REJECTED；CONFIRMED → REJECTED
打回：FIXED → IN_PROGRESS（复验不通过）
重开：REJECTED → OPEN（**仅** continuity=SUPPRESSED 的继承驳回项，须留审计）
终态：CLOSED；REJECTED（普通驳回不可逆）
```

该状态机是**人工处理生命周期**，与跨 Review 血缘 `continuity`（`NEW / PERSISTING / SUPPRESSED`，[ADR-009](./adr/ADR-009-finding-continuity.md)）正交，两者不得混入同一字段或同一 UI 标签。继承而来的抑制项以 `status=REJECTED + continuity=SUPPRESSED` 落库；被重新打开后 `continuity` 仍保留 `SUPPRESSED`（血缘事实不因当前状态改变而消失），并回到主列表正常显示。

### Review Decision

`PENDING | APPROVE | REQUEST_CHANGES`。**AI 置信度、Finding 状态、Review Decision 三者不互相替代**，UI 上必须分开呈现。
终局 Decision 只能写在**已完成、且 head 与需求版本均等于 PR 当前值**的 Review 上；REQUEST_CHANGES 后必须有新 head SHA 才能再次产生终局 Decision——**改需求关联或需求版本都不能解除该闸门**（[ADR-003](./adr/ADR-003-review-identity.md) §6 · §8）。

## 6. 关键产品规则

| # | 规则 | 依据 |
|---|---|---|
| P1 | PR 关联需求：分支名/标题解析 `REQ-<n>` 优先，页面下拉框可改可清除；解析失败不阻断入库，Review 标记"未关联需求" | [ADR-007](./adr/ADR-007-pr-requirement-association.md) |
| P2 | 一个 Requirement 可有多个 PR；一个 PR 至多关联一个 Requirement | [ADR-004](./adr/ADR-004-domain-cardinality.md) |
| P3 | 需求附件只在所属需求的 AI 场景可见；跨需求共享必须显式提升为项目知识 | [ADR-005](./adr/ADR-005-requirement-attachment-retrieval-boundary.md) |
| P4 | Review 身份 = (PR, head SHA, 需求版本)；终局 Decision 闸门只认 (PR, head SHA)；引擎升级不自动重审；FAILED 重试复用同一行，COMPLETED 永不覆盖 | [ADR-003](./adr/ADR-003-review-identity.md) |
| P5 | 大 PR 分批审查但只产出一份报告；未审查文件必须显式呈现，禁止静默截断 | [ADR-002](./adr/ADR-002-large-pr-review.md) |
| P6 | AI 返回非法结构时 Review 判定失败，**绝不生成"成功空报告"** | ARCHITECTURE §3.5 |
| P7 | 人工决策全部留痕（actor、时间、备注），可追溯 | ARCHITECTURE §2.1 |
| P8 | Review 保存审查时的 requirement_id、requirement_revision_id 与不可变上下文快照；历史结果不得通过 PR 当前关联反查语义 | ARCHITECTURE §2.1/3.5 |
| P9 | 单个 PR APPROVE 只结束当前 Review；Requirement DONE 必须由 LEADER 在确认全部关联工作完成后执行 | [ADR-004](./adr/ADR-004-domain-cardinality.md) |
| P10 | 上一轮已驳回且源码证据未变的 Finding，本轮自动抑制、不要求重复驳回；抑制不跨 PR，且不得自动认定"本轮未报告 = 已修复" | [ADR-009](./adr/ADR-009-finding-continuity.md) |
| P11 | "本人 PR" 由项目级 SCM 稳定外部 ID 判定，**禁止按用户名授权**；身份由 LEADER 配置，开发者不得自行声明 | [ADR-010](./adr/ADR-010-scm-identity-and-repository-immutability.md) |

## 7. 验收标准

### Phase 0（已完成并批准）

- [x] Legacy backend/frontend/deploy/evaluation/demo/docs 已实际扫描并逐项核实。
- [x] 核心流程可用一句话讲清；每个 MVP 模块通过"删除后产品是否成立"测试。
- [x] Finding 保持在 review 内；多轮 Assistant、相关代码检索、多仓库和强制四层已删除。
- [x] 数据模型收敛为 16 张表，依赖单向且无环。
- [x] 争议决策全部落为 ADR-001..011。
- [x] 文档收敛为单一事实源，无跨文档重复定义。
- [x] 用户批准最终执行方案；Phase 1 仍等待单独的开始指令。

### 产品 E2E（Phase 7 退出标准）

- [ ] LEADER 建项目、加成员、配仓库、传知识、写需求与 AC、指派开发。
- [ ] 被指派开发者可生成一次性实现建议，输出实现清单、相关规则和风险提示，不产生聊天会话。
- [ ] 开发者提交 `feat/REQ-<n>-*` 分支 PR，系统自动关联需求并产生 Review。
- [ ] Review 呈现 AC 覆盖判定与带证据的 Finding，证据可点击回溯到 AC/知识/代码行。
- [ ] Reviewer 确认部分 Finding 并 REQUEST_CHANGES；开发者修复推送新 head SHA。
- [ ] 新 Review 自动产生，历史 Review 完整保留；上一轮已驳回且源码证据未变的 Finding 在新 Review 中显示为已抑制，无需重复驳回。
- [ ] Reviewer APPROVE 只完成当前 Review，LEADER 确认后再将需求置 DONE。
- [ ] 需求状态与派生的评审活动在页面上分开呈现，互不污染。
- [ ] A 项目用户无法看到或操作 B 项目的任何资源。

## 8. 风险与边界声明

- 进程内 Review 执行**不提供**消息队列级持久性；系统必须先持久化 PENDING Review，并通过轻量 reconciliation 补偿漏触发任务，再由人工重试处理显式失败。
- 一个项目一个仓库是 MVP 约束；出现真实多仓库需求后再设计。
- **仓库产生 PR 后不可原地更换**：provider 与外部仓库 ID 冻结，凭据/Webhook Secret/API 地址仍可更新；确需更换仓库须新建项目（[ADR-010](./adr/ADR-010-scm-identity-and-repository-immutability.md)）。
- **PostgreSQL 最低版本为 15**，来自复合外键列级 `ON DELETE SET NULL` 与 `UNIQUE NULLS NOT DISTINCT` 两处不可替代的语法。
- Embedding 换 Profile 需维护窗口与 reindex，**不承诺**无停机切换。
- GitHub 先跑通主线，GitLab 后验证同一 contract，用以证明 Adapter 的实际价值。
- 评测语料为**人工构造的演示缺陷**，非真实企业缺陷，论文中须诚实说明；且 holdout 仅 12 例，结论必须给出置信区间或明确的不确定性说明，不得把小样本上的差值当作强证据。
