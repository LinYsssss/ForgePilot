# V2 方案复审（R2）

状态：**已批准并完成 `docs/v2/` 同步（2026-08-19）**。本任务为文档级复审，未执行 `task.py start`，未产出任何应用代码。

## 目标

在 Phase 1 开工前，对已批准的 Final R1 方案做一次第一性原理复审，找出会在实施期变成返工的契约级缺口。**不重新设计产品定位、模块边界或数据模型主体**，不在本任务内编写任何应用代码。

复审范围：`docs/v2/` 全量（README、PRD、ARCHITECTURE、IMPLEMENTATION-PLAN、FINAL-EXECUTION-PLAN、ADR-001..008、LEGACY-MIGRATION-MATRIX、AI-HANDOFF、CLEANUP-AND-LEGACY）。

## 确认成立、不再讨论的部分

- 因果链（需求/AC = 应该做什么，项目知识 = 本项目怎么做，Diff = 实际改了什么）唯一清晰，可作为一切模块的删除测试标准。
- 8 顶层包 + 单向依赖 + ArchUnit 五条强制项，能真正阻断旧架构复发。
- ADR-001（无维度 vector）、ADR-003（Review 身份）、ADR-006（复合外键代替运行时校验）是原方案质量最高的三项决策，共同取向为「用约束代替纪律」。本轮所有新决议沿用该取向。
- 明确不做清单（Agent/Patch/MQ/Sandbox/多仓库/聊天）与 P1 后置纪律齐备。

---

## 冻结的核心模型

```text
Review Identity = pull_request_id + head_sha + requirement_revision_id
Decision Gate   = pull_request_id + head_sha
```

```sql
UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, requirement_revision_id)
```

身份由**真实上下文派生**，不再有任何人工维护的计数器。行为后果：

| 事件 | 结果 |
|---|---|
| `NULL → REQ-A` | 产生新 Review |
| `REQ-A → REQ-B` | 产生新 Review |
| `REQ-A → NULL` | 产生无需求上下文 Review |
| Requirement v1 → v2 | 产生新 Review |
| `REQ-A → REQ-B → REQ-A` | 复用原 Review，不重复运行 |
| 知识库更新 | **不**改变生产 Review 身份（避免重审风暴） |
| Prompt / 模型 / 检索策略变化 | 进入 Evaluation，不制造生产 Review |

Decision Gate 独立于身份：**同一 `head_sha` 一旦出现 `REQUEST_CHANGES`，任何上下文版本都不能再 APPROVE**，解除只能靠新 head SHA；MVP 不提供 LEADER 绕过入口。这防止「被审查方改需求关联 → 换身份 → 重跑 → 拿 APPROVE」洗掉退回结论。

`context_revision` **不存在**，须确保它未出现在 `pull_request`、`review`、Review 唯一键、`review_activity` 判断、Finding 延续算法与 PR 关联事件中。

---

## 数据模型变更总览（14 → 16 表）

ARCHITECTURE §2.1 原文即规定「新增表必须有已发生的业务事实 + ADR 证明现有模型无法表达」。两张新表均满足该条件；表数是复杂度提醒，不应为守住数字而把两个不同领域事实塞进一张表。

### 新增表

| 表 | 职责 | 关键字段 |
|---|---|---|
| `requirement_revision` | 不可变需求正文版本 | requirement_id、正文、actor、reason、created_at |
| `pull_request_requirement_event` | **仅**记录 PR↔需求关联变化 | pull_request_id、from/to_requirement_id、actor_user_id、reason、created_at |

### 既有表新增列

| 表 | 新增列 |
|---|---|
| `project_member` | `scm_external_user_id`（权限依据）、`scm_username`（仅显示）、`scm_identity_verified_at`；`(project_id, scm_external_user_id)` 唯一 |
| `pull_request` | `author_external_user_id`、`author_username`（不可变快照）、`author_user_id`（派生映射） |
| `requirement` | `current_revision_id`（可空，回填） |
| `acceptance_criterion` | 归属 `requirement_revision`；`ac_key`（稳定不可变业务身份，如 `AC-0001`）、`sort_order`（仅显示顺序） |
| `review` | `requirement_revision_id` |
| `finding` | `evidence_hash`、`continuity`、`carried_from_finding_id`、`finding_type` |
| `ai_call_log` | `requirement_id`、`requirement_revision_id`（均可空） |

---

## 各缺口决议

### G1 SCM 身份映射（方案 B+）→ ADR-010

保留「开发者可修改本人 PR 的需求关联」，不建 OAuth／身份中心。

- 身份由 LEADER 在成员管理页配置并经 SCM API 解析为稳定外部 ID，**开发者不得自行声明**。
- 权限：LEADER 可改项目内任意 PR 关联；DEVELOPER 仅当 `author_user_id` = 当前用户；未映射时仅 LEADER。
- **禁止以用户名比对授权**（可改名、可重名、可被重新注册）。
- 排除方案 C（独立 `scm_user_identity` + OAuth）：多仓库／多 Provider／多账号／SCM 登录均未发生，属预埋。

工程限定：

1. 作者外键指向 `project_member` 而非 `user_account`：`(project_id, author_user_id) → project_member(project_id, user_id)`，成员移出项目时自动退化为「仅 LEADER 可改」。
2. 必须用 PostgreSQL 15+ 的**列级** `ON DELETE SET NULL (author_user_id)`；普通形式会同时清空 NOT NULL 的 `project_id` 而报错。**本项目最低 PostgreSQL 版本锁定为 15**，Testcontainers 镜像、Compose 与部署环境须统一。
3. `author_external_user_id` / `author_username` 为不可变快照，永不重算；`author_user_id` 为派生缓存，每次 webhook 同步按 `(project_id, provider, external_user_id)` 幂等重算，使后绑定的成员身份在下次 push 自动修复。
4. **仓库不可原地更换**：`scm_repository.project_id` UNIQUE 使新旧仓库无法并存。已产生 PR 后 `provider + external_id` 不可修改；token / webhook secret / api_base 可更新；真要换仓库须新建项目。此为**用户可见的产品限制**，须写入 PRD §8。未来支持换仓/多仓时改为 Project 1:N Repository + 单活动仓库约束，届时才需要「是否当前活动仓库」守卫——**当前不写该守卫**（永真判断即死代码），只在 ADR-010 记录该前置条件。
5. 落地：`project_member` 三列与唯一约束在 Phase 2；`pull_request` 三列与权限判断在 Phase 5。

### G2 Requirement 状态推进者 → ADR-011，需改 PRD §5

`IN_REVIEW` 从持久化状态中**删除**。持久状态收敛为 `DRAFT / READY / IN_DEVELOPMENT / DONE / CANCELED`：

- `DRAFT → READY`：LEADER 确认需求。
- `READY → IN_DEVELOPMENT`：与首次指派在**同一事务内**完成；后续更换负责人不再改变状态。
- `→ DONE`：LEADER 确认全部关联工作完成。
- AI、Webhook、PR、Review **一律不得推进**这些状态。

评审进展改为只读派生量 `review_activity`，不落表，取值 `FAILED / CHANGES_REQUESTED / REVIEWING / PENDING / MIXED / APPROVED / NO_PR`，多 PR 按该顺序确定性归并。页面同时展示两个正交维度（「需求状态：开发中 / 评审活动：修改待复审」）。

1. 必须按 **PR 当前 head + PR 当前关联 revision + Requirement 当前 revision** 三者同时匹配计算；缺任一项都会把历史结论误显示为当前结论。
2. `FAILED` 一档不可省略：缺它则 AI 失败的 PR 永远停在 `REVIEWING`，与 ARCHITECTURE §3.2「FAILED 需人工重试」的可见性要求冲突。
3. 需求列表页须用一次聚合查询计算，禁止 N+1。

### G3 身份与决策闸门分离 → 修订 ADR-003 / 007 / 008（不另开新号）

模型见上文「冻结的核心模型」。ADR-003 表述改为「一个 head 在一个上下文版本下只有一条 Review」，并补「终局 Decision 闸门仍以 head_sha 为准」。

1. **终局决策必须串行化**：事务内 `SELECT ... FOR UPDATE` 锁 `pull_request` 行 → 校验当前 head → 查该 head 是否已有 `REQUEST_CHANGES` → 写 Decision → 提交。普通 EXISTS 查询在两个 Reviewer 并发时会同时通过。需补并发集成测试。
2. **关联修改权限按是否已有 Review 收紧**：当前 head 尚无 Review → 本人 PR 的 DEVELOPER 可修正；已有任意 Review → 仅 LEADER。LEADER 亦不能解除 head 级 `REQUEST_CHANGES`。
3. `FAILED` 重试复用同一 Review 行；`COMPLETED` 永不覆盖。
4. 审计：`pull_request_requirement_event` 只记关联变化，与关联修改在同一事务内写入。排除「`pull_request` 追加式审计 JSON」（约束管不住、查不动即不算审计）与「把 `finding_event` 泛化为 `audit_event`」（`entity_id` 多态后 ADR-006 复合外键约束不上，等于拿强完整性换表数数字）。

### G4 Finding 跨 Review 血缘 → ADR-009

每次 Review 保存自己独立不可篡改的 Finding，跨轮关系用血缘字段表达。

| 概念 | 含义 |
|---|---|
| `status` | 人工处理生命周期（PRD §5 原状态机不变） |
| `continuity` | 跨 Review 关系：`NEW / PERSISTING / SUPPRESSED` |
| `finding_key` | 逻辑上是否同一个问题 |
| `evidence_hash` | 问题依据是否实质未变 |
| `carried_from_finding_id` | 直接来源 |
| `NOT_REPORTED` | 上轮有本轮无——**查询推导，不持久化** |

1. `evidence_hash` 必须基于**确定性源码证据**生成，**禁止哈希 LLM 生成的问题描述**；哈希前统一换行符、去除行号与非语义空白，否则模型换个措辞就击穿抑制。
2. 上轮 `REJECTED` 且 `evidence_hash` 未变 → 本轮 `SUPPRESSED`，不要求重复驳回；UI 折叠呈现，Reviewer 可重新打开。
3. 上轮问题仍在 → `PERSISTING`，但新 Finding 一律以 `OPEN` 落库，**不继承旧工作流状态**。
4. 本轮未再报告 → 仅推导为 `NOT_REPORTED`，**不得自动认定已修复**（可能只是被分批预算挤掉，与 ADR-002 禁止静默截断一致）。
5. `evidence_hash` 实质变化 → 视为新问题，重新确认。
6. **抑制作用域仅限同一 PR**，不跨 Requirement、仓库或其他 PR。
7. **Finding 身份用 `requirement_id + ac_key`**，不用 `acceptance_criterion.id`（属具体 revision），也不用序号（插入/排序会变）。`ac_key` 稳定不可变；措辞修正但语义不变时保留 `ac_key`，语义实质改变时创建新 `ac_key`。`Finding.ac_id` 仍指向审查时的具体 AC 版本，用于历史审计。
8. 通用代码质量问题按 `finding_key + evidence_hash` 延续；需求/AC 类问题的 `finding_key` 必须含需求维度，使 REQ-A 的误报抑制不污染 REQ-B。为使组成规则确定可测，`finding` 显式增 `finding_type ∈ {REQUIREMENT, CODE_QUALITY}`，不靠 `ac_id` 是否为 NULL 反推。
9. ADR-002 §7 的 fingerprint 定义须从「批内去重」扩写为「批内去重 + 跨 Review 血缘键」。

### G5 评测前移（改实施计划与评测规范，不新增 ADR）

**既有事实**：RepoSage `evaluation/README.md` 中评测集**已固定切分**为 development 26 例 + holdout 12 例，合计 38 例，不得重新切分。

- **Phase 1**：建立评测契约——固定指标定义、确定性评分器骨架、从现有 26 例 development 中挑 10–15 例作为快速集。此时不调用尚不存在的 Review Engine。（需放宽 Phase 1「无业务代码」措辞：评测设施不属业务代码。）
- **Phase 6**：每完成一个实验臂即在 development 集试跑（`Diff+LLM` → `+Requirement+AC` → `+Knowledge`），不等 Phase 6 结束。Prompt、TopK、证据组织**只允许依据 development 集调整**；**Phase 8 之前禁止运行 holdout**。
- **Phase 8**：配置冻结后同时报告 development、holdout、全量三组；holdout 为首次运行，不得据其调 Prompt。

holdout 仅 12 例，论文必须给出置信区间或明确的不确定性说明，与 PRD §8 已有的「人工构造演示缺陷」声明并列。

### G6 前端纵切

每个 Phase 的退出标准增加「该 Phase 的最小可用真实界面」。一级导航仍为三个，页面清单不变，只改建造顺序。

- Phase 1：冻结视觉方向与组件规范，建前端脚手架（路由、请求层、设计令牌、组件基础）
- Phase 2：登录页 + 项目/成员列表
- Phase 3：需求列表 + 详情
- Phase 4：知识上传 + 实现建议展示
- Phase 5：PR 列表
- Phase 6：Review 详情（只读）
- Phase 7：Finding 人工闭环交互 + 统一打磨 + 浏览器验收 + 完整 E2E

### G6-A 前端视觉与动效（用户新增需求）

约束：不新增一级导航、不改页面清单；不得损害 PRD §5「AI 置信度 / Finding 状态 / Review Decision 三者必须分开呈现」的可读性；必须尊重 `prefers-reduced-motion`，动效不得成为读取信息的前提；图表遵循 `dataviz` skill 规范。

**不引入** `LinYsssss/product-engineering-kit` 的任何 skill。判断依据：`.trellis/spec/frontend/` 已存在且三端共享、六份文件均为 "To fill"——投递机制本就具备，缺的只是内容；该 skill 的编排价值建立在 `ui-ux-pro-max` / `Impeccable` / `animation-vocabulary` 等专家 skill 之上，本机一个未装，去掉被编排对象后剩下的是一份检查清单；在 Trellis 工作流 + 8 Phase 闸门之上再叠 7 阶段工作流，正是上一版失控的同一模式。`coding` skill 与现有 workflow 大面积重叠，一并不引入。

借用其内容的执行方式：

1. 按其 Stage 2 标准做一次三方向视觉对比（结构与性格不同，非换配色；各附商业契合度、密度、字体与语义色角色、一个记忆点、禁用模式），以可交互 HTML Artifact 呈现，由用户选定。**属 Phase 1 工作，待授权后执行。**
2. 选定结果落入 `.trellis/spec/frontend/`：新增 `design-contract.md`、`motion.md`；填充已有六份文件。
3. Stage 7 设计漂移检查清单抄入 `quality-guidelines.md`，作为各 Phase 前端验收项。
4. 纯视觉决策记于 `.trellis/spec/frontend/`，不占 `docs/v2/adr/` 编号。

### G7 需求版本化 → 并入 ADR-011

否决「原地改需求 + 批量 bump 关联 PR」，理由：构成 `requirement → scm` 反向写依赖，违反 ARCHITECTURE §1.3；`pull_request_requirement_event` 不应兼记需求正文与 AC 变更史；原地修改丢失需求版本（`review.context_snapshot_json` 只能证明有 Review 时的上下文，未触发 Review 的修改无记录）。

```text
requirement                  稳定业务身份、负责人、状态、current_revision_id
└── requirement_revision     不可变需求正文版本 + 修改人/原因/时间
    └── acceptance_criterion 归属具体 revision，旧 AC 永久保留
```

- DRAFT 阶段编辑工作版本；READY 时发布不可变 Revision 1。
- READY 后修改由 LEADER 创建新 Revision，**必须填写变更原因**；AC 随 Revision 重新生成，旧 AC 永久保留。
- Review 创建时读取当前需求版本并保存 `requirement_revision_id`。
- **需求版本发布后不自动重审**：关联 PR 显示「审查已过期」→ LEADER / Reviewer 手动点击重新审查 → Engine 读取 v2。`requirement` 既不写 SCM 也不写 Review，单向依赖得以保持。
- 同一 head 已存在 `REQUEST_CHANGES` 时，换需求版本仍不能 APPROVE。

工程限定：

1. 唯一约束须用 `UNIQUE NULLS NOT DISTINCT`——未关联需求时 `requirement_revision_id` 为 NULL，默认语义下 NULL 互不相等，会允许同一上下文重复建 Review。
2. `ac_key` 独立、稳定、不可变（如 `AC-0001`）；`sort_order` 仅负责显示顺序，不承担业务身份。
3. 互为外键按固定顺序解决，**不用 DEFERRABLE**（更直观也更好测）：建 `requirement`（`current_revision_id = NULL`）→ 建 `requirement_revision` → 建该 Revision 下的 AC → 回填 `current_revision_id`；用复合外键保证 Revision 确属该 Requirement。
4. ADR-006 §6 的 `(requirement_id, ac_id) → acceptance_criterion` 复合外键须随 AC 归属变更同步调整。

### G8 4 GB 部署机

容量验证**前移到 Phase 1**：底座跑起来时即实测 PostgreSQL + JVM 与现有常驻服务（cpa / cmp / cloudflared）共同运行的常驻内存，而非等 Phase 8 部署才发现装不下——彼时功能已冻结、无回旋余地。ARCHITECTURE §7.2 既有要求（JVM 与 Postgres 显式设上限、并发 Review 降为 1）保持不变。加入 Phase 1 退出标准。

### G9 文档漂移

- 修正 ADR-006 §3 与 ARCHITECTURE §2.1 的 `project_id` 不一致（`review`、`finding_event`、`ai_call_log` 应标注携带 `project_id`）。
- `ai_call_log` 增加可空的 `requirement_id` 与 `requirement_revision_id`，使 Requirement Quality 与 Implementation Guidance 的调用可精确追溯，也满足 G5 提前评测所需粒度。
- 「14 表」散布于 ARCHITECTURE §2.1、README、PRD、FINAL-EXECUTION-PLAN §4、AI-HANDOFF 五处，统一改为 16 表——此事本身即单一事实源纪律的反例。

---

## ADR 编号约定

| 编号 | 内容 |
|---|---|
| ADR-009 | Finding continuity（G4） |
| ADR-010 | SCM 身份映射与仓库不可变边界（G1） |
| ADR-011 | Requirement 状态、需求版本化与派生 Review Activity（G2 + G7） |
| 修订 ADR-002 | fingerprint 定义扩写为「批内去重 + 跨 Review 血缘键」 |
| 修订 ADR-003 / 007 / 008 | 身份与决策闸门分离（G3），不另开新号 |
| 不新增 ADR | G5（改实施计划与评测规范）、G6/G6-A（改实施顺序与前端 spec）、G8、G9 |

## 验收标准

- [x] `docs/v2/` 与 ADR 全部按上述决议更新，五处「14 表」表述统一为 16。
- [x] 全仓不存在 `context_revision` 残留。
- [x] ADR-009 / 010 / 011 写成并接受；ADR-002 / 003 / 007 / 008 完成修订。
- [x] PRD §5 删除 `IN_REVIEW`；PRD §8 增加「有 PR 后不可换仓库」与 PostgreSQL 15 下限。
- [x] IMPLEMENTATION-PLAN 与 FINAL-EXECUTION-PLAN 各 Phase 退出标准含：最小可用真实界面（G6）、评测三段式（G5）、Phase 1 内存实测（G8）。
- [x] 本任务不产出任何应用代码。

## 已知风险与后续前置条件

- 最低 PostgreSQL 15 是硬依赖，来自两处（列级 `ON DELETE SET NULL`、`NULLS NOT DISTINCT`）；Testcontainers、Compose、部署环境须统一，Phase 1 即验证。
- holdout 仅 12 例，论文结论的统计强度有限。
- 前端纵切使各 Phase 周期变长，早期页面在后续 Phase 会被返工，总工作量持平或略增。
- ADR-002/003/007/008 属经用户批准的例外性回改，已在 `adr/README.md` 记录例外理由；此后不得再回改已接受的 ADR。

## 任务记录同步

Codex 侧在 Windows 检出上另有 `08-19-review-contract-gaps` 任务记录同一批决议，由用户自行清理。本任务目录保留为本轮复审的过程记录；**最终权威只在 `docs/v2/` 与 `docs/v2/adr/`**，实施期以后者为准。
