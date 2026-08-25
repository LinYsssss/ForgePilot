# Removal semantics for knowledge documents, project members, and canceled requirements

来源：`docs/v2/TEST-ISSUES.md` 的 T-005、T-006、T-007。

## Goal

为知识文档、项目成员和已作废需求补齐删除/移除能力。这三项的真正难点不是缺一个 `DELETE` 端点，而是**每一项都撞在不同语义的外键上**：有的引用是活权限（应当随移除失效），有的是既成事实与审计（永不可销毁）。本任务要求先定清语义再落实现，不允许用一种删除方式套三种资源。

## Background and confirmed facts

### 现有端点

- `KnowledgeController` 只有 `GET`（:34）、`POST`（:39）、`POST /{documentId}/promote`（:48），**无 DELETE**，无批量上传。
- `ProjectMemberController` 有 `GET`、`GET /candidates`、`POST /batch`、`PATCH /{userId}/roles`、`POST /leader-transfer`，**无 DELETE**。
- `RequirementController` 有 15 个端点覆盖需求、修订、状态、指派、附件、Guidance、质量检查，**无 DELETE**。

### 外键引用图（本任务的核心约束）

`knowledge_document` 被两张表引用：

| 引用方 | 位置 | 性质 |
|---|---|---|
| `knowledge_chunk (project_id, document_id)` | V4:87 | 派生数据，随文档消亡 |
| `requirement_attachment (project_id, document_id, source_requirement_id)` | V4:64 | 复合外键；`RequirementAttachmentService` 已真实写入 |

`project_member` 被五处引用，**语义互相冲突**：

| 引用方 | 位置 | 性质 |
|---|---|---|
| `requirement` 指派 | V3:22 | 活权限 |
| `finding.assignee_id` | V6:152 | 活权限——V6 注释明写「leaving the project must revoke a live permission」 |
| `pull_request.author_user_id` | V5:80 | **历史事实**，不应因成员离开而消失 |
| `project_member_role` | V8:17 | 成员属性 |
| 项目 SCM 绑定 | V8:85 | 成员属性，且绑定历史不得被覆盖 |

`requirement` 被六处引用：

| 引用方 | 位置 | 性质 |
|---|---|---|
| `requirement_revision` | V3:40 | 不可变修订 |
| `knowledge_document.source_requirement_id` | V4:23 | 附件投影 |
| `requirement_attachment.requirement_id` | V4:59 | 附件事实源 |
| `ai_call_log.requirement_id` | V4:129 | **调用审计**，物理删除即销毁审计 |
| `pull_request.requirement_id` | V5:75 | 关联事实 |
| `pull_request_requirement_event.from/to_requirement_id` | V5:100/102 | **既成事实**，V5 注释明确其指向全局账户以免「erase an accomplished fact」 |

### 既有决策与边界

- 成员移除是**有意延期项，不是遗漏**：`08-23-member-directory-multi-role-scm-identities/prd.md:105` 明确把「成员移除、离职归档与重新激活」列入 Out of Scope，并保持「只添加、不删除成员」边界。本任务是接续该延期项。
- 每个项目恰有一个 `LEADER`（D004）；`LEADER` 只能通过独立的负责人转移事务变更，不经普通角色编辑或批量路径。
- 需求状态转换（含 `CANCELED`）当前**不单独留痕**，是 D013.3 明确接受的 MVP 缺口。若删除策略依赖「谁何时作废了它」，这条缺口会成为前置问题。
- 向量索引已由 **D019 决策为非目标**，因此台账里「索引策略另议」实际待定的**不是**建不建索引，而只是删除文档时 `knowledge_chunk` 与 embedding 的级联方式。
- 数据模型当前为 19 张表 / 8 个迁移；历史迁移不可修改，新增只能追加。

## Requirements

### R1 知识文档批量上传与删除（T-005）

- 支持一次选择多个知识文件上传，逐个文件报告成功/失败，且单个失败不使整批不可用。
- 提供知识文档删除能力，并明确 `knowledge_chunk` 与 embedding 的级联方式。
- 删除必须处理 `requirement_attachment` 对该文档的复合外键引用：要么拒绝删除仍被引用为需求附件的文档，要么同时撤销该附件关系，二者必须择一并写入决策，不得留下悬空引用或依赖数据库报错兜底。
- 删除后该文档的内容不得再出现在任何检索路径（Guidance、Review 的附件检索）里。
- 权限与项目隔离沿用现有规则；跨项目删除必须被拒绝。

### R2 项目成员移除（T-006）

- 允许把成员从项目中移除，并撤销其一切活权限：角色集合、需求指派能力、Finding 指派能力、项目 SCM 绑定。
- **不得**破坏历史事实：`pull_request` 的不可变作者快照（`author_external_user_id`、`author_username`）、`pull_request_requirement_event`、Finding 血缘与审计记录在移除后必须仍然可读。`author_user_id` 是可重算映射而非事实本身，按库结构预设的列级 `ON DELETE SET NULL` 置空——详见「已决策」第 2 条，该条取代本行原先关于 `author_user_id` 的表述。
- 移除时对已指派给该成员的需求与 Finding 必须有确定处理（见待评审决策 2），且该处理必须留痕。
- 唯一 `LEADER` 不可被移除；移除 `LEADER` 只能先经负责人转移。成员不能移除自己以外的越权对象，具体授权见 R4。
- 移除后该用户的平台账户与其自有 SCM 身份不受影响（身份归用户本人，非项目所有）。

### R3 已作废需求删除（T-007）

- 允许删除处于作废状态的需求，并明确是否要求「无关联记录」作为前置条件。
- **不得**物理销毁 `ai_call_log` 或 `pull_request_requirement_event` 所承载的审计与既成事实；若采用软删除，需求必须从产品列表消失但审计仍可追溯。
- 只有作废状态可进入删除路径；`DRAFT`/`READY`/进行中/`DONE` 均不可删除。
- 已被 PR 关联或已产生 Finding 的需求，其删除条件必须显式定义，不得留给实现临场决定。

### R4 授权与隔离

- 三类删除的授权主体必须逐项写明（预期为项目 `LEADER`），并在后端强制，不依赖前端隐藏入口。
- 所有查询与写入保持 `project_id` 隔离；跨项目删除、跨项目枚举均不可达。
- 删除类操作必须是幂等可判定的：重复删除返回明确结果而非产生歧义状态。

### R5 留痕

- 三类删除各自留下可追溯记录：谁、何时、对什么、以及连带撤销了什么。
- 留痕不得写入被删除对象自身（否则随删除一起消失）。

### R6 交付约束

- 若需要新表或新列，只能追加新的 Flyway 迁移，不修改 V1–V8。
- 不新增顶层包、一级导航、AI 流程、运行时依赖或第二 Review 流程。
- 后端 `./mvnw -B -ntp verify` 全绿零 skip；前端 lint/typecheck/test/build 全绿零 skip。
- 空库 Compose 启动与迁移升级路径均需验证。
- PRD/ARCHITECTURE/API/DECISIONS 同步更新；正式评测冻结、语料、holdout 台账与原始输出不得触碰或重跑。

## Acceptance Criteria

- [x] AC1 可一次上传多个知识文件，逐文件报告结果，单个失败不阻断整批。
- [x] AC2 知识文档可删除；`knowledge_chunk` 与 embedding 按既定级联处理，无悬空引用。
- [x] AC3 被引用为需求附件的知识文档，其删除行为与 R1 所定策略一致，且行为可预期而非数据库报错。
- [x] AC4 已删除文档的内容不再出现在 Guidance 与 Review 的任何检索结果中。
- [x] AC5 成员可被移除，且移除后其角色、需求指派能力、Finding 指派能力与项目 SCM 绑定全部失效。
- [x] AC6 移除成员后，`pull_request` 的不可变作者快照（`author_external_user_id`、`author_username`）、`pull_request_requirement_event`、Finding 血缘与审计记录仍完整可读；`author_user_id` 按预设置空不算破坏事实。
- [x] AC7 已指派给被移除成员的需求与 Finding 按既定策略处理，并留下可追溯记录。
- [x] AC8 唯一 `LEADER` 无法被移除；必须先完成负责人转移。
- [x] AC9 被移除成员的平台账户与自有 SCM 身份不受影响。
- [x] AC10 只有作废需求可进入删除路径，其他状态一律拒绝。
- [x] AC11 删除需求后 `ai_call_log` 与 `pull_request_requirement_event` 的记录未被销毁，审计仍可追溯。
- [x] AC12 三类删除的授权在后端强制；非授权主体经直接调用接口亦不可达。
- [x] AC13 跨项目删除与跨项目枚举被拒绝；数据库约束层面同样拒绝跨项目写入。
- [x] AC14 重复删除返回明确结果，不产生歧义状态。
- [x] AC15 三类删除均有留痕，且留痕不随被删除对象消失。
- [x] AC16 新增迁移仅为追加，V1–V8 未被修改；空库启动与升级路径均通过。
- [x] AC17 后端 verify 与前端 lint/typecheck/test/build 全绿零 skip。
- [ ] AC18 真实浏览器完成「批量上传知识 → 删除知识 → 移除成员 → 删除作废需求」人工闭环，且期间既有审计可读。（步骤已写入 `FULL-CHAIN-UI-TEST.md` §7 与 `MANUAL-ACCEPTANCE.md` §2；**真实浏览器执行待人工**，见 `result.md`）
- [x] AC19 相关 v2 文档同步更新；正式评测资产未被触碰。

## 已决策（2026-08-24 方案评审）

### 1. 三类资源三种删除策略，且这个不一致是结论而非妥协

| 资源 | 策略 | 决定它的外键事实 |
|---|---|---|
| 知识文档 | **硬删** | `knowledge_chunk` 是派生数据，随文档消亡；唯一真实引用是 `requirement_attachment` |
| 项目成员 | **硬删 `project_member` 行** | 见第 2 条：库结构本来就是为这条路径设计的 |
| 已作废需求 | **软删** | `ai_call_log` 与 `pull_request_requirement_event` 承载审计与既成事实，物理销毁即销毁审计 |

PRD 的 Goal 已经写明「不允许用一种删除方式套三种资源」。因此三种策略不是不一致，而是三组外键语义各自的正确答案；统一成一种才是错的。

### 2. 成员硬删是库结构预先设计好的路径——但 R2 的措辞需要纠正

**R2 原文「`pull_request.author_user_id` 指向的既有 PR 作者关系……在移除后必须仍然可读」不准确，本节取代它。**

`ARCHITECTURE.md:106` 对 `pull_request` 的定义分得很清楚：

- `author_external_user_id`、`author_username` —— **不可变作者快照**，两列均 `NOT NULL`；
- `author_user_id` —— **可重算映射**，复合 FK 指向 `project_member`，列级 `ON DELETE SET NULL`（D010）。

这是**全库唯一一条 `ON DELETE`**（`database-guidelines.md` 明确记录了这一点），也就是说：成员被硬删时把本地映射置空，正是当初为这个场景专门设计的行为。作为事实的作者身份由那两列不可变快照承载，移除后完整可读；`author_user_id` 只是「这个 SCM 账号当前对应哪位项目成员」的映射，成员没了，映射本就该消失。D020 也说它是按活动绑定**重算**出来的。

所以真正要保住的是快照两列，不是映射列。R2 的验收点相应改为：移除后 `author_external_user_id` / `author_username` / `pull_request_requirement_event` / Finding 血缘与审计记录仍完整可读。

### 3. 移除成员时的连带处理：置空活权限指派并留痕

`finding.assignee_id` 与 `requirement.assignee_id` 都指向 `project_member` 且**都没有 `ON DELETE` 子句**——硬删会被外键直接拒绝。因此移除必须在同一事务里显式处理：

```
1. 校验：目标不是唯一 LEADER（是则拒绝，要求先做负责人转移）
2. requirement.assignee_id  -> NULL（该成员被指派的全部需求）
3. finding.assignee_id      -> NULL（该成员认领的全部 Finding）
4. delete project_member_scm_binding（项目作用域的成员属性）
5. delete project_member_role
6. delete project_member     -> 触发 pull_request.author_user_id 的 ON DELETE SET NULL
7. 写入移除留痕（见第 4 条）
```

选「置空 + 留痕」而不是「拒绝移除」或「强制先转派」：任何认领过 Finding 的成员都会让「拒绝」在实践中等于无法移除；「强制先转派」要凭空造一套转派工作流。置空的语义也是对的——人走了，这条 Finding 就该回到无人认领。

用户自有的 `scm_identity` **不受影响**（身份归用户本人，非项目所有），只有项目作用域的绑定随成员关系消失。

### 4. 需要新迁移，占 **V10**

两件事：

- `requirement` 加软删标记（`deleted_at` + `deleted_by`，均可空）。
- 新增**一张**留痕表承载三类删除的可追溯记录。R5 要求「留痕不得写入被删除对象自身」，而知识文档与成员是硬删，记录必须落在别处；需求虽是软删，为口径统一也写同一张表。业务表 19 → **20**。

只加一张而不是三张：三类删除要记的东西是同构的（谁、何时、删了什么、连带撤销了什么），拆三张纯属重复。

### 5. 迁移号：V10（V9 已由 `08-24-finding-explanation-and-remediation` 占用并已落地）

### 6. 删除作废需求的前置条件：只要求状态为 `CANCELED`

不额外要求「无 PR 关联且无 Finding」。软删之下所有既有引用继续有效，不存在悬空风险，因此那个前置条件只会平白挡住正常使用。需求软删后从产品列表消失，审计仍可追溯。

### 7. 批量上传：逐文件独立，且这个与成员批量添加的差异是有意的

成员批量添加是整批事务，因为部分成功会留下让人困惑的半套成员关系，且候选集小而同构。知识文件不同：每个文件独立有意义，一个不受支持的类型或一次 embedding 失败不该让另外九个已经成功的文件一起回滚；而横跨 N 次 embedding 外部调用的事务本身就是个长事务。故逐文件独立、逐文件报告结果。

### 8. 知识文档被引为需求附件时：拒绝删除

R1 要求二择一。选「拒绝」而不是「连带撤销附件关系」：附件关系是需求侧的事实，删知识文档不该顺手改变某个需求的附件构成。拒绝时返回明确原因并指出是哪些需求在引用，用户可以自己先解除附件再删。这也让删除保持幂等可判定。

### 9. 留痕表与 ARCHITECTURE §2.1「不建 `audit_event`」的边界（2026-08-25 实施前补）

§2.1 的不建清单里有「通用 `audit_event`（多态 entity_id 无法被 D006 复合外键约束）」，而第 4 条要的正是一张带多态 `resource_id` 的表。这条冲突的处理结论：**建这张表，并把禁令的适用范围写清楚，记为 D022。**

禁令的理由在删除台账上不适用——**被引对象按定义已经不存在**（两类硬删，且 R5 明令留痕不得写在被删对象自身），所以不是「没加外键」，是没有可加外键的目标。能约束的两列都约束了：`project_id` → `project`（AC13 靠它），`actor_user_id` → `user_account`。`resource_type` 是三个值的封闭 CHECK 词表，不是开放的多态注册表。拆三张窄表并不解决外键问题，只是把同一个问题抄三遍。

§2.1 的不建清单需同步补一句例外，否则后续会话读到的仍是一条绝对禁令。

### 10. 批量上传是纯前端，后端零改动（2026-08-25 实施前补）

R1 的「一次选择多个文件、逐个报告成功/失败、单个失败不阻断整批」正是 N 次调用现有 `POST /knowledge/documents` 的语义：每次调用自己一个事务，天然逐文件独立、天然逐文件有结果。因此批量上传不新增端点。

新建批量端点反而更差：它要么是一个事务（违反第 7 条的「单个失败不该让另外九个回滚」），要么是一个没有事务语义的 `POST`（只是把前端循环搬进服务端，还得发明一套逐行结果契约），而且会横跨 N 次 embedding 外部调用——第 7 条说的「长事务」正是这个。


## Out of Scope

- 建立向量索引（D019 已决策为非目标）。
- 需求状态转换留痕（D013.3 明确接受的缺口）；除非评审判定它是 R3 的前置条件。
- 组织/HR 系统、离职归档流程、成员重新激活工作流。
- 删除项目本身、删除用户平台账户、删除 PR 或 Review。
- 修改 Finding 的字段、内容或展示（属 `08-24-finding-explanation-and-remediation`）。
- 纯前端体验问题（属 `08-24-frontend-ux-remediation`）。
- 修改历史迁移或继承 Legacy 迁移历史。
- 修改或重跑正式评测资产。
