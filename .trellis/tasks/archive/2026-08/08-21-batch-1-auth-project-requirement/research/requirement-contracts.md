# Research: Phase 3 Requirement / Revision / AC 既定契约

- **Query**: 把 Phase 3（Requirement + Revision + AC）的全部既定契约从权威文档中抽取成可直接用于写 `design.md` 的研究文件
- **Scope**: internal（仅 `docs/v2/**` 与 `.trellis/spec/frontend/**` 权威文档）
- **Date**: 2026-08-21

## 0. 引用约定与权威边界

| 简写 | 文件 |
|---|---|
| PRD | `docs/v2/PRD.md` |
| ARCH | `docs/v2/ARCHITECTURE.md` |
| DEC | `docs/v2/DECISIONS.md` |
| PLAN | `docs/v2/IMPLEMENTATION-PLAN.md` |
| MATRIX | `docs/v2/LEGACY-MIGRATION-MATRIX.md` |

引用一律写成 `文件:行`。**本文只做摘录与结构化，不新增设计**；任何文档没写的内容一律进 §9 开放问题，不在正文里代替文档拍板。

单一事实源纪律：16 表、依赖规则、状态机、运行边界**只在 ARCH 定义**（ARCH:10）。发现规则冲突必须先停下改文档或新增决策，不得用代码"自行解释"（PLAN:13）。

---

## 1. 三张表的完整定义

### 1.1 通用约定（ARCH §2.4:235-247）

| 对象 | 规范 | 行 |
|---|---|---|
| 表名 / 列名 | `snake_case` 单数 | ARCH:239 |
| 主键 | `id`，BIGINT identity | ARCH:240 |
| 外键列 | `<被引用表>_id` | ARCH:241 |
| 时间列 | `created_at` / `updated_at`，`timestamptz`，UTC 存储 | ARCH:242 |
| 枚举 | 数据库存 `varchar` + `CHECK`，Java 侧 enum；全大写下划线 | ARCH:243 |
| Flyway | `V<n>__<snake_case>.sql`，V1 为唯一初始化脚本 | ARCH:244 |
| REST 路径 | `/api/projects/{projectId}/...`，项目内资源一律带 projectId 段 | ARCH:245 |
| 错误响应 | `common` 统一 `{code, message, traceId}`；不复用 Legacy error code | ARCH:246 |
| Java 类 | 实体无 `Entity` 后缀（`Requirement` 而非 `RequirementEntity`） | ARCH:247 |

项目隔离总则（ARCH §2.3:133）：除全局 `user_account` 与项目根 `project` 外，**所有项目作用域表都携带 `project_id`**；项目内外键必须把 `project_id` 一并带入，被引用表提供对应复合唯一键；指向全局 `user_account` 的审计 actor 是唯一例外。数据库负责拒绝跨项目写入，**Repository 读路径仍必须接受 `projectId`，禁止裸 id 查询后再补权限判断**。约束冲突统一映射为 409/422（ARCH:133、DEC:71）。

PostgreSQL **最低 15**（ARCH:448、PRD:184）；对本批次三张表本身不构成语法依赖（15 的两处硬语法在 `pull_request` 与 `review` 上），但 Testcontainers/Compose/部署必须统一到 15+。

### 1.2 `requirement`

原文（ARCH:89）：

> | `requirement` | 需求稳定身份、指派、状态 | project_id、assignee_id（nullable，复合 FK 指向 project_member）、status、current_revision_id（可空，回填；复合 FK `(project_id,id,current_revision_id)` 指向自身 Revision）；`UNIQUE(project_id,id)` |

逐列：

| 列 | 来源 | 说明 |
|---|---|---|
| `id` | ARCH:240 | BIGINT identity，PK |
| `project_id` | ARCH:89 | NOT NULL，→ `project(id)` |
| `assignee_id` | ARCH:89 | **nullable**；复合 FK `(project_id, assignee_id) -> project_member(project_id, user_id)`（ARCH:138-139） |
| `status` | ARCH:89 | varchar + CHECK（ARCH:243）；取值见 §3 |
| `current_revision_id` | ARCH:89 | **可空、回填**；复合 FK `(project_id, id, current_revision_id) -> requirement_revision(project_id, requirement_id, id)`（ARCH:152-154）。详见 §2 |

**注意：`requirement` 上没有 `title`。** 正文字段（title/background/description）全部在 `requirement_revision`（ARCH:90）。需求列表页要显示标题必须 JOIN `current_revision_id` 指向的 revision——这是列表查询设计的直接后果。

`requirement` 也没有列出 `created_at/updated_at/created_by`；ARCH:242 只给了时间列的**命名约定**，没有声明这三张表各自有哪些时间列 → 见 §9 开放问题 O-14。

唯一键：`UNIQUE(project_id, id)`（ARCH:89）。

### 1.3 `requirement_revision`

原文（ARCH:90）：

> | `requirement_revision` | 不可变需求正文版本与该版本的质量结果 | project_id、requirement_id、seq、title/background/description、created_by、change_reason、created_at、quality_json/quality_version/quality_checked_at；`(requirement_id,seq)` unique；`UNIQUE(project_id,id)`、`UNIQUE(project_id,requirement_id,id)`（D006/D011） |

逐列：

| 列 | 来源 | 说明 |
|---|---|---|
| `id` | ARCH:240 | BIGINT identity，PK |
| `project_id` | ARCH:90 | NOT NULL |
| `requirement_id` | ARCH:90 | 复合 FK `(project_id, requirement_id) -> requirement(project_id, id)`（ARCH:141-142） |
| `seq` | ARCH:90 | 版本序号；Revision 1 在创建需求时同步建立（DEC:118） |
| `title` | ARCH:90 | 需求正文 |
| `background` | ARCH:90 | 需求正文 |
| `description` | ARCH:90 | 需求正文 |
| `created_by` | ARCH:90 | 文档未写指向哪张表 → §9 O-15 |
| `change_reason` | ARCH:90 | 新 Revision 必填原因（PRD:119、DEC:118）；Revision 1 无原因 → §9 O-06 |
| `created_at` | ARCH:90 | timestamptz UTC |
| `quality_json` | ARCH:90 | 见 §5 |
| `quality_version` | ARCH:90 | 见 §5 |
| `quality_checked_at` | ARCH:90 | 见 §5 |

唯一键三条（原样）：

- `(requirement_id, seq)` unique
- `UNIQUE(project_id, id)`
- `UNIQUE(project_id, requirement_id, id)`

**观察**：`(requirement_id, seq)` 是三张表里**唯一一条不带 `project_id`** 的唯一键。因为 `requirement_id` 已经函数决定 `project_id`（由 `(project_id, requirement_id) -> requirement(project_id, id)` 保证），所以不构成隔离漏洞，但与其余唯一键的写法不一致，实现时按原文照抄即可，不要"顺手"补 `project_id`（那会改变约束语义并影响索引）。

### 1.4 `acceptance_criterion`

原文（ARCH:91）：

> | `acceptance_criterion` | AC，归属具体 revision | project_id、requirement_revision_id、`ac_key`（稳定不可变业务身份）、`sort_order`（仅显示）、text；`(requirement_revision_id,ac_key)` unique；`UNIQUE(project_id,id)`、`UNIQUE(project_id,requirement_revision_id,id)`（D011） |

逐列：

| 列 | 来源 | 说明 |
|---|---|---|
| `id` | ARCH:240 | BIGINT identity，PK |
| `project_id` | ARCH:91 | NOT NULL |
| `requirement_revision_id` | ARCH:91 | 复合 FK `(project_id, requirement_revision_id) -> requirement_revision(project_id, id)`（ARCH:156-158） |
| `ac_key` | ARCH:91 | **稳定不可变业务身份**；跨 Revision 稳定，**不得用数据库行 id 或显示顺序代替**（ARCH:350） |
| `sort_order` | ARCH:91 | **仅用于显示** |
| `text` | ARCH:91 | AC 正文 |

唯一键三条（原样）：

- `(requirement_revision_id, ac_key)` unique
- `UNIQUE(project_id, id)`
- `UNIQUE(project_id, requirement_revision_id, id)`

**AC 归属的是 Revision，不是 Requirement**（ARCH:116 的 ER 图 `requirement_revision ||--o{ acceptance_criterion`；DEC:118 "AC 归属具体 Revision"）。

### 1.5 每条唯一键支撑哪个复合外键

这是 D006 的强制映射：「所有项目内跨表引用使用包含 `project_id` 的复合外键；被引用表提供对应唯一键」（DEC:65）。逐条对齐 ARCH §2.3:135-194 的完整清单：

| 唯一键 | 所在表 | 支撑的复合外键 | 来源行 |
|---|---|---|---|
| `UNIQUE(project_id, id)` | `requirement` | `requirement_revision (project_id, requirement_id) -> requirement(project_id,id)` | ARCH:141-142 |
| 同上 | | `knowledge_document (project_id, source_requirement_id) -> requirement(project_id,id)`（Phase 4） | ARCH:144-145 |
| 同上 | | `requirement_attachment (project_id, requirement_id) -> requirement(project_id,id)`（Phase 4） | ARCH:147-148 |
| 同上 | | `pull_request (project_id, requirement_id) -> requirement(project_id,id)`（Phase 5） | ARCH:162 |
| 同上 | | `ai_call_log (project_id, requirement_id) -> requirement(project_id,id)`（Phase 4/6） | ARCH:186 |
| 同上 | | `pull_request_requirement_event (project_id, from_requirement_id)` 与 `(project_id, to_requirement_id) -> requirement(project_id,id)`（Phase 5） | ARCH:192-193 |
| `UNIQUE(project_id, id)` | `requirement_revision` | `acceptance_criterion (project_id, requirement_revision_id) -> requirement_revision(project_id,id)` | ARCH:156-158 |
| `UNIQUE(project_id, requirement_id, id)` | `requirement_revision` | **`requirement (project_id, id, current_revision_id) -> requirement_revision(project_id, requirement_id, id)`**（自引用，见 §2） | ARCH:152-154 |
| 同上 | | `review (project_id, requirement_id, requirement_revision_id) -> requirement_revision(project_id, requirement_id, id)`（Phase 6） | ARCH:168-169 |
| 同上 | | `ai_call_log (project_id, requirement_id, requirement_revision_id) -> requirement_revision(project_id, requirement_id, id)`（Phase 4/6） | ARCH:187-188 |
| `(requirement_id, seq)` unique | `requirement_revision` | **不支撑外键**；保证同一需求内版本序号唯一（业务约束，非引用完整性） | ARCH:90 |
| `UNIQUE(project_id, requirement_revision_id, id)` | `acceptance_criterion` | `finding (project_id, requirement_revision_id, ac_id) -> acceptance_criterion(project_id, requirement_revision_id, id)`（Phase 6） | ARCH:173-174 |
| `UNIQUE(project_id, id)` | `acceptance_criterion` | **§2.3 清单中当前没有任何外键指向它**；属 D006「被引用表提供对应唯一键」的统一模式 / 前置声明 | ARCH:91 vs ARCH:135-194 |
| `(requirement_revision_id, ac_key)` unique | `acceptance_criterion` | **不支撑外键**；保证同一 Revision 内 `ac_key` 唯一（业务身份约束） | ARCH:91 |

**要点**：三条"三列唯一键"（`requirement_revision(project_id, requirement_id, id)`、`acceptance_criterion(project_id, requirement_revision_id, id)`）的存在理由完全一样——让复合外键**在一次约束里同时证明三件事**：同一项目、属于正确的父行、目标行存在。这就是 D006 拒绝"运行时逐处校验"的落点（DEC:69）。

`requirement.assignee` 的复合 FK `(project_id, assignee_id) -> project_member(project_id, user_id)`（ARCH:138-139）依赖 `project_member` 的 `(project_id, user_id)` unique（ARCH:88，Phase 2 建）——**批次 1 内跨 Phase 依赖：Phase 3 的 `requirement` 建表依赖 Phase 2 的 `project_member` 唯一键先落地。**

### 1.6 ER 关系（ARCH §2.2:115-116）

```text
requirement          ||--o{ requirement_revision : "不可变版本 D011"
requirement_revision ||--o{ acceptance_criterion : ""
```

ER 图**没有画** `requirement -> requirement_revision` 的 `current_revision` 反向边——该边只在 §2.1:89 与 §2.3:152-154 定义。实现时以 §2.1/§2.3 为准。

---

## 2. 最棘手的一条：`requirement.current_revision_id` 自引用复合外键

### 2.1 事实（逐条原文）

- ARCH:89 —— `current_revision_id（可空，回填；复合 FK (project_id,id,current_revision_id) 指向自身 Revision）`
- ARCH:152-154 ——
  ```text
  requirement.current_revision
    (project_id, id, current_revision_id)
      -> requirement_revision(project_id, requirement_id, id)
  ```
- ARCH:141-142 —— 反方向已有 `requirement_revision (project_id, requirement_id) -> requirement(project_id, id)`
- DEC:118 —— 「创建时同步建立 Revision 1」
- ARCH:196 —— 「可空复合外键使用 PostgreSQL **`MATCH SIMPLE`**」

因此两张表之间存在**双向 FK 环**：`requirement_revision → requirement`（父子）与 `requirement → requirement_revision`（current 指针）。注意 `requirement.id` 在这条 FK 里同时充当"我自己的主键"和"子表的 requirement_id"，所以这条 FK 顺带证明了 **current revision 必属于本需求本项目**，不只是"存在"。

### 2.2 写入顺序（结论）

`MATCH SIMPLE` 的语义是：复合外键**只要有任一引用列为 NULL，整条约束就不检查**。ARCH:196 正是用这一条来论证 Finding 的 nullable 外键不能证明父 Review 存在——同一条机制在这里被反过来利用：`(project_id, id, current_revision_id)` 中 `project_id`、`id` 恒为非空，唯一可空列是 `current_revision_id`，所以

- `current_revision_id IS NULL` → 约束**完全跳过**；
- `current_revision_id IS NOT NULL` → 三列全非空 → 约束**完整校验**。

由此，创建需求的**唯一可行顺序**是三步、**同一事务**：

```text
BEGIN;
  1) INSERT requirement (project_id, status='DRAFT', assignee_id=NULL, current_revision_id=NULL)
     -- current_revision_id IS NULL → MATCH SIMPLE 跳过 current_revision FK
  2) INSERT requirement_revision (project_id, requirement_id=<1 的 id>, seq=1, title/background/description, created_by, created_at)
     -- (project_id, requirement_id) -> requirement(project_id,id) 满足：父行已在本事务内可见
  2') [可选] INSERT acceptance_criterion ... (requirement_revision_id=<2 的 id>)
  3) UPDATE requirement SET current_revision_id = <2 的 id>
     -- 三列全非空 → 校验 requirement_revision(project_id, requirement_id, id) 存在且属于本需求本项目 → 通过
COMMIT;
```

### 2.3 对外键时序的要求

- 普通（非 DEFERRABLE）FK 在 PostgreSQL 中由 AFTER ROW 触发器实现，**在语句结束时检查**，并且**看得到同一事务中前面语句写入的未提交行**。步骤 2 的父行、步骤 3 的目标行都满足这一点。
- 因此三步顺序对**非延迟**约束是安全的：步骤 1 靠 NULL 绕开，步骤 2、3 各自的目标在检查时刻均已存在。
- 相反顺序（先插 revision 再插 requirement）**不可行**：`requirement_revision.requirement_id` 是 NOT NULL 的复合 FK 引用列，没有 NULL 逃逸口。

### 2.4 是否需要 DEFERRABLE

**结论：不需要，且文档没有授权使用它。**

- 全库 grep `docs/v2/**` 对 `deferr` **零命中**——DEFERRABLE 在权威文档中从未出现。
- 需要 `DEFERRABLE INITIALLY DEFERRED` 的前提是"真正的鸡生蛋"：两个方向的引用列**都不可为空**，无法拆成两步。这里 ARCH:89 明确把 `current_revision_id` 定义为**可空 + 回填**，正是为了不引入延迟约束而选的破环手段。
- 因此 design 里应写死：三张表的所有 FK 均为默认的 `NOT DEFERRABLE INITIALLY IMMEDIATE`，破环靠 NULL + 事务内回填 UPDATE。

**但必须同时记录这个选择的代价（这是文档没写、design 必须处理的）：**

1. **数据库无法证明"每个已提交的 requirement 都有 current revision"。** `MATCH SIMPLE` + 可空列意味着 `current_revision_id = NULL` 永远合法。文档只在 DEC:118 用业务语言写了"创建时同步建立 Revision 1"，没有给出任何 `NOT NULL` / `CHECK` / 触发器来强制。→ §9 **O-01**。
2. **不能通过"把列改成 NOT NULL + DEFERRABLE"来修**，那是超出 ARCH:89 文义的 schema 变更，按 ARCH:104 与 PLAN:116 需要先补并批准新的决策记录。
3. **删除方向没有出口。** 环上两条 FK 都没有声明 `ON DELETE` 行为（ARCH 全文只为 `pull_request.author_user_id` 指定了列级 `ON DELETE SET NULL`，ARCH:163-164）。硬删除一个 requirement 会被自身的 current 指针和子 revision 双向挡住。→ §9 **O-04**。

### 2.5 ORM 风险（D012 刻意留在批次 1 的那颗雷）

DEC:144 明确：「数据库约束的反馈回路必须保留……批次划分刻意把这一风险留在批次 1（第一次真正建业务表时）暴露」。DEC:71 进一步规定：若约束触发器/约束与 ORM 无法兼容，**必须先新增决策，不得静默降级为无测试的 Service 纪律**。

这条自引用复合 FK 是批次 1 内**最可能触发该反馈回路**的构造，具体风险点：

- JPA/Hibernate 的 `@ManyToOne` 默认按单列外键映射，`(project_id, id, current_revision_id)` 这种**引用列与本表 PK/其它 FK 列重叠**的三列复合 FK 很难用关联映射表达；常见做法是把 `project_id`、`current_revision_id` 映射为普通标量字段，FK 只在 Flyway DDL 里声明。
- 若把 current revision 映射成关联且 `optional=false` / `nullable=false`，Hibernate 会把该列放进 INSERT，直接破坏 §2.2 的三步顺序。映射必须允许 NULL 并依赖后续 UPDATE 刷出。
- Hibernate 的 flush 排序按实体类型分组，双向 FK 环下必须显式控制 flush 时机（persist requirement → flush → persist revision → set current → flush），否则语句顺序不受控。

以上属于"实现策略未被文档规定"，见 §9 **O-02**。**不要**在没有新决策的情况下把 FK 从 DDL 里拿掉换成 Service 校验（DEC:71 明令禁止）。

### 2.6 必须写的集成测试（由上述约束直接推导，非发明）

ARCH:233 已给出固定集成测试清单的模式（跨项目猜 id 等）。对本节至少应覆盖：

- A 项目用户猜 B 项目 requirement / revision / AC 的 id（ARCH:233、PRD:177）。
- 把 `current_revision_id` 指向**别的需求**的 revision → 被复合 FK 拒绝（这正是三列 FK 相对单列 FK 的增量价值）。
- 把 `current_revision_id` 指向**别的项目**的 revision → 被拒绝。
- 创建需求的三步在同一事务内成功；中途失败整体回滚（不留孤儿 revision，也不留 `current_revision_id IS NULL` 的已提交需求）。

---

## 3. 状态机：可测断言

### 3.1 原文

PRD:96-103：

```text
DRAFT → READY → IN_DEVELOPMENT → DONE
  └────────────────────────────→ CANCELED
```

> `DRAFT → READY` 由 LEADER 确认；`READY → IN_DEVELOPMENT` 与**首次指派**同事务完成，后续更换负责人不再改变状态；`→ DONE` 由 LEADER 确认全部关联工作完成。**AI、Webhook、PR、Review 一律不得推进这些状态。**（PRD:103）

DEC:118：「Requirement 持久状态仅为 `DRAFT/READY/IN_DEVELOPMENT/DONE/CANCELED`，AI、Webhook、PR 和 Review 不推进状态。」

PLAN:49 把这五个状态列为 Phase 3 交付物。

### 3.2 权限来源（PRD §3:47-63）

| 动作 | LEADER | DEVELOPER | REVIEWER | 行 |
|---|:--:|:--:|:--:|---|
| 创建/编辑需求与 AC | ✅ | ❌ | ❌ | PRD:51 |
| 运行需求质量检查 | ✅ | ❌ | ❌ | PRD:52 |
| 需求 DRAFT → READY、指派开发 | ✅ | ❌ | ❌ | PRD:53 |
| 生成当前需求的一次性 AI 实现建议 | ✅ | 仅被指派需求 | ❌ | PRD:54 |
| 取消需求 | ✅ | ❌ | ❌ | PRD:61 |

「每个项目**恰有一个 LEADER**」（PRD:45、DEC:47）；「跨项目一律不可见、不可操作」（PRD:63）。

### 3.3 可测断言

正向转换：

| ID | 转换 | 谁 | 前置条件 | 同事务副作用 | 来源 |
|---|---|---|---|---|---|
| S-01 | ∅ → `DRAFT` | LEADER | 项目内 | 同步建立 Revision 1 并回填 `current_revision_id`（§2.2） | PRD:51、DEC:118 |
| S-02 | `DRAFT` → `READY` | LEADER | `status = DRAFT` | **同事务冻结 Revision 1**（PRD:119） | PRD:53、PRD:103、PRD:119 |
| S-03 | `READY` → `IN_DEVELOPMENT` | LEADER | `status = READY` 且这是**首次指派**（`assignee_id` 由 NULL 变为非 NULL） | 与首次指派**同一事务** | PRD:103、DEC:118 |
| S-04 | 换人（状态不变） | LEADER | `status = IN_DEVELOPMENT`，`assignee_id` 已非 NULL | **仅改 `assignee_id`，`status` 不动** | PRD:103 |
| S-05 | `IN_DEVELOPMENT` → `DONE` | LEADER | LEADER 已确认**全部关联工作完成** | — | PRD:103、PRD:150(P9)、DEC:51 |
| S-06 | `?` → `CANCELED` | LEADER | **源状态集合未定义** → §9 **O-03** | — | PRD:61、PRD:99-101 |

否定断言（每条都必须有测试）：

| ID | 断言 | 来源 |
|---|---|---|
| S-N1 | **AI、Webhook、PR、Review 一律不得推进 requirement.status**——不存在任何由这四者触发的状态写入路径 | PRD:103、DEC:118、DEC:124 |
| S-N2 | 质量检查结果**不能自动置 READY**；「质量检查是建议，不是工作流状态」 | PRD:105 |
| S-N3 | 枚举中**不存在 `NEEDS_IMPROVEMENT`** | PRD:105、MATRIX:32 |
| S-N4 | 枚举中**不存在 `IN_REVIEW`**；评审进展是只读派生量 `review_activity`，**不落表** | PRD:106、DEC:124 |
| S-N5 | DEVELOPER / REVIEWER 不能创建或编辑需求与 AC | PRD:51 |
| S-N6 | DEVELOPER / REVIEWER 不能执行 `DRAFT → READY`、不能指派 | PRD:53 |
| S-N7 | DEVELOPER / REVIEWER 不能取消需求 | PRD:61 |
| S-N8 | 跨项目用户对本项目 requirement 既不可见也不可操作（猜 id 也不行） | PRD:63、PRD:177、ARCH:233 |
| S-N9 | `DONE` 不由多个 PR 的 Review 结果自动聚合得出，必须 LEADER 人工确认 | DEC:51、PRD:150 |
| S-N10 | 图中不存在的边（如 `READY → DRAFT`、`DONE → *`）默认不实现 | PRD:99-101（图为唯一来源）→ 反向确认见 §9 O-03/O-05 |

**状态机与派生量的正交性**是硬要求：`review_activity` 是「只读派生量……不落表」（PRD:106），UI 上「需求状态与派生的评审活动并列展示，不得合并」（PRD:117、PRD:175、ARCH:420）。

---

## 4. Revision 不可变语义（D011）

### 4.1 原文

- DEC:118 —— 「创建时同步建立 Revision 1；DRAFT 可原地编辑，READY 时冻结；之后每次修改由 LEADER 一次性发布新 Revision 并填写原因，AC 归属具体 Revision，`ac_key` 是跨版本稳定业务身份。」
- PRD:119 —— 「READY 后正文与 AC 锁定；修改由 LEADER 创建新的不可变 Revision 并填写变更原因，**旧 AC 永久保留**。DRAFT 阶段的 Revision 1 可原地编辑，`DRAFT → READY` 同事务冻结——"不可变"指**已发布的 Revision**。」
- ARCH:343 —— 「Requirement 进入 READY 后正文与 AC 锁定，修改须由 LEADER 创建新的不可变 Revision（D011）；旧 Review **永不失效、永不覆盖**。」
- ARCH:350 —— 「`ac_key` 是跨 Revision 稳定业务身份，**不得用数据库行 id 或显示顺序代替**。」
- ARCH:91 —— `sort_order`（**仅显示**）。

### 4.2 可测断言

| ID | 断言 | 来源 |
|---|---|---|
| R-01 | 创建需求时**同事务**建立 Revision 1；不存在"有 requirement 无 revision"的已提交状态 | DEC:118 |
| R-02 | `status = DRAFT` 时，Revision 1 的 title/background/description 与其 AC **可原地编辑**（UPDATE 同一行，不产生新 Revision） | PRD:119 |
| R-03 | `DRAFT → READY` **同事务**冻结 Revision 1：此后该行的正文与其 AC 集合不可再修改 | PRD:119 |
| R-04 | READY 之后的任何正文/AC 变更**只能**由 LEADER 发布**新 Revision**，并**必须**填写 `change_reason` | PRD:119、DEC:118 |
| R-05 | 新 Revision 一经发布即不可变（「"不可变"指已发布的 Revision」）——**系统中唯一可变的 Revision 是 `status=DRAFT` 下的 Revision 1** | PRD:119 |
| R-06 | 旧 Revision 与旧 AC **永久保留**，不删除、不覆盖 | PRD:119、ARCH:343 |
| R-07 | `ac_key` 跨 Revision 稳定；禁止用 `acceptance_criterion.id` 或 `sort_order` 承担业务身份 | ARCH:350、ARCH:91 |
| R-08 | `sort_order` 只影响展示顺序；改 `sort_order` 不改变任何 AC 的业务身份，也不构成"正文修改" | ARCH:91 → 但"改 sort_order 是否算修改"未定义，见 §9 O-08 |
| R-09 | Revision 变更**不自动重审**；对已关联 PR 派生 `REVIEW_REQUIRED`（Phase 6 行为，Phase 3 不实现） | PRD:119、DEC:120 |

### 4.3 `ac_key` 为什么必须现在就做对

`ac_key` 不是 Phase 3 的内部细节，它是 Phase 6 的输入：`finding_key` 中「`REQUIREMENT` 还必须加入 `requirement_id + ac_key`」（ARCH:350、MATRIX:107）。若 Phase 3 用行 id 或顺序号冒充 `ac_key`，Phase 6 的跨 Review 血缘与误报抑制（D009）会在需求发新版本后整体失效。**这是一条不可延后的正确性依赖。**

同时注意 DB 只约束 `(requirement_revision_id, ac_key)` unique（ARCH:91），即**每个 Revision 内唯一**；文档声称的"跨 Revision 稳定业务身份"在数据库层没有任何强制 → §9 **O-05**（`ac_key` 的生成规则、可见性与跨 Revision 复用规则未定义）。

---

## 5. 质量结果字段：本批次"只建列"与"要实现"的分界

### 5.1 事实

- 列在 `requirement_revision` 上：`quality_json / quality_version / quality_checked_at`（ARCH:90）。
- 归属 Revision，不归属 Requirement：DEC:120「需求质量结果归属 Revision」；PRD:119「需求质量检查结果归属具体 Revision」。
- 清空语义：DEC:120「**DRAFT 正文或 AC 修改时同事务清空**」；PRD:119「DRAFT 期间正文一改即失效」。
- 不建独立报告表：MATRIX:40「`RequirementQualityReport*` … REWRITE … `requirement.quality_json/version/checked_at` 快照字段｜不建独立报告表（ARCHITECTURE §2.1）；不可复用旧 schema」。
  - 注意 MATRIX:40 的目标写作 `requirement.*`，而 ARCH:90 把这三列放在 `requirement_revision`。**以 ARCH 为准**（ARCH:10 单一事实源；DEC:120 也说归属 Revision）。这是一处措辞不一致，见 §9 O-13。
- 质量检查**本身**在 Phase 6：PLAN:69「Phase 6：Requirement Quality + Review Engine —— 规则 + 一次结构化 AI Quality」。
- 权限：运行需求质量检查 = LEADER（PRD:52）。
- 质量检查**不是**工作流状态、**不能**自动置 READY（PRD:105）。

### 5.2 分界（明确写给 design）

| 项 | 批次 1 / Phase 3 | 后续 |
|---|---|---|
| `quality_json / quality_version / quality_checked_at` 三列的 DDL | ✅ **本批次建列** | — |
| DRAFT 期正文或 AC 修改时**同事务清空**这三列 | ✅ **本批次实现**（写入路径的不变式） | — |
| 新 Revision 发布时三列的初值 | ✅ 建列即隐含为空 → 但"发布新 Revision 是否必然为 NULL"未明写，见 §9 O-09 | — |
| 确定性规则检查器（`RequirementRuleChecker` 的 REWRITE） | ❌ | Phase 6（PLAN:69、MATRIX:36） |
| 一次结构化 AI 质量分析 + 解析器 | ❌ | Phase 6（PLAN:69、MATRIX:37-38） |
| 运行质量检查的 API / 按钮 | ❌ | Phase 6 |

**实现提示（非文档规定）**：Phase 3 内没有任何生产者会写入 `quality_json`，因此 R-清空 语义只能靠测试夹具直接 seed 一个非空 `quality_json` 后再触发 DRAFT 编辑来验证。若不这么测，这条不变式会在 Phase 6 才第一次被执行，届时"忘记清空"是典型回归。

---

## 6. 本批次明确不做的（边界）

| 项 | 归属 | 依据 | Phase 3 应有的表现 |
|---|---|---|---|
| `review_activity` 派生量（`REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/APPROVED/MIXED/NO_PR`） | Phase 6 | PLAN:73、PRD:106-117、DEC:122 | Phase 3 无 `pull_request`/`review` 表，**任何需求必然是 `NO_PR`**（PRD:117）。是否在 API/UI 里体现见 §9 O-10 |
| Requirement 附件关系（`requirement_attachment` + `knowledge_document.source_requirement_id`） | Phase 4 | PLAN:56、DEC:56-58、PRD:144(P3) | 不建表、不建 FK、不做上传 |
| 一次性 AI 实现建议（Implementation Guidance） | Phase 4 | PLAN:57、ARCH:419 | 详情页不出现该功能 |
| Requirement Quality（规则 + AI） | Phase 6 | PLAN:69 | 只建列 + 清空语义（§5） |
| PR ↔ 需求关联、`REQ-<n>` 解析 | Phase 5 | PLAN:60-65、PRD:142(P1)、ARCH:49 | 不做解析；但 `REQ-<n>` 的 `<n>` 来源会倒逼 `requirement` 的列设计，见 §9 **O-11** |
| Review Engine / Finding | Phase 6-7 | PLAN:67-83 | 不涉及 |
| 需求状态的 SCM/Review 反写 | **永不做** | PRD:103、DEC:118 | 断言 S-N1 |

模块依赖侧的对应约束：`requirement ← common, project, knowledge, ai`（ARCH:60）。Phase 3 时 `knowledge` 与 `ai` **尚不存在**，因此 Phase 3 的 `requirement` 包**只能依赖 `common` 与 `project`**。ARCH:48 描述的 `requirement` 模块职责（需求、AC、指派、质量检查、附件关系、一次性实现建议）是**模块的最终形态**，不是 Phase 3 的交付清单。

PLAN:11 的禁止项同样适用：禁止 Agent、Patch、MQ/Outbox、第二 Review Engine、第二 AI runtime、本地 Git/clone、代码向量库和运行时 DDL。

新增表的门槛：ARCH:104「新增表必须有已发生的业务事实 + 新决策记录证明现有模型无法表达」；PLAN:116「任何新增表、模块、一级页面、运行时依赖或改变已接受决策的行为都必须先补充并批准新的决策记录」。

---

## 7. 前端范围

### 7.1 路由（ARCH §6:405-415）

一级导航只有三个：**项目**、**研发需求**、**代码审查**（ARCH:405）。全量路由表：

```text
/projects
/projects/:id/members
/projects/:id/settings       # SCM + Knowledge
/requirements                # ← Phase 3
/requirements/:id            # ← Phase 3
/reviews
/reviews/:id
```

与需求相关的只有 `/requirements`（列表）与 `/requirements/:id`（详情）。ARCH:417 明令 Workbench、Knowledge、Repository、Metrics、Agent、Patch、AI Logs **均不做一级页面**；`.trellis/spec/frontend/design-contract.md:58`「No fourth top-level menu」。

### 7.2 Phase 3 界面交付与退出条件

PLAN:51：「界面：**需求列表、详情和版本历史**；**无 AI/SCM 也能完成创建、确认和指派**。」

- "版本历史"在 ARCH §6 的路由表里**没有独立路由** → 应落在 `/requirements/:id` 内部（section 或子路由），见 §9 O-12。
- "无 AI/SCM 也能完成创建、确认和指派" = Phase 3 的功能闭环退出条件：`创建(DRAFT) → 确认(READY) → 指派(IN_DEVELOPMENT)` 三步在**不接任何 AI Gateway、不接任何 SCM**的前提下可完整走通。这也验证了 §6 的依赖边界。

### 7.3 正交呈现（硬规则）

- PRD:117 —— 「需求状态与评审活动**并列展示，不得合并**。」
- PRD:175（Phase 7 退出条件）—— 「需求状态与派生的评审活动在页面上分开呈现，互不污染。」
- ARCH:420 —— 「AI 置信度、Finding 人工状态、Review Decision 在 UI 上必须明确分开呈现；需求状态与派生的评审活动（D011）同样是两个正交维度，**不得合并为一个标签**。」
- `.trellis/spec/frontend/design-contract.md:28-30` —— 「Finding lifecycle, AI confidence, Requirement status, Review Decision, and review activity remain separate labels and containers; never merge them into one risk badge or composite score.」

Phase 3 虽然还没有 review activity 的数据源，**页面结构必须从一开始就为两个独立标签留位**，不要先做成一个合并 badge 再在 Phase 6 拆——那正是上述三处规则要防的漂移。

### 7.4 视觉/交互契约（`.trellis/spec/frontend/`）

ARCH:422：视觉与动效契约定义在 `.trellis/spec/frontend/`，本文不重复；**页面按纵向切片随各 Phase 交付，不集中堆到最后一个 Phase**。

`design-contract.md` 关键约束（`.trellis/spec/frontend/design-contract.md`）：

- 方向 B「精密审查台」+ **light** 配色为契约（:5、:11-22）。
- `frontend/src/styles/tokens.css` 是主题色/字体/间距/圆角/阴影/动效值的**唯一来源**（:35-37）。
- 语义化 `header/nav/main`、可见 skip link、原生 link/button、键盘可见焦点（:49-50）。
- **状态永不只靠颜色**，语义色必须配可见文字或形状（:52）——直接适用于需求状态徽标。
- 新增颜色/间距/圆角/阴影/断点/动效值必须先命名 token 并更新契约（:53-55）。
- 在 1440 / 768 / 390 CSS px 检查长标题/长路径、空/错/禁用/焦点态、reduced motion、控制台与网络错误（:56-57）。

### 7.5 API 形态

ARCH:245：`/api/projects/{projectId}/...`，项目内资源一律带 projectId 段 → 需求 API 形如 `/api/projects/{projectId}/requirements[/{id}]`。
ARCH:246：错误响应统一 `{code, message, traceId}`。
ARCH:133 / DEC:71：约束冲突统一映射 **409/422**。

**冲突点**：前端路由 `/requirements` 与 `/requirements/:id` **不含 projectId 段**，而 API 必须带 projectId。当前项目如何选择与承载（query param / store / localStorage）在 ARCH §6 中未定义 → §9 **O-16**。

---

## 8. Legacy 取舍（MATRIX §Requirement，:28-42 逐条）

分类口径（MATRIX:7）：`KEEP` = 源代码可低成本迁移并补测试；`REWRITE` = 保留业务但按 V2 边界重新实现；`REFERENCE` = 只继承算法/安全策略/Prompt/测试思想；`DROP` = 不进入 V2。
纪律（MATRIX:9、PLAN:9）：绿地项目会更换根包、数据表和依赖方向，**KEEP 也必须先迁特征/安全测试**，再迁少量边界清楚的纯代码；旧 Flyway 历史、周边架构和运行依赖一律不随 KEEP 进入 V2。Legacy 只读，不整包复制。

| # | Legacy 资产 | 判断 | V2 去向 | 理由与风险（原文） | 行 |
|---|---|---|---|---|---|
| L1 | `requirement/RequirementStatus` | **REWRITE** | `requirement.RequirementStatus` | 「V2 **删除 NEEDS_IMPROVEMENT**，质量建议不再驱动工作流状态」 | MATRIX:32 |
| L2 | `RequirementEntity` | REWRITE | `requirement.Requirement` | 「新增明确 project/assignee/review 状态与附件关系」 | MATRIX:33 |
| L3 | `AcceptanceCriterionEntity` | REWRITE | `requirement.AcceptanceCriterion` | 「模型简单但需新 FK/唯一约束/**版本语义**」 | MATRIX:34 |
| L4 | `RequirementService` | REWRITE | `requirement.RequirementService` | 「保留用例，**拆开命令、查询与外部 AI 调用**」 | MATRIX:35 |
| L5 | `RequirementRuleChecker` | REWRITE | `requirement.RequirementQualityService` | 「保留规则与测试；旧实体/DTO 和中文启发式需版本化重建」——**Phase 6** | MATRIX:36 |
| L6 | `RequirementCheckParser` | REWRITE | `requirement.RequirementQualityParser` | 「保留整体拒绝与全量补齐测试，新输出 Schema 不同」——**Phase 6** | MATRIX:37 |
| L7 | `RequirementCheckService` | REWRITE | `requirement.RequirementQualityService` | 「核心业务保留，**外部调用移出长事务**；与规则检查同一编排入口」——**Phase 6** | MATRIX:38 |
| L8 | `OpenAiCompatibleRequirementCheckClient` | **DROP** | 统一 `ai.AiGateway` | 「**重复客户端且反向依赖 Agent Prompt**」 | MATRIX:39 |
| L9 | `RequirementQualityReport*` | REWRITE | `quality_json/version/checked_at` 快照字段 | 「**不建独立报告表**（ARCHITECTURE §2.1）；不可复用旧 schema」 | MATRIX:40 |
| L10 | `RequirementLinkEntity/Service` | **REFERENCE** | Requirement↔PR/代码引用显式字段 | 「幂等/提取策略可参考，**字符串多态模型不迁**」 | MATRIX:41 |
| L11 | 需求上传附件逻辑 | REWRITE | `RequirementAttachment` + `KnowledgeDocument` | 「一次上传、一个文档事实源，**禁止双解析/双索引**」——**Phase 4** | MATRIX:42 |

**批次 1 实际会碰到的只有 L1–L4 与 L9（建列部分）**；L5–L8 属 Phase 6，L11 属 Phase 4，L10 属 Phase 5 的显式字段设计。

三条被点名的重点：

1. **L1 `RequirementStatus` → REWRITE，V2 删除 `NEEDS_IMPROVEMENT`**（MATRIX:32）。与 PRD:105「质量检查是建议，不是工作流状态，也不能自动置 READY」互相印证。旧枚举值不得以任何形式（含 DB CHECK 里的历史值、前端映射表）残留。
2. **L10 `RequirementLinkEntity/Service` → REFERENCE**（MATRIX:41）。旧的"Branch/Commit/PR 通用字符串多态链接"模型**不迁**；V2 用显式字段（Phase 5 的 `pull_request.requirement_id` + `pull_request_requirement_event`，ARCH:96-97）。只继承幂等与提取策略的思路。这条对 Phase 3 的意义是：**不要在 `requirement` 上预留任何通用 link 表或多态引用列**。
3. **L8 `OpenAiCompatibleRequirementCheckClient` → DROP**（MATRIX:39）。理由是重复客户端 + 反向依赖 Agent Prompt。V2 唯一 AI 技术入口是 `AiGateway.chat/embed`（ARCH:365-366），且 `ai` 不知道 Requirement 等业务类型（ARCH:370）。Phase 3 完全不碰 AI，本条的作用是**封死"顺手写个需求检查 HTTP 客户端"的路**。

同时注意 MATRIX 顶部（:5）：**Legacy Assistant 代码不迁**；MVP 的一次性 Implementation Guidance 在 `requirement` 内重新实现（Phase 4）。

---

## 9. 开放问题（文档未定义，实现必须回答）

按"必须在写 Flyway migration 之前回答"排序。**这些都不由本文拍板。**

### 阻塞 schema（migration 落地前必须定）

| # | 问题 | 为什么阻塞 | 相关行 |
|---|---|---|---|
| **O-11** | **`REQ-<n>` 的 `<n>` 是什么？** 是 `requirement.id`（全局 BIGINT identity）还是项目内展示编号？若是后者，`requirement` 需要一个 ARCH:89 里**不存在**的列（如 `number`/`project_seq`）及其 `(project_id, number)` 唯一键。 | Phase 5 才解析 `REQ-<n>`，但列必须在 Phase 3 的建表里存在；补列意味着改 ARCH §2.1 的 16 表定义（ARCH:104 门槛）。 | PRD:142、PRD:170、DEC:76、ARCH:49、ARCH:89 |
| **O-01** | `current_revision_id` 在事务提交后是否允许为 NULL？数据库层是否需要（且是否被授权）加 `NOT NULL` 或 CHECK/触发器来保证"每个已提交需求都有 current revision"？ | 决定 §2 的破环方案是否留下无法证明的不变式；改成 NOT NULL 会连带需要 DEFERRABLE（文档零提及）。 | ARCH:89、ARCH:196、DEC:118 |
| **O-04** | requirement / revision / AC 的**删除语义**：是否允许硬删除？各 FK 的 `ON DELETE` 行为是什么？环上两条 FK 如何解开？ | ARCH 只为 `pull_request.author_user_id` 指定过 `ON DELETE SET NULL`（:163-164），三张表一字未提。DDL 必须写点什么。 | ARCH:152-154、ARCH:141-142、ARCH:163-164 |
| **O-05** | `ac_key` 的**生成规则、格式、是否用户可见、是否可编辑**；以及"跨 Revision 稳定"的具体含义——新 Revision 里同一 `ac_key` 是否必须指同一条 AC？`ac_key` 是否可被删除后在更晚的 Revision 复用？ | DB 只有 `(requirement_revision_id, ac_key)` unique，跨 Revision 语义无任何强制；Phase 6 的 `finding_key` 直接依赖它。 | ARCH:91、ARCH:350、MATRIX:107 |
| **O-06** | `change_reason` 是否 NOT NULL？Revision 1 无变更原因，Revision ≥2 必填——是否需要 CHECK（如 `seq = 1 OR change_reason IS NOT NULL`）？ | 直接影响 DDL。 | ARCH:90、PRD:119、DEC:118 |
| **O-14** | 三张表各自的时间/审计列清单：`requirement` 是否有 `created_at/updated_at/created_by`？`acceptance_criterion` 是否有时间列？ | ARCH:242 只给命名约定，ARCH:89/:91 的列清单里没列。 | ARCH:89、ARCH:91、ARCH:242 |
| **O-15** | `requirement_revision.created_by` 指向 `user_account` 还是 `project_member`？ | ARCH:219 给了判定原则（审计 actor → `user_account`；活权限 → `project_member`），但没为该列定性；决定它是全局 FK 还是复合 FK。 | ARCH:90、ARCH:219 |
| **O-17** | 各文本列的类型与长度上限（`title`/`background`/`description`/`text`/`ac_key`），以及哪些 NOT NULL。 | DDL 必写。文档一字未提。 | ARCH:90、ARCH:91 |

### 阻塞状态机实现

| # | 问题 | 相关行 |
|---|---|---|
| **O-03** | **`→ CANCELED` 的源状态集合是什么？** PRD:99-101 的 ASCII 图中分支从 `DRAFT` 下方引出，但横跨整行；究竟是"仅 DRAFT 可取消"还是"DRAFT/READY/IN_DEVELOPMENT 均可取消"？`DONE` 之后能否取消？`CANCELED` 是否终态、可否恢复？ | PRD:99-101、PRD:61 |
| **O-07** | `DONE` 是否为终态？是否存在任何重开路径？`DONE` 只能从 `IN_DEVELOPMENT` 进入，还是也能从 `READY` 直接进入（工作证明不必要时）？ | PRD:99-103 |
| **O-18** | `READY → DRAFT`（撤销确认）是否允许？图中无此边，但也无明文禁止句。若禁止，Revision 1 一旦冻结即永久冻结。 | PRD:99-101、PRD:119 |
| **O-19** | **首次指派是否要求 `status = READY`？** 在 `DRAFT` 状态下指派应当被拒绝，还是允许但保持 `DRAFT`？ | PRD:103 |
| **O-20** | 指派是否可**清空**（`assignee_id` 置回 NULL）？若可以，`IN_DEVELOPMENT` 是否回退？（PRD:103 只说"更换负责人不改状态"，没说清空。） | PRD:103 |
| **O-21** | 可被指派的成员角色范围：仅 `DEVELOPER`，还是任意项目成员（含 LEADER/REVIEWER）？ | PRD:53、PRD:54 |
| **O-22** | `DRAFT → READY` 是否有内容前置条件（例如至少 1 条 AC、title 非空）？已知**不能**用质量检查作闸门（PRD:105），但其它前置条件未定义。 | PRD:105、PRD:119 |
| **O-23** | **需求状态变更的审计落在哪里？** P7 要求「人工决策全部留痕（actor、时间、备注），可追溯」（PRD:148），但 16 表中**没有 `requirement_event`**，且 ARCH:103 明确禁止通用 `audit_event`。`requirement_revision` 的 `created_by/change_reason/created_at` 只覆盖"发布新 Revision"，覆盖不到 READY 确认、指派、取消、DONE 确认。新增表须先有新决策（ARCH:104、PLAN:116）。 | PRD:148、ARCH:90、ARCH:103-104、PLAN:116 |

### 阻塞 Revision / 质量语义

| # | 问题 | 相关行 |
|---|---|---|
| **O-08** | DRAFT 期间**哪些操作算"正文或 AC 修改"**从而触发质量结果清空？只改 `sort_order` 算不算？只改 AC 文本算不算（应算）？ | DEC:120、PRD:119、ARCH:91 |
| **O-09** | 发布新 Revision 时，**AC 是否从上一 Revision 自动复制**（保留 `ac_key`），还是由 LEADER 重新录入？某条 AC 在新 Revision 中"消失"是否合法，其 `ac_key` 语义如何处理？新 Revision 的 `quality_*` 三列是否必然为 NULL？ | DEC:118、PRD:119、ARCH:350 |
| **O-24** | `current_revision_id` 是否**必须**恒等于 `max(seq)` 的 Revision？是否允许 LEADER 把它指回旧版本？是否需要约束/触发器保证？ | ARCH:89、DEC:118 |
| **O-25** | 处于 `DONE` 或 `CANCELED` 的需求，能否继续发布新 Revision？（PRD:119 只说"READY 后"。） | PRD:119 |
| **O-13** | MATRIX:40 把质量快照字段写作 `requirement.quality_json/version/checked_at`，ARCH:90 与 DEC:120 则明确在 `requirement_revision` 上。已按 ARCH 为准处理，但**MATRIX 文本需要修正**（属文档一致性问题，非实现问题）。 | MATRIX:40、ARCH:90、DEC:120 |

### 阻塞 API / 前端

| # | 问题 | 相关行 |
|---|---|---|
| **O-16** | 前端路由 `/requirements` 不含 projectId，而 API 必须是 `/api/projects/{projectId}/requirements`。**当前项目如何选择、承载与持久化**（query param / 全局 store / localStorage / 路由改造）？跨项目切换时的行为？ | ARCH:411-412、ARCH:245、PRD:63 |
| **O-10** | Phase 3 的需求 API/UI 是否**已经暴露 `review_activity` 字段**（恒为 `NO_PR`），还是等 Phase 6 再加？影响 API 契约稳定性与前端"两个正交标签"的落位时机。 | PRD:117、ARCH:420、PLAN:73 |
| **O-12** | "版本历史"是 `/requirements/:id` 内的 section，还是新增子路由（如 `/requirements/:id/revisions`）？ARCH:407-415 的路由表未列，但同表已存在 `/projects/:id/members` 这类子路由。 | PLAN:51、ARCH:407-417 |

### 阻塞实现策略（非文档缺陷，但必须在 design 里定）

| # | 问题 | 相关行 |
|---|---|---|
| **O-02** | 三列复合 FK（引用列与本表 PK 重叠）在 JPA/Hibernate 下的**映射策略**：标量字段 + DDL 声明 FK，还是 `@JoinColumns` 关联映射？双向 FK 环下的 flush 顺序如何显式控制？若发现约束与 ORM 不兼容，按 DEC:71 **必须先新增决策**，不得静默降级为 Service 校验。 | ARCH:152-154、DEC:71、DEC:144 |
| **O-26** | Phase 1 遗留前置条件与本批次的耦合：ArchUnit 的**子包深度**与 **Repository 识别规则**需在引入业务类的同时补齐；`common.web` 错误契约落地时需填写 `.trellis/spec/backend/error-handling.md` 与 `logging-guidelines.md`（当前为空）。 | PLAN:122、`.trellis/tasks/archive/2026-08/08-20-phase-1-foundation/result.md:202-205,228-230` |

---

## 10. Caveats / Not Found

- **`docs/v2/adr/` 是空目录**，11 份 ADR 已收敛进 `DECISIONS.md`（DEC:5）。没有额外的 Requirement 相关 ADR 可查。
- **`DEFERRABLE` 在 `docs/v2/**` 中零命中**（已 grep 确认）。§2.4 的"不需要 DEFERRABLE"是基于 `MATCH SIMPLE` + 可空回填的推导，不是文档原文的显式声明。
- **`requirement` 与 `requirement_revision` 的完整列清单在文档中并不完整**（无时间列、无长度、无 NOT NULL 标注）。§1 表格中标"来源 ARCH:89/90/91"的列是文档写明的；其余一律进 §9。
- 当前 `backend/src/main/resources/db/migration/` **只有 `V1__foundation.sql`**，内容仅 `CREATE EXTENSION IF NOT EXISTS vector;`（Phase 1 底座）。业务表尚未建任何一张，`project`、`project_member`、`user_account` 也不存在——**Phase 3 的 `requirement.assignee` 复合 FK 依赖 Phase 2 先落 `project_member(project_id, user_id)` 唯一键**。
- PLAN:27 说明：「只建立底座所需最小 schema；业务表随对应纵向 Phase 增加，**首个可发布版本前再 squash 为干净初始化迁移**」——所以本批次应新增 `V2__...`/`V3__...` 而不是改写 `V1`。具体 migration 编号/切分粒度文档未规定。
- 本文**没有**核对 `frontend/src/` 现有代码与路由实现；`.trellis/spec/frontend/` 只读取了 `design-contract.md` 全文与其它 spec 的 grep 命中行。若 design 需要现有前端路由骨架的确切形态，需另做一次内部检索。
- PRD:117 的多 PR 聚合表述存在轻微冗余（"全部子状态相同就返回该状态" 已蕴含 "全部 `APPROVED` 才返回 `APPROVED`"）。不影响 Phase 3（恒为 `NO_PR`），记录备查。
