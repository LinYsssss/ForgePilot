# Design — Finding explanation, remediation advice, and model confidence

对应 `prd.md`（T-010）。本文件只写技术设计；执行顺序在 `implement.md`。

## 0. 本设计遵守的两条外部约束

- **最小改动**：不新建抽象层、不新建设计原语、不顺手扩大数据模型。新增字段一律沿用同类字段的既有形态。
- **不过度测试**：只写锁得住具体不变量的测试。第 8 节逐条说明每个测试锁的是什么；答不上来的不写。

## 1. 结论先行：改什么

四个新字段，一条追加迁移，两处 schema 同步，一个版本号必须升、另一个必须不动。

| 字段 | 来源 | 存储形态 | 进哈希？ |
|---|---|---|---|
| `explanation` 问题说明 | 模型新产出 | `TEXT`（与 `evidence` 同形态） | **否** |
| `suggestion` 修复建议 | 模型新产出 | `TEXT` | **否** |
| `category` 问题类别 | **模型已在产出**，当前落库时被丢弃 | `VARCHAR(32)`（与 `finding_type` 同形态） | 已经在 `findingKey` 里，**行为不变** |
| `confidence` 置信度 | 模型新产出 | `VARCHAR(16)`（与 `status`/`continuity` 同形态） | **否** |

## 2. 两个版本号：一个必升，一个必不动

这是本任务最容易搞错的地方，PRD 未记录，单独列出。

| 常量 | 位置 | 含义 | 本任务 |
|---|---|---|---|
| `ReviewPrompts.VERSION` | `ReviewPrompts.java:49`，值 `"review-1"`，存进 `review.prompt_version` | 一份报告对着哪个 Prompt 才可解读 | **必须升为 `"review-2"`** |
| `FindingKeys.RULE_VERSION` | `FindingKeys.java:35`，值 `"1"`，进 `basis_hash` | 确定性规则版本 | **必须保持 `"1"`** |

`ReviewPrompts` 的类 javadoc 写明：「只要任一条指令或任一个 schema 变了，它就必须跟着变」。本任务改了两个 schema，因此升版是该常量自身契约的要求，不是可选项。

它**不进任何哈希**，所以升版不会动 `finding_key` / `evidence_hash` / `basis_hash`，也不会使既有抑制与血缘失效。历史 Review 行保留 `"review-1"`，其报告仍对着 V1 契约可解读——这正是该字段存在的目的。

反过来，`RULE_VERSION` 一旦递增会改写全部 `basis_hash`、丢弃全部继承抑制项。本任务没有改动任何确定性规则，因此不得递增。

## 3. 哈希不变性（R3 红线）

三个哈希的输入构成**一个字符都不改**：

- `findingKey(findingType, path, line, category, requirementId, acKey)` —— `category` 本来就在里面，且经 `normalizeCategory` 折叠大小写与首尾空白。落库只是把同一个值**额外**存一份，计算路径不碰。
- `evidenceHash(evidence)` —— 不碰。
- `basisHash(RULE_VERSION, requirementText, acKey, acText, knowledgeExcerptHashes)` —— 不碰。

四个新字段全部不作为任何 `field(digest, …)` 的实参。V6 迁移注释「neither may cover model prose -- otherwise suppression drifts with the model's wording」在本任务后依然成立。

## 4. 数据层：V9 追加迁移

```sql
ALTER TABLE finding
    ADD COLUMN category    VARCHAR(32),
    ADD COLUMN explanation TEXT,
    ADD COLUMN suggestion  TEXT,
    ADD COLUMN confidence  VARCHAR(16);
```

四列**全部可空**，因为既有 8 条历史 Finding 从未产出过这些内容（AC4）。可空性不是宽松，而是对事实的如实表达：这些行确实没有说明。

两个闭合词表加 CHECK，与 `ck_finding_type` / `ck_finding_status` / `ck_finding_continuity` 同形态，且都容忍 NULL：

```sql
ALTER TABLE finding
    ADD CONSTRAINT ck_finding_category CHECK (category IS NULL OR category IN (
        'CORRECTNESS', 'SECURITY', 'ERROR_HANDLING', 'CONCURRENCY', 'PERFORMANCE',
        'API_CONTRACT', 'TEST_COVERAGE', 'MAINTAINABILITY', 'REQUIREMENT_GAP')),
    ADD CONSTRAINT ck_finding_confidence CHECK (confidence IS NULL OR confidence IN (
        'HIGH', 'MEDIUM', 'LOW'));
```

不改 V1–V8。不加索引：这四列不进任何 WHERE 条件，只随行读出。

### 4.1 CHECK 约束引入的故障模式，必须在写库前挡住

**这是本设计中唯一一处「加一列」会带来新故障模式的地方。**

`ReviewOutputValidator:217` 目前这样读 category：

```java
String category = stringOrNull(item, "category");
```

它**从不校验词表**——原始字符串直接进 `normalizeCategory` 参与哈希，然后被丢弃。丢弃掉，所以模型幻觉出一个词表外的值也没有后果。

一旦这个值落库且列上有 CHECK，幻觉值会让**整批 finding 插入中止**。`ReviewOutputValidator:204` 的注释已经描述过这个形态：约束触发器会「把整批插入一起带走」。

因此：**校验器必须在构造候选项时把 category 映射到闭合词表，越界值存 `null`**，并记一条 warning。关键点是——

> 落库用映射后的值，**哈希仍用模型给的原始字符串**。

这样 `findingKey` 与今天逐字节相同（R3 红线），而列里永远只可能出现词表内的值或 NULL，CHECK 永不触发。`confidence` 同样处理。

## 5. 契约层：两处 schema 同步

`BATCH_SCHEMA`（:59）与 `SYNTHESIS_SCHEMA`（:104）的 finding item 各加四个属性，两处**逐字相同**。`category` 已存在，不动它。

```
"explanation": {"type": "string", "maxLength": 2000,
  "description": "Your own plain-language statement of what is wrong here, in the reader's language. Not a quotation."},
"suggestion":  {"type": "string", "maxLength": 2000,
  "description": "Your own concrete advice for fixing it. Advice only -- you are not writing the patch."},
"confidence":  {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"],
  "description": "How sure you are that this finding is real. A coarse band, not a calibrated probability."}
```

`required` 从

```
["type", "category", "path", "line", "evidence", "acId", "sourceIds"]
```

改为

```
["type", "category", "path", "line", "evidence", "explanation", "suggestion", "confidence", "acId", "sourceIds"]
```

`additionalProperties: false` 保持不变。

### 5.1 说明/建议与 evidence 的分工（R1、AC2）

`evidence` 的「逐字引用」语义不能被稀释。两条防线：

1. 新字段 `explanation` 的 `description` 注解措辞明说 **"Not a quotation" / "Your own"**，与 `evidence` 的 `"copied character for character"` 形成对照。
2. `CITATION_RULES`（:146）开头一句是「Every quotation you write — a finding's evidence, a criterion's excerpt — must be copied character for character」。它逐项列举了「哪些是引用」，说明与建议**不在该列表内**，因此无需修改这段规则，语义自然正确。

2000 字符上限：沿用本代码库既有的散文上限约定（`FindingController.StatusRequest.comment` 与前端 textarea 的 `maxlength` 均为 2000），不另立数字。

### 5.2 注入防护

`UNTRUSTED`（:158）与 `REPAIR_INSTRUCTION`（:196）不需改动——它们约束的是**输入**（需求文本、知识、patch、模型自己上一轮的回答），与新增哪些**输出**字段无关。新字段随 `repair` 的「转换而非改写」规则一并受约束。

## 6. 校验与落库

### 6.1 `ReviewOutputValidator.readFinding` 的处置规则

现有哲学是明确的：**只有确定性部分不可用时才丢弃整条**。`evidence` 缺失会丢弃，是因为无证据的 finding 会共享同一个 `evidence_hash`，导致对一条的驳回抑制掉不相干的另一条（:186-191 注释）。散文不进任何哈希，不存在这个问题。

据此：

| 情形 | 处置 | 理由 |
|---|---|---|
| `explanation` / `suggestion` 缺失或空白 | **接受为 `null`**，记 warning | 不因散文缺失丢掉一条有证据的有效 Finding |
| 二者超过 2000 字符 | **截断到 2000**，记 warning | 上限的目的是约束存储与布局，截断已达成；为啰嗦而拒绝是同一种损失 |
| `category` 在词表外 | 存 `null`，记 warning，**哈希仍用原始值** | 见 4.1，防 CHECK 中止整批 |
| `confidence` 缺失或词表外 | 存 `null`，记 warning | 同上；前端回落到「未记录」 |

**没有任何一种新情形会丢弃整条 Finding，也没有任何一种会静默发生**（R2「不得静默丢弃」）。全部走既有 `warnings` 通道，与 `ReviewOutput` javadoc 的理由一致：静默丢弃只会让运维拿到一份更短的报告却无从得知它被缩短过。

加上 `category` 落库后，即便说明与建议双双缺失，页面上仍有 **类型 + 类别 + 证据 + 定位**，不会退回「完全无法理解」。

### 6.2 记录形态：保持扁平，不引入分组抽象

`ReviewOutput.FindingCandidate`（11 个组件）加 4 个 → 15 个；`Finding` 构造器（15 参）加 4 个 → 19 参。

曾考虑用一个嵌套 record（如 `FindingNarrative`）把四个字段打包，使各处只 +1 参。**否决**：JPA 实体仍需要 4 个独立列，用 `@Embeddable` 才能对应，那是为了签名好看而引入的机械成本；而扁平加参数与既有风格逐字一致。这是「最小改动」的直接应用。

代价明码标价：`explanation` 与 `suggestion` 同为 `String` 且相邻，**调换二者可以通过编译**并静默污染每一条 Finding。第 8.4 条测试正是为这个而写。

新字段在实体上一律 `updatable = false`，与 `evidence` / `path` / `line` 同——它们是那一次 Review 的产物，事后不可改。

### 6.3 需要同步更正的既有 javadoc

`ReviewOutput.java:45-47` 现在写着：

> 这里没有承载模型散文的字段。`finding` 表没有标题、严重级别或描述这样的列……因此唯一携带的文本就是 `evidence`。

本任务后该段直接为假，**必须改写**，并写明新的分界：散文字段存在，但一律不进三个哈希。`Finding.java:88-91`（`evidenceHash` 上的注释）与 `FindingKeys.java:18-22` 的表述仍然正确，无需改动。

## 7. API 与前端

### 7.1 API

`ReviewViews.FindingView`（:99，16 个组件）加 4 个；映射点唯一，在 `ReviewDecisionService.java:223`。无 DTO 层，无额外端点，无授权面变化。

`FindingController` 不动：它只管人工生命周期，没有 finding 读取端点——finding 随 Review 视图一并返回。

### 7.2 前端

`frontend/src/features/review/api.ts` 的 `Finding` 接口逐字镜像 `FindingView`，加同样 4 个字段（均可空）。新增两个联合类型 `FindingCategory`、`FindingConfidence`。

`labels.ts` 加 `FINDING_CATEGORY_LABELS`、`FINDING_CONFIDENCE_LABELS` 与 `FINDING_CONFIDENCE_TONES`，形态与既有 `FINDING_STATUS_LABELS` / `FINDING_CONTINUITY_TONES` 完全一致（`Record<T, string>`）。

`FindingCard.vue` 的改动：

1. **阅读层次改为「说明 → 证据 → 建议」**（AC7）。现状是证据摘录直接跟在四个徽章后面。
2. **置信度徽章接真实数据**。:109-113 已有「AI 置信度」`<dt>/<dd>` 占位，当前硬编码「未记录」；改为读 `finding.confidence`，为 `null` 时仍显示「未记录」。:123-125 的 field-hint 需相应改写（现文案「finding 表没有置信度列」将为假）。
3. **类别徽章**并入 record-head，与既有 `finding-type` 徽章同形态。
4. **三个内部哈希收起**（AC8）。`finding-details` 中的 `finding key` / `证据 hash` / `依据 hash` 三项移入 `<details>` 折叠块，摘要文案表明它们用于可核验性。其余四项（验收标准、需求版本、认领人、血缘来源）留在原处。
5. **模型判断与可核验证据必须可区分**（AC10）。说明与建议区块显式标注为模型判断，与 `.evidence` 的「证据摘录」标签形成对照；建议不得呈现为已验证结论。

**长内容不撑破布局（AC9）复用既有形态，不造新原语**：`.evidence`（:276-290）已经有一套可用的 containment —— `max-height` + `overflow: auto` + `white-space: pre-wrap` + `word-break: break-word`。说明与建议区块沿用同一组属性。这也是与 `08-24-frontend-ux-remediation` R4 保持一致的做法，二者不会各造一套。

## 8. 测试：只写锁得住不变量的

| # | 测试 | 锁的不变量 | 为何非有不可 |
|---|---|---|---|
| 8.1 | 模型措辞/置信度改变时，`findingKey`、`evidenceHash`、`basisHash` 三者逐字节不变 | **R3 红线** | AC5/AC6 明文要求。这是整个任务唯一会造成不可见损坏的风险：一旦散文入哈希，跨轮抑制与血缘会静默失效，而现象要到下一轮才显现 |
| 8.2 | 扩展既有 `bothSchemasAreValidJsonAndAgreeOnTheCategoryVocabulary`，断言两处 schema 的新字段声明一致 | 两份**刻意重复**的 schema 字面量不漂移 | 该测试存在的理由就是这个。新增四个重复字段而不覆盖，等于把它当初防的洞重新打开 |
| 8.3 | 校验器：说明缺失 → 保留 Finding + warning；超长 → 截断 + warning；category/confidence 越界 → 存 null + warning | 「不因散文丢弃整条」与 **4.1 的 CHECK 中止防线** | 4.1 是本任务唯一的新增故障模式，且它一旦发生是整批丢失，不是单条 |
| 8.4 | 流水线：落库的 Finding 携带正确的 explanation / suggestion / category / confidence | 6.2 明码标价的**参数调换**风险 | 两个相邻 String 参数调换可通过编译且静默污染全部数据 |
| 8.5 | 前端：FindingCard 渲染说明与建议，且三个哈希不在默认视图 | AC7 / AC8 | 前端唯一的行为断言 |

**不写**：新字段的 getter/setter 测试、四列可空性的单独测试（迁移跑通即证明）、每个 category 词表值的逐一断言、置信度三档的逐档渲染测试。这些都答不上「锁的是哪个不变量」。

`RULE_VERSION` 的现有测试（若存在断言其为 `"1"`）不动，本任务不改它。断言 `prompt_version` 为 `"review-1"` 的测试需随 2. 节升版一并更新。

## 9. 决策记录 D021

当前最新为 D020，故本任务写 D021，格式逐条对齐 D020（`**决定**` / `**数据实现**` / `**理由**` / `**明确不做**`）。至少覆盖：

- 为何新增散文字段，以及它们与 `evidence` 的分工；
- 为何四者一律不进三个哈希，而 `category` 落库不改变 `findingKey`；
- **置信度取舍**（R5、AC11）：记录，但只用 `HIGH/MEDIUM/LOW` 三档闭合词表，不用数值。理由是 `PRD.md:144` 与 `ARCHITECTURE.md:459` 早已要求「AI 置信度、Finding 状态、Review Decision 三者不互相替代，UI 上必须分开呈现」，`FindingCard.vue:109` 也早已留有占位——本任务是兑现既有约束而非扩张；分档而非数值，是因为模型自报置信度未经校准，精确数字会暗示已校准。它不进任何哈希、不参与任何自动门禁或状态流转，与 `LEGACY-MIGRATION-MATRIX.md:109` 对 legacy `FindingConfidenceService` 的批注（「评测/展示，不自动 gate」「未校准权重不得决定 PR 是否通过」）一致，且 `:112` 把按置信度自动 gate 的 `FindingDecisionEntity` 标为 DROP，本任务不触碰该边界。
- 为何 `ReviewPrompts.VERSION` 必须升而 `RULE_VERSION` 必须不升。

## 10. 文档同步

| 文件 | 需要更新的内容 |
|---|---|
| `docs/v2/ARCHITECTURE.md` | `finding` 表列清单；模型输出契约；迁移数 8 → 9；:459 关于置信度的表述改为「已记录，分档」 |
| `docs/v2/API.md` | Finding 读取字段清单 |
| `docs/v2/PRD.md` | :144 置信度表述 |
| `docs/v2/DECISIONS.md` | 追加 D021 |
| `docs/v2/README.md` | 迁移数、已知缺口 |
| `AGENTS.md` / `CLAUDE.md` | 「8 个 Flyway 迁移」「317 测试」等计数 |
| `docs/v2/TEST-ISSUES.md` | T-010 行状态 |

表数**不变**（仍 19 张，本任务只加列不加表）。

## 11. 边界（与 PRD Out of Scope 一致）

不产出补丁、不自动改码、不自动流转 Finding 状态（AC12）；不改 `RULE_VERSION` 或任何哈希输入构成；不新增顶层包、一级导航、AI runtime、第二 Review 流程或运行时依赖；不触碰正式评测冻结、语料清单、holdout 台账与原始输出（AC16）。

评测证据不受影响这一点已核实：`evaluation/tools/*.py` 对后端零引用，改 Prompt 契约不会使冻结的三臂结论失效——但答辩表述仍须保持精确（冻结实验证明的是「知识进上下文有用」）。

## 12. 实施偏离（按 D016 的惯例显式记录，而不是回头改写设计）

实施过程中有五处与本文原始内容不同，全部已落到代码与文档：

1. **散文字段定名 `explanation` 而非 `description`。** JSON Schema 里 `"description"` 本身就是注解键，用它做属性名会写出 `"description": {..., "description": ...}`，读 schema 与写测试都容易看错；而 `explanation` 正是本任务标题与目录名里的词。契约名与列名是后续最贵的东西，V9 落地后再改就要 V10，故在实施第一步即改。本文第 1、4、5、6 节的字段名已同步。

2. **哈希不变性测试（8.1）本来就存在。** `ReviewOutputValidatorTest.rewordingTheModelsProseChangesNoKeyAndNoHash` 早已在，但它当时喂的 `title` / `severity` / `description` 都是校验器会忽略的假字段。若不改，它会从「证明散文不进哈希」退化成「证明未知字段被忽略」。因此这一条是**改写既有测试**而不是新写一条：改喂三个真字段，并把 `category` 的两种写法（`CORRECTNESS` 与 `Correctness `）纳入同一断言。

3. **schema 同步断言（8.2）收得比设计更紧。** 原计划逐个断言三个新字段一致，实际改为断言两处 finding `items` 节点**整体相等**——一条断言同时覆盖词表一致、`required` 一致与全部属性声明一致，且以后再加字段也不会漏。测试名随之由 `...AgreeOnTheCategoryVocabulary` 改为 `...DescribeTheSameFinding`。

4. **多写了一条 `V9MigrationTest`，这是设计时漏掉的一个真实风险。** `ALTER TABLE ... ADD CONSTRAINT ... CHECK` 会立即校验既有行，所以一个不容忍 NULL 的 CHECK 会在空的测试库上通过、却让迁移在有 finding 的真实部署库上直接失败。`FoundationDatabaseTest` 跑的是空库，结构上看不到这一类失败。该测试先迁到 V8、写一条 finding、再迁完，是唯一能覆盖它的形态。这条知识已写入 `.trellis/spec/backend/database-guidelines.md`。

5. **`docs/v2/API.md` 无需改动。** 第 10 节把它列为待更新，实际它只覆盖账户、成员目录与 SCM 身份契约，通篇没有 finding 接口——finding 随 Review 视图返回，没有独立端点。

另外，第 7.2 节计划的 `FINDING_CONFIDENCE_TONES` **刻意没有实现**：类别与置信度都不是严重度，给 `LOW` 配色等于凭空造出一套后端不存在的风险模型（迁移矩阵已把按置信度自动 gate 的形态标为 DROP），且颜色一旦带上价值判断，未经校准的置信度就会被读成质量结论。两者统一用 `badge-neutral`，语义由文字承载；理由写在 `labels.ts` 的注释里。
