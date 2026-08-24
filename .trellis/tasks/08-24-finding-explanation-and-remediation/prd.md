# Finding explanation, remediation advice, and model confidence

来源：`docs/v2/TEST-ISSUES.md` 的 T-010。

## Goal

让 AI Review Finding 承载**可读的问题说明**与**可执行的修复建议**，并就是否记录模型置信度做出明确决策——同时不破坏既有的去重、抑制与血缘语义。这是三个任务里唯一的全垂直改动：从模型输出契约一直到前端渲染。

## Background and confirmed facts

### 根因比台账描述的更靠前

台账记为「Finding 的数据与页面未承载可读的问题说明或修复建议」。核实后根因在**契约最上游**：

- `ReviewPrompts.java:70` 与 :127 的模型输出 schema，`required` 为 `["type", "category", "path", "line", "evidence", "acId", "sourceIds"]`。**模型从未被要求产出问题说明或修复建议。**
- `finding` 表（V6）只有 `finding_type` / `path` / `line` / `evidence` / `ac_id` / `status` / `continuity` 与三个哈希等可核验字段，**无 `explanation` / `suggestion` / `confidence` 列**。
- `ReviewOutputValidator` 只解析 `path`（:175/:241）、`evidence`（:185）、`line`（:291）。
- 前端 `FindingCard.vue` 渲染 `acKey`、`basisHash`、`carriedFromFindingId`、`continuity`、`evidence`、`evidenceHash`、`findingKey`、`findingType`、`id`、`line`、`path`、`requirementRevisionId`、`status`。

所以这不是「生产了但没展示」，而是**从未生产过**。修复必须贯穿 Prompt → 校验器 → 迁移 → 实体 → API → 前端。

### 关键设计约束：三个哈希都不含模型散文

V6 迁移注释明确要求 `finding_key`、`evidence_hash`、`basis_hash` 三者 "neither may cover model prose -- otherwise suppression drifts with the model's wording"。实测输入确认与该要求一致：

| 哈希 | 输入 |
|---|---|
| `findingKey` | `findingType`, `path`, `line`, `category`, `requirementId`, `acKey` — 纯结构 |
| `evidenceHash` | 规范化后的引用原文 — 代码，非模型措辞 |
| `basisHash` | `RULE_VERSION`, `requirementText`, `acKey`, `acText`, `knowledgeExcerptHashes` — 输入，非输出 |

**因此新增散文字段是安全的，前提是它们不进这三个哈希、也不改动 `RULE_VERSION = "1"`。** 这条既是本任务可做的依据，也是实现时必须显式守住的红线：一旦散文或置信度进入 `basis_hash`，同一问题会在每轮审查中抖动，跨轮次抑制与血缘立即失效。

### 其他确认事实

- 模型**已经在产出 `category`** 且它参与 `findingKey`，但 `finding` 表没有 `category` 列——这个语义标签当前被丢弃。它可能是「问题说明」里最便宜的一半。
- 本次真实 PR 审查已成功产出 8 条 Finding。问题不在模型、Webhook、仓库凭据或知识检索。
- **正式评测证据不受影响**：`evaluation/tools/*.py` 对后端零引用（无 `ReviewPrompts`、无 `/api/`、无 backend 调用）。修改 Prompt 契约不会使冻结的三臂实验证据失效。这一点需在答辩材料中保持表述准确。
- 前端目前把 `basisHash` / `evidenceHash` / `findingKey` 等内部哈希直接展示给用户——与「结果难以理解」是同一个问题的两面。
- AI 不得直接改变业务状态或代码（AGENTS.md 产品边界）：修复建议只能是**建议**，不产出补丁、不自动改码。
- 当前 19 表 / 8 迁移；历史迁移不可修改，只能追加。

## Requirements

### R1 模型输出契约

- 模型输出 schema 增加可读的问题说明与修复建议字段，并保持既有 `evidence` 的「逐字引用」语义不被稀释——说明与建议是模型自己的话，`evidence` 仍必须是原文摘录。
- 新字段设长度上限，防止单条 Finding 的散文无界增长。
- 两种 Prompt 形态（:70 与 :127 两处 schema）必须同步，不得只改一处。
- Prompt 净化与既有注入防护规则继续适用于新字段。

### R2 校验与落库

- `ReviewOutputValidator` 校验新字段，缺失或超限时的行为必须明确（拒绝整条 Finding 还是接受为空），不得静默丢弃。
- 追加新的 Flyway 迁移承载新列，不修改 V1–V8。
- 新列的可空性必须与「历史 Finding 没有这些内容」这一事实相容——既有 8 条 Finding 不能因为加列而变成非法行。

### R3 哈希不变性（本任务最硬的约束）

- 新增字段**一律不得**进入 `findingKey`、`evidenceHash`、`basisHash` 任何一个的输入。
- `RULE_VERSION` 保持 `"1"`，不得因本任务递增。
- 必须有测试证明：同一问题在模型措辞改变时 `findingKey` / `basisHash` / `evidenceHash` 三者均不变，跨轮次去重、抑制与 `carried_from_finding_id` 血缘行为不变。

### R4 API 与前端

- Finding 的读取接口暴露新字段；前端 `FindingCard.vue` 以可读层次展示「问题说明 → 代码证据 → 修复建议」，让用户不必读哈希就能理解并执行。
- 内部哈希与 `findingKey` 从默认视图收起或移除（保留可核验性，但不作为主要阅读内容）。
- 长说明与长建议不得撑破布局（与 `08-24-frontend-ux-remediation` 的 R4 同类约束，但作用于 Review 页）。
- 展示必须清楚区分「模型的判断」与「可核验的证据」，不得让建议看起来像已验证结论。

### R5 模型置信度（独立评估）

- 是否记录置信度、以何语义展示，必须与 R1 的说明/建议**分开决策**，避免把未经校准的数字误解为质量保证。
- 若决定记录：置信度绝不进入任何哈希（否则同一问题每轮抖动）；展示上必须避免精确数字暗示校准过，且不得参与任何自动化门禁或状态流转。
- 若决定不记录：在决策记录里写明理由，避免后续会话重开此题。

### R6 决策记录与文档

- 本任务改变 AI 输出契约与数据模型，须新增决策记录（当前最新为 D020，故为 D021 起），至少覆盖：为何新增散文字段、为何它们不进哈希、置信度的取舍。
- PRD / ARCHITECTURE / API / DECISIONS 同步更新。
- 答辩材料中关于评测的表述保持精确：冻结实验证明的是「知识进上下文有用」，本任务不改变该表述，也不使其失效。

### R7 交付约束

- 后端 `./mvnw -B -ntp verify` 全绿零 skip；前端 lint/typecheck/test/build 全绿零 skip。
- 不新增顶层包、一级导航、AI runtime、第二 Review 流程或运行时依赖。
- 空库 Compose 启动与迁移升级路径均需验证。
- 正式评测冻结、语料清单、holdout 台账与原始输出不得触碰或重跑。

## Acceptance Criteria

- [ ] AC1 模型输出契约包含问题说明与修复建议，两处 schema 均已同步，且带长度上限。
- [ ] AC2 `evidence` 仍是逐字引用；说明/建议与证据在契约与展示上都可区分。
- [ ] AC3 校验器对新字段缺失/超限的行为明确且有测试覆盖，无静默丢弃。
- [ ] AC4 新迁移仅为追加，V1–V8 未被修改；既有 8 条历史 Finding 在升级后仍合法可读。
- [ ] AC5 新字段不在 `findingKey` / `evidenceHash` / `basisHash` 任一输入中；`RULE_VERSION` 仍为 `"1"`。
- [ ] AC6 有测试证明模型措辞变化不改变三个哈希，且跨轮次去重、抑制与血缘行为不变。
- [ ] AC7 Finding 读取接口暴露新字段，前端按「说明 → 证据 → 建议」层次展示。
- [ ] AC8 内部哈希不再作为主要阅读内容；可核验性仍可查看。
- [ ] AC9 超长说明与建议不撑破 Review 页布局，不产生页面横向滚动。
- [ ] AC10 页面能让使用者区分模型判断与可核验证据，建议不被呈现为已验证结论。
- [ ] AC11 置信度的取舍已落入决策记录；若记录则不进哈希、不参与任何自动门禁与状态流转。
- [ ] AC12 AI 未获得改变业务状态或自动改码的能力；建议只是建议。
- [ ] AC13 新增 D021 起决策记录，且 PRD/ARCHITECTURE/API/DECISIONS 同步更新。
- [ ] AC14 后端 verify 与前端 lint/typecheck/test/build 全绿零 skip；空库启动与升级路径通过。
- [ ] AC15 真实 PR 重新审查一次，新产出的 Finding 在页面上可读、可执行，且既有哈希语义未变。
- [ ] AC16 正式评测冻结、语料、holdout 台账与原始输出未被触碰或重跑。
- [ ] AC17 `ReviewPrompts.VERSION` 已升为 `"review-2"`，且 `FindingKeys.RULE_VERSION` 仍为 `"1"`；升版未改变任何既有 Finding 的三个哈希。
- [ ] AC18 词表外的 `category` / `confidence` 不会中止整批插入：越界值存 `null` 并留 warning，而 `findingKey` 仍使用模型给的原始字符串、逐字节不变。

## 已决策（2026-08-24 方案评审）

原「待评审决策」六项均已定案。技术依据见 `design.md`，执行顺序见 `implement.md`。

1. **置信度：记录，但只用 `HIGH / MEDIUM / LOW` 三档闭合词表，不用数值。**
   记录的理由不是新增能力，而是兑现既有约束：`PRD.md:144` 与 `ARCHITECTURE.md:459` 早已要求「AI 置信度、Finding 状态、Review Decision 三者不互相替代，UI 上必须分开呈现」，`FindingCard.vue:109` 也早已留有占位并显示「未记录」。用分档而非数值，是因为模型自报置信度未经校准，精确数字会暗示已校准；分档复用 `category` 的同一套闭合词表机制，边际成本接近零。它不进任何哈希、不参与任何自动门禁与状态流转——与 `LEGACY-MIGRATION-MATRIX.md:109`（legacy 置信度服务标 REFERENCE，「评测/展示，不自动 gate」）及 `:112`（按置信度自动 gate 的 `FindingDecisionEntity` 标 DROP）一致。
2. **`category` 落库：是。** 模型已在产出且它已参与 `findingKey`，当前落库时被丢弃。它是「问题说明」里最便宜的一半，且使一条散文缺失的 Finding 仍保有「类型 + 类别 + 证据 + 定位」。
   **附带一个必须挡住的陷阱**：校验器目前不校验 category 词表（`ReviewOutputValidator:217` 只把原始字符串交给 `normalizeCategory` 供哈希用，随后丢弃）。一旦落库且列上有 CHECK，模型幻觉出的词表外值会**中止整批 finding 插入**（`:204` 注释已描述过同类形态）。因此词表映射必须发生在写库之前，越界值存 `null`；**哈希继续使用模型给的原始字符串**，以保证 `findingKey` 逐字节不变。
3. **散文缺失/超长：一律不丢弃整条 Finding。** 缺失 → 接受为空并记 warning；超长 → 截断到 2000 字符并记 warning。依据是校验器现有哲学——只有确定性部分不可用时才丢弃整条（无 `evidence` 会导致 `evidence_hash` 碰撞，故必须丢），而散文不进任何哈希，不存在该问题；为散文缺失丢掉一条有证据的有效 Finding 是净损失。全部走既有 `warnings` 通道，无静默丢弃。
4. **迁移号：本任务占 V9**；`08-24-resource-removal-semantics` 后执行，用 V10。start 本任务即锁定该分配。
5. **AC15 重跑：是。** 对一个真实 PR 重新触发一次审查作为证据，输出留档到本任务目录下的 `evidence/`。它不是正式评测资产、不受不可变约束，但**绝不与正式证据混放**；正式评测的冻结、语料清单、holdout 台账与原始输出全程不触碰、不重跑。
6. **内部哈希：收起，不移除。** 这一项其实已由 AC8 自答——「不再作为主要阅读内容；可核验性仍可查看」即收起。三个哈希移入折叠块，答辩时仍可展开展示可核验性。

## 核实时发现的、原 PRD 未记录的约束

**`ReviewPrompts.VERSION` 必须升版，`FindingKeys.RULE_VERSION` 必须不动——这是两个不同的版本号。**

| 常量 | 位置 | 存进 | 本任务 |
|---|---|---|---|
| `ReviewPrompts.VERSION` | `ReviewPrompts.java:49`，`"review-1"` | `review.prompt_version` | **必须升为 `"review-2"`** |
| `FindingKeys.RULE_VERSION` | `FindingKeys.java:35`，`"1"` | `basis_hash` | **必须保持 `"1"`** |

`ReviewPrompts` 类 javadoc 写明「只要任一条指令或任一个 schema 变了，它就必须跟着变：一份存下来的报告只有对着产生它的那个 Prompt 才可解读」。本任务改了两处 schema，因此升版是该常量自身契约的要求。它不进任何哈希，升版不影响 `finding_key` / `evidence_hash` / `basis_hash`，也不使既有抑制与血缘失效；历史 Review 行保留 `"review-1"`，其报告仍对着旧契约可解读。

R3 原文只锁了 `RULE_VERSION`，容易被读成「所有版本号都不许动」，故在此单独澄清。


## Out of Scope

- 建立向量索引（D019 已决策为非目标）。
- 让 AI 产出补丁、自动改码或自动流转 Finding 状态。
- 第二个 Review Engine、Agent、Patch、Sandbox、第二 AI runtime。
- 修改 `RULE_VERSION` 或既有哈希的输入构成。
- 重跑、修改或扩充正式评测实验；改变已冻结的三臂结论表述。
- 知识、成员、需求的删除能力（属 `08-24-resource-removal-semantics`）。
- 工作台、成员、账户的纯前端体验问题（属 `08-24-frontend-ux-remediation`）。
- Finding 人工生命周期语义（`OPEN`→…→`CLOSED`）的任何改动。
