# ForgePilot V2 决策记录

状态：**R2.5 顶部导航基线（2026-08-23）**。

本文将原来分散的 11 份 ADR 收敛为一个决策记录，只解释“为什么这样定”和不可逆后果；可执行规则分别以 [PRD](./PRD.md)、[ARCHITECTURE](./ARCHITECTURE.md) 和 [IMPLEMENTATION-PLAN](./IMPLEMENTATION-PLAN.md) 为准。未来新增决策按 `D012...` 追加；修改已接受决策须用户明确批准并在 Git 历史中留痕。

<a id="d001"></a>
## D001 无维度向量列与延迟索引

**决定**：`knowledge_chunk.embedding` 使用无 typmod 的 pgvector `vector`；初始化 schema 不绑定模型维度、不建向量索引。Phase 4 冻结单一 Embedding Profile 后，用独立 migration 创建与检索 cast 完全一致的 HNSW expression index。

**理由**：模型维度是部署配置，不能让相同 Flyway 版本在不同环境生成不同结构。

**后果**：索引前顺序扫描；应用写入时显式校验维度。更换 Profile 是停写、重嵌入和重建索引的维护操作，不做在线双版本。

**执行状态（R2.5）**：那条 HNSW expression index 的独立 migration **没有创建，而且在当前 Profile 下创建不了**。冻结的 `Qwen/Qwen3-Embedding-8B` 是 4096 维，超过 pgvector 0.8.6 全部精确索引形态的维度上限。本条的索引条款由 [D019](#d019) 收窄为条件条款，实测与替代方案的代价一并记在那里。

<a id="d002"></a>
## D002 大 PR 分批产证据、统一合成

**决定**：始终只有一个 Review Engine。大 PR 的 Batch 只产 Finding candidate 和 AC evidence，全部 Batch 完成后统一合成最终 Finding 与 AC verdict；任一 Batch 修复一次后仍为非法结构，整次 Review FAILED。

**理由**：分批独立给 AC 结论会产生互相矛盾的 verdict；静默截断又会制造虚假完整性。

**后果**：保存 coverage/truncation manifest；未审文件显式展示；fingerprint 同时用于批内去重和跨 Review 血缘。

<a id="d003"></a>
## D003 Review 身份、当前有效性与终局 Decision

**决定**：

```text
Review Identity = PR + head SHA + review_input_fingerprint + requirement_revision
Current Validity = head/fingerprint/revision 均匹配 PR 当前值
Decision Gate = 同一 PR/head 是否已有 REQUEST_CHANGES
```

`review_input_fingerprint` 是规范化 SCM Diff 输入的确定性哈希，至少覆盖 provider/instance/repository、base/head、changed-file manifest 与 patch 内容；Provider 若提供能标识实际 Diff 版本的稳定 revision，也纳入哈希。仅用于乱序保护的事件序号或更新时间不应无条件进入哈希。Base 或 Diff 改变时，即使 head 不变，也产生新 Review 身份。

Decision 只允许 `PENDING → APPROVE | REQUEST_CHANGES` 一次。PR 行锁下校验 COMPLETED、decision=PENDING、当前 head/fingerprint/revision 及同 head 无 REQUEST_CHANGES，再做条件更新；并发请求只有一个成功。同 head 的 REQUEST_CHANGES 只能由新 head 解除，改 Base、关联或需求版本均不能绕过。

**理由**：head 不能完整标识实际 patch；同时关联/需求变更不应洗掉退回结论，人工终局事实也不能被覆盖。

**后果**：Engine/Prompt/Model 只作审计，不改变生产 Review 身份；知识更新不自动制造重审。旧 Review 永久保留但可被判定为已过期。

<a id="d004"></a>
## D004 领域基数

**决定**：每个项目恰有一个 LEADER；数据库部分唯一索引保证至多一个，Service 事务保证至少一个。Requirement 1:N Pull Request；MVP 中一个 PR 最多关联一个 Requirement。

**理由**：一个需求常被拆分为多个 PR，强制 1:1 不符合真实研发；LEADER 唯一性必须由数据库和事务共同保证。

**后果**：Requirement DONE 始终由 LEADER 人工确认，不自动聚合多个 PR 的 Review 结果。

<a id="d005"></a>
## D005 需求附件检索边界

**决定**：附件复用 KnowledgeDocument 的解析、切片和向量能力，但只允许所属 Requirement 的 Quality/Guidance/Review 场景召回。跨需求共享必须复制/沉淀为新的 Project Knowledge，原附件关系保留。

`requirement_attachment` 是领域关系事实源；`knowledge_document.source_requirement_id` 是受数据库约束的检索投影。附件类型必须有 scope，公共知识必须无 scope，一个 Document 最多属于一个 Requirement，二者同事务写入且由复合 FK 保证相等。

**理由**：无差别 project TopK 会将需求私有附件污染到其他需求；两份可独立修改的归属字段会形成双事实源。

<a id="d006"></a>
## D006 跨项目与跨上下文引用完整性

**决定**：所有项目内跨表引用使用包含 `project_id` 的复合外键；被引用表提供对应唯一键。数据库拒绝跨项目写入，Repository 读路径仍必须携带 projectId。

Finding 永久拥有 `(project_id,review_id) → review(project_id,id)` 父 FK，nullable Requirement 外键不能替代它。Finding 的 Requirement/Revision 必须与父 Review NULL-safe 一致；该跨行不变式由 migration 的约束触发器保证。AC、Requirement Revision、附件归属与 Finding 血缘均按 ARCHITECTURE 的完整链约束。

**理由**：运行时逐处校验容易遗漏；nullable 复合 FK 在 `MATCH SIMPLE` 下会跳过校验，不能证明父子关系。

**后果**：违反完整性映射为 409/422；若实施阶段发现约束触发器与 ORM 无法兼容，必须先新增决策，不得静默降级为无测试的 Service 纪律。

<a id="d007"></a>
## D007 PR 与 Requirement 关联

**决定**：同步 PR 时从分支名和标题解析第一个 `REQ-<n>`，失败不阻断入库；页面允许设置或清除。LEADER 始终可改；PR 作者在当前 head 尚无任何人工终局 Decision 时可改，即使自动 PENDING 已存在；其他 DEVELOPER/REVIEWER 不可改。

每次变更与 `pull_request_requirement_event` 同事务审计。关联变化不覆盖或取消历史 Review，也不自动重审；新上下文由有权限的人手动触发。

**理由**：把权限限定为“尚无任何 Review”会与 Webhook 同步创建 PENDING 冲突，使作者纠正路径实际不可达。

<a id="d008"></a>
## D008 Review 事务与恢复

**决定**：`PullRequestChanged` 是事务内同步事件。SCM 更新 PR 后同步发布，Review 监听器在同一事务中幂等创建 PENDING；监听失败则 SCM 事务回滚。只有事务提交后的 callback 才能提交有界执行器；提交失败保留 PENDING。

任务通过原子条件更新领取，每次递增 attempt、生成 execution token 与 lease。完成、失败、续租和插入 Finding 都必须匹配当前 token；旧 Worker 的条件写入为 0。reconciliation 只恢复已落库但未执行的超时 PENDING 和 lease 过期 RUNNING，禁止根据“当前上下文无 Review”补建任务。

**理由**：提交前启动会读取未提交数据；无 fencing 的恢复会让旧 Worker 覆盖新结果；自动补建又会把人工重审规则变成隐式第二 Pipeline。

<a id="d009"></a>
## D009 Finding 连续性与误报抑制

**决定**：每个 Review 保存独立 Finding 快照；跨轮使用 `finding_key/evidence_hash/basis_hash/continuity/carried_from_finding_id`。

- `evidence_hash`：确定性源码证据；规范化换行与易变行号，但不得对缩进敏感内容做通用空白折叠。
- `basis_hash`：被引用 Requirement/AC 内容、知识 excerpt/hash、确定性规则版本；不包含 LLM 自由文本。
- 只有同一 PR、同一 finding_key、两个 hash 均相同，且历史最近人工判定为 REJECTED，才以 `REJECTED + SUPPRESSED` 继承。
- `PERSISTING` 只比较紧邻上一条 COMPLETED Review；`SUPPRESSED` 查同一 PR 全部历史中的最近人工 REJECTED。
- 本轮未报告只推导为 NOT_REPORTED，绝不自动认定修复。

**理由**：只哈希模型描述不稳定；只比较源码会在 AC/知识规则变化后错误延续旧误报结论。

<a id="d010"></a>
## D010 SCM 身份、仓库身份与事件顺序

**决定**：成员的“本人 PR”权限使用项目级稳定外部用户 ID，禁止用户名比对；身份由 LEADER 通过 SCM API 配置。PR 保存不可变作者外部 ID/用户名快照和可重算的本地映射，成员退出后映射由列级 `ON DELETE SET NULL` 清空。

仓库稳定身份为 `provider + normalized instance identity + external repository id`。产生 PR 后三元组冻结；凭据/Secret 可换，api_base 只有验证仍指向同一实例才可更新。Webhook 只作同步触发，验签后读取 Provider 权威快照，并用 source revision/time 单调更新；重放、并发和乱序事件不得回退当前 base/head/patch。

**理由**：用户名可变且可被复用；仅冻结 provider/external id 无法区分不同自建实例；直接信任乱序 payload 会回退 PR 状态。

**后果**：最低 PostgreSQL 15，同时满足列级 `ON DELETE SET NULL` 与 Review 唯一键的 `NULLS NOT DISTINCT`。

<a id="d011"></a>
## D011 Requirement Revision 与派生 Review Activity

**决定**：Requirement 持久状态仅为 `DRAFT/READY/IN_DEVELOPMENT/DONE/CANCELED`，AI、Webhook、PR 和 Review 不推进状态。创建时同步建立 Revision 1；DRAFT 可原地编辑，READY 时冻结；之后每次修改由 LEADER 一次性发布新 Revision 并填写原因，AC 归属具体 Revision，`ac_key` 是跨版本稳定业务身份。

需求质量结果归属 Revision；DRAFT 正文或 AC 修改时同事务清空。Revision/关联/Diff 变化不自动重审，而是派生 `REVIEW_REQUIRED`。

单 PR activity 取值：`REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/APPROVED`。Requirement 无 PR 为 `NO_PR`；多 PR 时 FAILED、CHANGES_REQUESTED 依次占优，否则全部相同返回该状态、全部 APPROVED 才 APPROVED，其余为 `MIXED` 并显示明细计数。

**理由**：持久 `IN_REVIEW` 会迫使 SCM/Review 反向写 Requirement；原地修改需求会使历史 Review 指向已经改变的语义；没有 REVIEW_REQUIRED 又无法表达“有 PR 但当前输入尚未审查”。

<a id="d012"></a>
## D012 Phase 批次化授权

**决定**：Phase 2 起改为按批次授权，而不是逐个 Phase 单独授权：

```text
批次 1 = Phase 2 + Phase 3      Auth/Project/Member + Requirement/AC/Revision
批次 2 = Phase 4 + Phase 5      AI Gateway + Knowledge/pgvector + GitHub SCM
批次 3 = Phase 6 + Phase 7      Review Engine + 人工闭环
Phase 8 单独且最后               GitLab + 正式评测与答辩
```

每个批次仍必须有自己的 Trellis 任务、`prd.md`、`design.md`、`implement.md` 和验证清单，经用户确认后才 `task.py start`；批次结束时停在评审闸门，提交验收证据后才请求下一批次授权。批次内部的 Phase 顺序和各自的退出条件不变。

以下三条不随批次化放宽：

1. **holdout 仍锁定在 Phase 8**，配置冻结后只运行一次。任何提前运行、反复运行或据其调参都会永久摧毁该 12 例作为无偏估计的资格，且不可通过重跑恢复。
2. **Phase 6 的运行边界是实测输出**，不是可以预先写死的常量。并发 Review 上限（1 或 2）、单 Batch 输入预算和 changed-file 上限必须由目标 4 GB 机上的实际 Review 运行确定，并按 [ARCHITECTURE §7.2](./ARCHITECTURE.md#72-运行边界phase-6-实测冻结调整需更新本表) 更新该表。
3. **数据库约束的反馈回路必须保留**。[D006](#d006) 已经声明约束触发器与 ORM 可能不兼容，且该情况必须先新增决策而不是静默降级。批次划分刻意把这一风险留在批次 1（第一次真正建业务表时）暴露，而不是等六个 Phase 的代码堆完再发现。

**理由**：R2.3 已把 16 张表、依赖方向、状态机和 D001–D011 冻结得足够彻底，逐个 Phase 停一次的边际收益低，而横切模式（`project_id` 复合外键、Service/Query facade、错误映射）一次性统一实现比分批引入更一致。同时，把批次做到"一次写完全部 Phase"则会同时破坏上述三条：holdout 不可逆污染、Phase 6 参数变成猜测、schema 风险在六个 Phase 的代码之下暴露。批次为 2 个 Phase 是这两种失效模式之间的平衡点。

**后果**：授权闸门从 7 次减为 4 次。用户于 2026-08-21 明确批准本决策，同批次内不再逐 Phase 请求授权，但批次之间仍须停止并等待明确授权。

<a id="d013"></a>
## D013 批次 1 实现裁定

**背景**：批次 1 规划期做了三项研究（`.trellis/tasks/archive/2026-08/08-21-batch-1-auth-project-requirement/research/`），其中 `pg15-hibernate-constraints.md` 用真实 PostgreSQL 15.19 + Hibernate 7.4.1 实测了本模型最危险的约束。研究共提出 49 条开放问题；本决策裁定其中会改变实现形态或消除文档矛盾的部分，其余属实现细节，由 `design.md` 承担。

**总判据**（用户 2026-08-21 要求）：优先选零新增表、零新增列、零新增抽象的可行解；只有当简单解会让两条权威规定互相矛盾或根本跑不起来时才升级，并说明为什么简单解不成立。**以下裁定没有一条改动 16 表定义。**

### D013.1 复合外键的实体映射形态（实测强制）

**决定**：所有把 `project_id` 带进复合键的关联，统一采用**变体 A**——`@JoinColumn` 全部 `insertable=false, updatable=false`，另用标量 `Long xxxId` 承担写入。全局统一，禁止与变体 B 混用。

**理由**：Hibernate 7.4.1 **拒绝启动**最自然的写法，实测报 `AnnotationException: Column mappings for property 'currentRevision' mix insertable with 'insertable=false'` 与 `MappingException: Column 'project_id' is duplicated in mapping`。由于每条项目内外键都带 `project_id`，该冲突会出现在 16 张表几乎每个关联上，不是个案。实测只有两种合法形态。选 A 而非 B（关联可写、`project_id` 由目标推导）的理由：A 的服务层写普通 `Long`，DTO 映射与批量写入都不绕；跨项目写入由数据库复合外键以 23503 拒绝，这正是 [D006](#d006) 规定数据库承担的职责，不需要再用 ORM 结构二次保证；且标量外键更契合 [ARCHITECTURE §1.3](./ARCHITECTURE.md#13-依赖规则单向唯一定义处)「跨模块只经 Service/Query facade」——本就不该靠实体图跨模块导航。

**后果**：该约定必须写入 `.trellis/spec/backend/`，否则第一个写复合关联的人会撞上启动期异常。变体 B 在防跨项目写入上更强，被否决的代价是服务层需自行保证 `projectId` 取值正确，由数据库兜底。

### D013.2 `REQ-<n>` 的 `<n>` 使用 `requirement.id`

**决定**：`<n>` 直接是 `requirement.id`，不新增项目内展示编号列。解析必须按 PR 所属项目过滤，外项目 id 解析不到即落入 [P1](./PRD.md) 已规定的「未关联需求」。

**理由**：`ARCHITECTURE §2.1` 的 `requirement` 列清单没有编号列，而 P1 与 PRD §7 的 E2E 验收都要求解析 `feat/REQ-<n>-*`。新增编号列需要一个额外唯一键并改动已冻结的表定义，而 id 已全局唯一、足以定位。代价是各项目看到的编号不连续，属展示层美观问题，不影响正确性。

### D013.3 P7 审计范围收窄，不新增 `requirement_event`

**决定**：[P7](./PRD.md)「人工决策全部留痕」的适用范围是 `ARCHITECTURE §2.1` 已定义审计结构的那些决策：PR↔需求关联（`pull_request_requirement_event`）、Finding 生命周期（`finding_event`）、Review 终局（`review` 行上的 decision 列）、Revision 发布（`requirement_revision.created_by/change_reason/created_at`）。需求状态转换（DRAFT→READY、指派、CANCELED、DONE）在 MVP 不单独留痕。

**理由**：P7 的依据栏写的就是 `ARCHITECTURE §2.1`，而 §2.1 明禁通用 `audit_event`（多态 entity_id 无法被 D006 复合外键约束），16 表里也没有需求状态审计表。新增第 17 张表须满足 §2.1 结尾的门槛；在 Phase 3 尚无人工闭环的前提下，该门槛不成立。收窄解释即可消除 PRD 与表清单之间的张力，无需新增结构。

**后果**：这是明确记录的 MVP 缺口。Phase 7 建设人工闭环时若确有需要，须带正式决策新增 `requirement_event`，不得由实现自行补表。

### D013.4 `CANCELED` 的可达性

**决定**：`CANCELED` 可由任意非终态（`DRAFT` / `READY` / `IN_DEVELOPMENT`）到达，是终态，不可恢复。

**理由**：PRD §5 的状态图中该分支横跨整行，两种读法都成立；取"任意非终态可取消"是最少意外的读法，且不引入"取消后恢复"这条额外状态边。

### D013.5 项目创建者同事务成为 LEADER

**决定**：任何已登录用户都可以创建项目；创建项目与写入该用户的 `project_member(role=LEADER)` 在同一事务内完成。`project.created_by` 记录创建者，与 LEADER 身份是两件事——LEADER 可后续转移，`created_by` 不变。

**理由**：PRD §3 把「创建项目」列在 LEADER 列，但创建之前该用户在该项目没有任何角色，[D004](#d004)「至少一个 LEADER」因而没有起点。同事务落 LEADER 是唯一不需要新增结构的解法，也让 D004 的不变式从第一条记录起就成立。

### D013.6 `auth` 暴露只读账户目录，§1.3 的措辞收窄

**决定**：`user_account` 归 `auth`。`auth` 对外只暴露一个只读 Query facade（按 id / username 查 id、username、enabled），`project` 等业务模块可依赖该 facade。`ARCHITECTURE §1.3`「业务模块不依赖 auth」收窄为：**业务模块不依赖 auth 的认证机制**（Session、Spring Security、登录状态），身份仍按原文由 Controller 从登录上下文取 `userId` 传参；读取账户展示信息经该只读 facade。

**理由**：成员管理必须按 username 添加成员、必须展示成员用户名，而 §1.3 原措辞只解决了写入方向的身份传递，没解决读取方向。备选方案各有代价：把 `user_account` 挪进 `common` 与 §1.1 对 `common` 的定义（API error、paging、clock、纯安全工具）矛盾；在 `project_member` 冗余 username 需要新增列且会陈旧。**本裁定不需要改动 ArchUnit**：§1.4 的五条规则并不包含「project 不得依赖 auth」，且 facade 是 Service 而非 Repository，规则 4、5 均不触发。

### D013.7 会话与 CSRF：进程内 HttpSession，无新增表

**决定**：使用 Spring Security 默认的服务端 `HttpSession`（进程内），CSRF 用 Spring Security 的 cookie token repository。密码哈希用 Spring Security 默认的 BCrypt `PasswordEncoder`。`user_account.session_version` 仅在改密码和「强制全端登出」时递增，普通登录/登出不动它。

**理由**：`ARCHITECTURE §7.1` 禁止 Redis，16 表里也没有 session 表，新增表须过门槛。进程内会话零新增依赖、零新增表，与「单体、单实例、有界进程内执行器」的既定运行形态一致。自定义签名 Cookie 协议被否决：Legacy 的 `TokenService` 在迁移矩阵中是 REFERENCE 而非 KEEP，明确「不原样继承私有 Token 协议」，自己实现一套签名/续期/撤销比用框架现成机制复杂得多。

**后果**：**进程重启会话即失效**，这是单节点部署下被接受的代价，须在部署说明中写明，不得靠新增持久化去掩盖。

### D013.8 LEADER 转移的写法

**决定**：LEADER 转移固定为同一事务内**先把原 LEADER 降级并 flush，再把新成员升级**。禁止写成单条 `UPDATE ... CASE` 交换。

**理由**：实测发现部分唯一索引**无法 DEFERRABLE**（三种写法分别报 42601/42601/42809），且单语句交换的成败**依赖物理扫描顺序**——同一条语义等价的 SQL，旧 LEADER 的 ctid 在前就成功、在后就报 23505。这是一个会随数据分布随机复现的缺陷，必须在写法上根除而不是靠重试。

### D013.9 D004「至少一个 LEADER」的语义澄清

**决定**：[D004](#d004) 的「Service 事务保证至少一个 LEADER」是**每次事务提交后的不变式**，不是事务内每一时刻的不变式。

**理由**：因 D013.8，转移过程中必然存在短暂的「零个 LEADER」中间状态；该状态位于事务内部，受隔离性保护，对外不可见。任何 immediate 约束都无法表达「至少一个」，这不是实现缺陷而是约束表达能力的边界。

### D013.10 复合外键的匹配与延迟语义

**决定**：批次 1 的全部外键为默认 `NOT DEFERRABLE INITIALLY IMMEDIATE`，且 `requirement.current_revision_id` 这条复合自引用外键**必须**保持 `MATCH SIMPLE`。需求创建固定为同事务三步：插 `requirement`（`current_revision_id = NULL`）→ 插 `requirement_revision` → 回填。

**理由**：实测三步回填在非延迟下全程通过，回填别的需求/别的项目的 revision、删除在用 revision 均被 23503 拒绝；Hibernate 的 flush 顺序（INSERT 先于 UPDATE）天然产出该顺序，无需人工干预。改 `MATCH FULL` 会直接报 `MATCH FULL does not allow mixing of null and nonnull key values`，使回填设计不可能——这条必须写死，避免后来者"顺手加严"。

**后果**：数据库无法证明「每个已提交的需求都有 current revision」（该列永远可为 NULL）。该不变式由 Service 事务保证并由集成测试覆盖，与 D013.9 同类。

### D013.11 约束冲突一律不捕获后继续

**决定**：任何数据库约束冲突（唯一、外键、CHECK、约束触发器）都不得捕获后在同一事务内继续，统一映射为 409/422 并让事务回滚。

**理由**：实测约束触发器错误会使事务进入 25P02，即便加 JDBC savepoint 也会撞 `UnexpectedRollbackException`。该结论与 `ARCHITECTURE §2.3`「约束冲突统一映射为 409/422」原本的规定一致，此处只是给出实测依据并明确禁止"捕获后降级处理"的写法。

### D013.12 前向说明：§2.3 的两个"备选"实际收敛（Phase 6）

**决定**：`ARCHITECTURE §2.3` 关于 Finding 约束触发器给出的两个备选（覆盖父表更新 / 直接拒绝身份列变化）在实测中收敛为同一个——`INITIALLY IMMEDIATE` 下父子协同改写在两种顺序下都失败，只有 `SET CONSTRAINTS ALL DEFERRED` 能完成。此处仅作前向记录，Phase 6 实现时按此设计，不在批次 1 改动文档。

**理由**：避免 Phase 6 的实现者误以为存在两条可选路线而反复试错。

---

**D013 不改变**：16 张表的定义、模块边界、依赖方向（仅收窄 §1.3 对 auth 的措辞）、ArchUnit 五条规则、holdout 纪律，以及 [D001](#d001)–[D012](#d012) 的任何已接受结论。

---

## D014 批次评审授权委托给编排会话

**决定**：[D012](#d012) 要求「每个批次通过**人工评审**后才能授权下一批次」。用户在 2026-08-21
明确把该评审职责委托给编排会话本身：「后续的工作全部交由你判断……直接一直运行**包括后续批次的
审核运行**都交给你了，不用问我了」，并在批次 1 完成后再次确认「持续工作，有你审批，完成项目直到落地」。

因此批次 2 及以后**不再等待用户逐批开口授权**，但 D012 的三条不可放松规则原样保留，且评审标准不降低：

1. **holdout 仍锁死在 Phase 8 且只跑一次**，配置冻结之后。这条不受本决策影响，也不可由编排会话自行放松。
2. **Phase 6 的运行边界（并发 Review 上限、batch 预算）仍是实测输出**，不得预写为常量。
3. **[D006](#d006) 的 schema 反馈回路仍然有效**：约束与 ORM 冲突时回到决策，不在代码里加兼容分支。

**评审标准**（编排会话在开下一批次前必须逐条自证，写进上一批次的 `result.md`）：
构建与测试全绿且无 skip；Compose 空库冷启动通过；CI 全部 job 绿；边界检查（无计划外的表、
顶层包、一级菜单、运行时依赖）通过；`result.md` 已如实记录偏差与缺口，**部分通过的验收条件必须标为部分通过**。
任何一条不成立就停下，不进入下一批次。

**理由**：用户是本项目唯一的人类评审者，且已连续多次要求不要为逐批授权中断。
继续在文档里写「须用户单独授权」会让下一个会话与用户的实际指令直接矛盾——
而本项目的首要判据正是「能运行、没有矛盾点」。把委托写成决策，比让闸门文本与事实不符要诚实。

**后果**：编排会话同时是实现者与评审者，独立性因此弱于外部评审。作为补偿，评审标准以**可复现的命令与
真实输出**为准，不接受"我认为没问题"；批次 1 已按此执行——AC11 因缺少自动化浏览器点击闭环而被记为
**部分通过**，而不是凑成全绿。用户随时可以收回该委托。

---

## D015 批次 2 实现裁定（结构性部分）

**背景**：批次 2 的三份研究（`.trellis/tasks/archive/2026-08/08-21-batch-2-ai-knowledge-scm/research/`）提出 28 条开放问题，
其中 `pgvector-hibernate-measured.md` 在真实 PostgreSQL 15.19 + pgvector 0.8.6 + Hibernate 7.4.1 上实测了
本批次最危险的几条。本决策只裁定**会改变迁移结构或模块边界**的部分；其余属实现细节，由 `design.md` 承担。

**总判据**（沿用 [D013](#d013)）：优先零新增表、零新增抽象的可行解；只有当简单解让两条权威规定互相矛盾
或根本跑不起来时才升级，并说明为什么简单解不成立。**以下裁定不新增第 17 张表。**

### D015.1 `ai_call_log.review_id` 先建列、不建外键，批次 3 补

**决定**：`ai_call_log` 在批次 2 的迁移中建出 `review_id BIGINT`（可空），**暂不建**
`(project_id, review_id) -> review(project_id, id)` 复合外键。批次 3 建 `review` 表的迁移里用
`ALTER TABLE ... ADD CONSTRAINT` 补上该外键。迁移中必须写明这不是遗漏。

**理由**：`ai_call_log` 属 Phase 4，`review` 属 Phase 6，被引用表还不存在，外键无法建立。
三个备选各有硬伤：把 `ai_call_log` 推迟到 Phase 6 会让 Phase 4 的 AI 调用无处留痕，
与「评测与故障定位」的建表目的直接矛盾；建一张过渡表是新增第 17 张表；不建这一列则 Phase 6 要改已应用的迁移。
Flyway 追加式补外键是标准做法，不违反「迁移只增不改」。

**后果**：批次 2 期间 `review_id` 不受数据库约束。暴露面为零——本批次没有任何代码写它，
且 `review` 表尚不存在，任何非空值都必然是错的。批次 3 补外键前必须先断言该列此刻全为 NULL。

### D015.2 `requirement_attachment.requirement_id` 必须 NOT NULL（实测承重）

**决定**：`requirement_attachment.requirement_id` 与 `document_id` 均为 `NOT NULL`。

**理由**：实测（研究 §3j）——`(project_id, document_id, requirement_id) -> knowledge_document(project_id, id,
source_requirement_id)` 这条三列外键在 `MATCH SIMPLE` 下，**只要子表 `requirement_id` 可空，整条检查就蒸发**，
一个根本不存在的 `document_id` 也能直接落库。该外键正是 [D005](#d005) 附件归属的唯一执行者，
可空等于把它变成装饰。同一实测确认：父表 `knowledge_document` 必须有 `UNIQUE(project_id, id,
source_requirement_id)`，且公共知识（`source_requirement_id IS NULL`）**无法**被挂成附件（23503）——
这正是 D005 要的行为，且把父唯一键改成 `NULLS NOT DISTINCT` 也救不回来（§3l）。

### D015.3 批次 2 不建向量索引；维度只加"自洽 CHECK"，真正的防线在应用层

**决定**：批次 2 **不**创建任何向量索引。`knowledge_chunk` 增加一条**不绑定具体维度**的 CHECK：

```sql
CONSTRAINT ck_knowledge_chunk_dimension
    CHECK (embedding IS NULL OR dimension = vector_dims(embedding))
```

写入前的维度一致性校验由应用层承担，并且必须有显式失败的测试。

**理由**：实测（§2a–2c）——无维度列上 `ivfflat`/`hnsw` 一律 `22023: column does not have dimensions`，
所以 [D001](#d001)「不绑维度」与「建向量索引」在批次 2 无法同时成立；表达式索引是 Phase 4 之后的出路（§2f）。
更关键的实测（§1k）：**无维度列在建索引之前，数据库完全不校验维度**，同一项目内混入一行错维度向量，
该项目**所有** TopK 查询立刻 `22000: different vector dimensions` 失败——一行脏数据毒死整个项目的检索。
上面这条 CHECK 只保证「声明的维度与实际向量自洽」，挡不住"整列声明成同一个错维度"，
因此 `ARCHITECTURE.md` §5「应用层写入时校验维度」**不是可选纪律，而是唯一防线**，必须这样写进 spec。
好消息：`project_id` 硬过滤在排序表达式之前求值（§1l），别的项目污染不了本项目。

### D015.4 `knowledge_chunk.embedding` 不映射进实体

**决定**：`KnowledgeChunk` 实体**不映射** `embedding` 列。向量的写入与 TopK 检索一律走带 `::vector` cast 的
原生 SQL，集中在一个 Repository 里。不引入 `hibernate-vector` 依赖。

**理由**：实测（§6b–6g）四种写法：不映射 ✅ 启动并运行正常；`String` ❌ 启动即失败；
`String + columnDefinition="vector"` ⚠️ **validate 放行、运行时才炸 42804**（最危险的一种，明确禁止）；
`@JdbcTypeCode(SqlTypes.VECTOR) float[]` ✅ 功能完整但需要新增 `hibernate-vector` 依赖。
同时实测确认 **`ddl-auto: validate` 只检查"实体映射到的列"，未映射的列它根本不看**，所以不映射不会破坏启动期校验。
选不映射的理由：TopK 检索本来就必须用原生 `<->`/`<=>` 运算符，映射了也绕不开；
向量不是 Java 侧要操作的领域状态，而是检索载荷；零依赖增量符合本项目的既定纪律。

**后果**：`embedding` 列不受 `validate` 保护，实体与 schema 在这一列上的漂移不会在启动期暴露。
代价由那个唯一的原生 SQL Repository 的集成测试承担。若 Phase 6 确实需要在 HQL 里做向量运算，
再带决策引入 `hibernate-vector`（版本由 Boot 4.1.0 管理，无需写 `<version>`）。

### D015.5 孤立代理项必须在应用层拒绝（实测破口）

**决定**：写入任何文本列之前，应用层必须拒绝含孤立 UTF-16 代理项的文本（用 `CharsetEncoder` 或等价校验），
并作为显式失败纳入 Phase 4 退出条件的测试。

**理由**：实测（§7i–7n）——NUL 字节（`22021`）、非法字节序列（`22021`）都由数据库显式拒绝，
但**孤立代理项被 PgJDBC 的编码器静默替换成 `?`**：`javaIn=[0061 d800 0062]` 落库成 `613f62`，
往返不等，数据库收到的是合法 UTF-8，`22021` 永远不会触发。这是 Phase 4
「非法输入必须显式失败而不是损坏数据」在实测中**唯一**的破口，数据库无从拦截。
同时记录另一条实测：`varlena` 的 1 GB 上限在本项目量级上不是防线（600 MB 文本真的落库成功），
`ARCHITECTURE.md` §7.2 的「单文件 5 MB + `KnowledgeUploadValidator`」是唯一真实的大小约束。

### D015.6 `scm` 可依赖 `requirement` 的只读查询 facade，§1.3 相应收窄

**决定**：`ARCHITECTURE.md` §1.3 的依赖行由 `common, project ← scm` 改为
**`common, project, requirement ← scm`**。`requirement` 对外只暴露一个只读 Query facade
（按 `(projectId, requirementId)` 判断存在性并返回展示所需的最小信息），`scm` 只能用它，
**不得**注入 `RequirementRepository`（ArchUnit 规则 4 仍然生效）。

**理由**：[D013.2](#d013) 要求 `REQ-<n>` 按 PR 所属项目解析，外项目 id 解析不到即「未关联需求」；
而 [D007](#d007)/P1 要求解析失败**不阻断入库**。数据库复合外键做不到这件事：它只会让整条插入失败，
而捕获约束冲突后继续正是 [D013.11](#d013) 明令禁止的。因此解析必须在写入之前完成，
`scm` 必须能问「这个 id 在本项目存在吗」。**方向上无环**：`requirement` 不依赖 `scm`，
`review` 本就同时依赖两者。本裁定与 [D013.6](#d013) 为 `auth.UserDirectory` 所做的收窄同型，
不改动 ArchUnit 五条既有规则。

### D015.7 changed-file manifest 与 patch 存 `pull_request` 的 JSONB 列

**决定**：changed-file manifest 与各文件 patch 以 JSONB 列存在 `pull_request` 行上，不新增表。
写入前施加明确的大小上限，超限显式失败并在 PR 行上标记，**不静默截断**。

**理由**：`IMPLEMENTATION-PLAN.md` Phase 5 明文要求「**保存** …changed files、patch 和确定性
`review_input_fingerprint`」。不存则 fingerprint 的输入无法从数据库复现，
「PR 权威快照」就不成其为快照，Phase 6 的幂等与历史语义会失去依据；单独建表是第 17 张表，被门槛挡住。
JSONB 让一个 PR 仍是一行，且与 `review_input_fingerprint` 同事务写入。
[D002](#d002)「未审查文件必须显式呈现，禁止静默截断」的精神在此同样适用于超限 patch。

### D015.8 无凭据测试形态固定为 JDK 自带 HTTP 服务器

**决定**：AI Gateway 与 GitHub Provider 的自动化测试一律打到 `com.sun.net.httpserver.HttpServer`
（`jdk.httpserver`，随构建镜像的 JDK 21 自带）或 `MockRestServiceServer`（已随
`spring-boot-starter-test` 在 classpath 上）。**不新增 WireMock / MockWebServer / MockServer 依赖。**
GitHub 客户端的 base URI **必须**取自 `scm_repository.api_base`，**禁止硬编码 host**。

**理由**：两份研究分别核对了 `backend/pom.xml` 与 `~/.m2`：`jdk.httpserver@21.0.11` 与
`MockRestServiceServer` 都已可用，WireMock/MockWebServer 都不在，新增即是新增依赖，
而 `quality-guidelines.md` 要求新增门槛必须举出它能挡住的真实故障——此处举不出。
JDK 服务器是真实 socket，能证明超时真的触发、能计数从而证明「恰好重试一次」、
能返回畸形 JSON 驱动失败分类，这些都是 `MockRestServiceServer` 做不到的。
「不硬编码 host」这一条同时满足两个目的：[D010](#d010) 要求支持自建实例，而 `api_base` 正好就是测试接缝——
生产需求与可测性在这里是同一件事。

### D015.9 [D013.11](#d013) 的实测边界修正

**决定**：D013.11「约束冲突一律不捕获后继续」的结论**不变**，但其理由需精确化：
实测（§5f–5i）确认约束触发器错误使事务进入 `25P02` 后，**SQL 层的 `SAVEPOINT` 可以救回，JPA 层不行**。

**理由**：批次 1 的表述是「即便加 JDBC savepoint 也会撞 `UnexpectedRollbackException`」，
这在 JPA/Spring 事务边界内成立，但作为对 PostgreSQL 的普遍陈述过强。
禁令本身不依赖这个细节——它依赖的是 D013.11 的另一半（捕获后继续会让"部分成功"的写入提交），
但一份说得过头的理由日后会被人用「我用的是原生 SQL 所以不适用」绕过去。

**本决策不改变**：16 张表的定义、[D001](#d001) 的不绑维度、模块边界（仅收窄 §1.3 对 `scm` 的措辞）、
ArchUnit 七条规则、holdout 纪律，以及 [D001](#d001)–[D014](#d014) 的任何其它已接受结论。

---

## D016 批次 2 的两处实现偏离，正式化

**背景**：批次 2 的 `result.md` 把 AC20 记为**部分通过**，理由是本批次做了两个
[D015](#d015) 没有授权、也没有回写成决策的判断。本决策把它们正式化，
使批次 3 不必在文档与代码之间猜测哪一个才作数。

### D016.1 changed-file 超限只拒绝，不在 PR 行上标记

**决定**：[D015.7](#d015) 要求「超限显式失败并**在 PR 行上标记**，不静默截断」。
`pull_request` 上没有可以承载该标记的列，因此本项目**放弃「标记」这半条**：
超过 `ChangedFile.MAX_TOTAL_CHARS` 时整条投递以 `422` 失败，一个字节都不写。

**理由**：D015.7 真正要防的是「Review 被告知一份残缺的 diff 是完整的」——
整条拒绝比标记更彻底地达成了这个目的，代价是那次投递不留痕。
补「标记」需要在 `pull_request` 上加列，而 §2.1 的列清单扩充在批次 2 只授权了
`knowledge_document` 那两列（见批次 2 `result.md` §2）。

**后果**：超限的 PR **在系统里完全不存在**，运维看不到「有一个 PR 因为太大被拒了」。
MVP 单仓库规模下 4,000,000 字符的 diff 极罕见，但这是一个真实的可观测性缺口。
**该路径目前零测试覆盖**（`MAX_TOTAL_CHARS` 在测试代码中零引用），批次 3 若触及 `pull_request` 应顺手补一条。

### D016.2 P1 的 DEVELOPER 半条授权推迟到批次 3

**决定**：`PUT /api/projects/{p}/pull-requests/{id}/requirement` 在批次 2 **只允许 LEADER**。
PRD P1 的另一半「本人 PR 且当前 head 尚无人工终局 Decision」**推迟到批次 3**，
与 `review` 表同批次落地。

**理由**：批次 2 没有 `review`，「尚无终局 Decision」无法表达。
可选的三条路里，写一个恒答「没有终局」的判断会**多授权**（任何 DEVELOPER 对自己的 PR 恒可改关联，
包括已经有人做过终局决策之后），比不授权更危险；提前建 `review` 表越界到批次 3；
因此只剩「先不授权」。**收窄授权范围永远比放宽安全**，这是本项目一贯的 fail-closed 取向。

**后果**：批次 3 建 `review` 之后**必须**补上这半条，否则 PRD P1 长期只实现一半。
已写入批次 2 `result.md` §10 的前置条件清单与 `PullRequestAssociationService` 的 javadoc。

**执行状态（R2.5，已完成）**：这半条已经补上。`PullRequestAssociationService` 现在允许 PR 作者在本 head 尚无任何终局 Decision 时纠正关联，作者身份按项目级稳定外部 id 判定（D010），闸门由 `PullRequestDecisionGate` 回答——接口声明在 `scm`、实现落在 `review`，因此编译期依赖方向仍是 `review → scm`，ArchUnit 规则 3 未被触碰。作者被闸门挡住时返回 409 而不是 403：角色和人都是对的，挡住他的是这个 head 上已经发生的事实，推一个新 commit 就能改变。前端 `ReviewDetailPage` 对作者显示同一张表单，但不复制判定——授权仍然只在后端。

**本决策不改变**：16 张表的定义、[D001](#d001) 的不绑维度、模块边界、ArchUnit 七条规则、
holdout 纪律，以及 [D001](#d001)–[D015](#d015) 的任何其它已接受结论。

<a id="d017"></a>
## D017 六入口产品界面与主链路补全

**决定**：正式前端采用“工作台 / 项目 / 研发需求 / 项目知识 / 仓库接入 / 代码审查”六个一级入口。工作台只在浏览器端组合真实列表 API，展示项目研发概况与“需求质量检查 → 知识增强实现建议 → 唯一 AI Review Engine”的能力链；它不是聊天、Agent、自动执行或新的业务域。项目知识与仓库接入成为独立页面，旧 `/projects/:id/settings` 只作兼容跳转。

Project Knowledge 与 Requirement 附件补齐正式 HTTP 用户流程；附件关系仍以 `requirement_attachment` 为唯一事实源并与 Document 同事务写入。向量检索在 SQL 中同时按项目与当前 Requirement 过滤，无 Requirement 上下文时只能召回公共 Project Knowledge。Guidance 使用 Requirement、AC、公共知识与当前需求附件，一次性返回 `checklist/rules/risks` 和真实召回引用。SCM 读取只返回安全元数据。

**理由**：原三入口界面隐藏了已经属于主因果链的 Knowledge 与 SCM，并且缺少上传、附件、刷新读取和知识增强 Guidance 的用户闭环。只读工作台和真实向量元数据能让用户理解系统亮点，同时不增加持久化、第二运行时或虚构遥测。

**后果**：前端契约由 3 个一级入口扩展为 6 个，桌面 Shell 改为侧边导航并保留窄屏可达性；两份用户 Logo 成为正式品牌资源。允许展示 Chunk 数、已嵌入数、维度、Embedding Profile、索引状态和语义召回相似度，但禁止返回原始向量或凭据。16 表、8 包、一个仓库/项目、唯一 Review Engine、AI 不改变业务状态和不可变评测证据均不改变。

> 侧边导航这一半已由 [D018](#d018) 取代为顶部居中应用栏。六个入口本身、路由、权限与数据源不变。

<a id="d018"></a>
## D018 顶部居中导航与单页面单 Logo

**决定**：D017 的六个一级入口保持不变，但正式桌面 Shell 从侧边导航改为顶部应用栏：横版 Logo 位于左侧、六入口导航位于页面水平中心、账户操作位于右侧。窄屏在既有 `64rem` 断点变为两行，导航继续水平滚动且不隐藏入口。

同一页面只展示一种 Logo。已登录 Shell 使用 `logo-lockup.png`；登录页只使用 `logo-app.png`；应用图标继续作为 favicon。登录页不再并排展示两份品牌资源。

**理由**：六入口已经稳定，不需要侧栏才能承载。顶部居中导航能减少内容区被挤压，并让各业务页面拥有一致的横向工作空间；单页面单 Logo 可建立更清晰的品牌层级，避免登录页出现重复标识。

**后果**：本决策只改变信息架构的空间布局和品牌摆放，不改变路由、权限、项目查询参数、业务状态源或 AI/向量能力。仍只允许六个一级入口，不引入抽屉依赖、第三断点、UI 框架或第二套导航运行时。

<a id="d019"></a>
## D019 冻结 Profile 下不建向量索引

**背景**：[D001](#d001) 规定 Embedding Profile 冻结之后，用一条独立 migration 建出与检索 cast 完全一致的 HNSW 表达式索引。该 migration 至今未写。R2.5 的文档复核先把它记为「计划中未兑现」，随后补做了实测——结论比「忘了写」更硬。

**实测**（pgvector 0.8.6 / PostgreSQL 15；冻结 Profile 为 `Qwen/Qwen3-Embedding-8B`、`qwen3-embedding-8b-4096-v1`，**4096 维**）：

| 索引形态 | 结果 |
|---|---|
| `hnsw ((embedding::vector(4096)) vector_cosine_ops)` | ❌ `column cannot have more than 2000 dimensions for hnsw index` |
| `hnsw ((embedding::halfvec(4096)) halfvec_cosine_ops)` | ❌ `column cannot have more than 4000 dimensions for hnsw index` |
| `ivfflat ((embedding::vector(4096)) vector_cosine_ops)` | ❌ `column cannot have more than 2000 dimensions for ivfflat index` |
| `hnsw ((binary_quantize(embedding)::bit(4096)) bit_hamming_ops)` | ✅ 建得出 |
| `hnsw ((subvector(embedding::vector(4096),1,2000)::vector(2000)) vector_cosine_ops)` | ✅ 建得出 |

**决定**：本部署**不建任何向量索引**，语义检索保持顺序扫描给出的精确余弦序。D001 中「建 HNSW expression index」那一句在当前 Profile 下**不可执行**，就此收窄为条件条款：只有当 Embedding 维度降到 2000 及以下、或 pgvector 放宽维度上限时才重新生效。

**理由**：三条，缺一条这个决定都不成立。

1. 被承诺的那条索引**建不出来**——不是没人写，是在这个维度上写不了。
2. 唯一建得出的两种形态都是**有损预筛**：二值量化按 Hamming 距离排序，subvector 直接丢掉一半以上的维度。要用它们就必须再加一个 rerank 阶段，于是「加索引」从一次性能优化变成一次**检索契约变更**。而 `.trellis/spec/backend/quality-guidelines.md` 要求新增复杂度必须举出它能挡住的真实故障。
3. 举不出那个故障。MVP 下单项目的 chunk 数是几十到几百量级，顺序扫描是亚毫秒级；`project_id` 硬过滤又在排序表达式之前求值（[D015.3](#d015) 实测），跨项目数据根本不进入这次扫描。为这个规模引入有损预筛加 rerank，是拿正确性去换一个测不出来的速度。

第三条路是把 Profile 换成 2000 维以下（Qwen3 支持 MRL 维度截断），精确 HNSW 就重新可用。它被否决**不是因为方案不好，而是代价不在本次范围内**：换 Profile 按 D001 是停写 → 全量重嵌入 → 重建索引的维护操作，且发生在答辩前。它是本决策日后最可能的出路。

**后果**：`ChunkSearchRepository` 的查询保持 `c.embedding <=> ?::vector`，不引入 cast 表达式，也不引入第二段检索。`KnowledgeVectorIndexTest` 把上表的三条拒绝钉成断言：pgvector 一旦放宽上限，该测试就会失败，而那次失败正是「D019 的前提变了，回来重开」的信号——一份躺在 markdown 里的实测结论没有这个性质。

**重新评估的触发条件**：单项目 chunk 数进入万级；或检索延迟成为可观测问题；或 Embedding Profile 换到 ≤2000 维。

<a id="d020"></a>
## D020 成员目录、多角色与用户自有 SCM 多身份

**决定**：账户增加非空 `display_name`，成员选择时同时展示显示名、用户名和平台 ID；LEADER 通过分页搜索选择已有账户并一次最多原子添加 50 人。同一成员可同时拥有 `LEADER / DEVELOPER / REVIEWER` 多个角色，授权取角色能力并集。项目仍恰有一个 LEADER，授予或移除 LEADER 只能走独立转移动作。

SCM 身份改由用户本人持有。用户用一次性 Token 调用 GitHub/GitLab 当前用户接口，平台只保存 Provider、规范化实例、稳定外部用户 ID、当前用户名、标签、用途和验证时间，不保存 Token。一个用户可有多个身份；同一个已验证远端身份只能归属一个本地账户。

项目成员只能为自己选择与项目仓库 Provider/实例兼容的身份，并再次用一次性 Token 验证当前用户及仓库访问级别。项目默认自动激活绑定；LEADER 可为仓库开启严格模式，使新绑定进入 `PENDING_APPROVAL`，但 LEADER 只能批准或拒绝，不能替成员选择身份。每个成员在每个项目同时最多一个活动绑定，替换、撤销与审批结果保留历史。只有活动且已验证的绑定参与 `pull_request.author_user_id` 重算和“本人 PR”授权，用户名始终只显示。

**数据实现**：V8 增加 `project_member_role`、`scm_identity`、`project_member_scm_binding` 三张表，并从 V1–V7 的单角色/Leader 手填身份原地迁移。旧手填身份保留为 `LEGACY_UNCONFIRMED` 证据，不获得授权；`project_member` 的旧角色和 SCM 列随后删除。表从 16 增至 19，Flyway 从 V7 增至 V8。

**理由**：把角色数组或多组 SCM 字段继续塞在 `project_member` 会失去可验证的唯一性、所有权和绑定历史；三张关系表分别对应三个不可合并的业务事实。标签与用途解决“Leader 无法判断员工多个账号各自用途”的问题，稳定外部 ID 继续解决用户名可变/可复用的问题。一次性 Provider 验证消除 Leader 手填错误，又无需 OAuth 应用、Token 存储或通用凭据系统。

**明确不做**：成员软删除/移除、延迟约束触发器、通用权限引擎、通用审批流、候选人 SCM readiness 预查询、Provider 原始权限 JSON、OAuth 应用和多仓库。前端只增加非一级 `/account` 页面，六个一级入口不变。

**取代范围**：本决策只取代 [D010](#d010) 中“身份由 LEADER 配置”的成员身份部分；D010 的仓库稳定身份、Webhook 顺序和禁止用户名授权继续有效。历史文档中的“16 表”描述其当时基线，不代表当前 V8 形态。

## D021 Finding 的问题说明、修复建议与模型置信度

**决定**：AI Review Finding 承载模型自己写的问题说明与修复建议，并记录模型自报的置信度。模型输出 schema 两处 finding 结构同步增加 `explanation`、`suggestion`、`confidence` 三个必填字段，各带 2000 字符上限；`evidence` 的「逐字引用」语义不变，说明与建议是模型自己的话，二者在契约与界面上都必须可区分。修复建议只是建议：AI 不产出补丁、不自动改码、不自动流转 Finding 状态。

置信度只记 `HIGH / MEDIUM / LOW` 三档，不记数值。模型自报的把握没有经过校准，一个小数会让读者以为它校准过；三档说得清「更值得先看」，又说不出它并不知道的精度。它不参与任何自动门禁或状态流转，在 UI 上与 Finding 人工状态、Review Decision 三者分开呈现，不合并为综合徽章——这与 `LEGACY-MIGRATION-MATRIX.md` 把按置信度自动 gate 的 `FindingDecisionEntity` 标为 DROP 是同一条边界。记录它并非新增概念：PRD 5 与本文档的 UI 契约早已要求三者分开呈现，前端也早已留有「未记录」占位，本决策只是把那个占位兑现。

模型已在产出的 `category` 一并落库。它一直是 `finding_key` 的输入，此前算完 key 就被丢弃；落库后一条散文缺失的 Finding 仍保有「类型 + 类别 + 证据 + 定位」，不会退回完全无法理解。

**数据实现**：V9 给 `finding` 追加 `category`、`explanation`、`suggestion`、`confidence` 四列，不新增表，业务表仍 19 张，Flyway 从 V8 增至 V9。四列全部可空，这是对事实的如实表达而非宽松——V9 之前的 Finding 确实没有这些内容，用 NOT NULL 加空默认值会给历史行伪造一段空说明，并让「模型什么都没说」与「模型说了空字符串」不可区分。`category` 与 `confidence` 各有一个容忍 NULL 的 CHECK 封闭词表。

两个版本号朝相反方向移动：`ReviewPrompts.VERSION` 必须升为 `"review-2"`，因为它自身的契约规定任一指令或 schema 变更都要跟着升，而一份存档报告只有对着产生它的那个 Prompt 才可解读；`FindingKeys.RULE_VERSION` 必须保持 `"1"`，因为没有任何确定性规则发生变化，递增它会改写全部 `basis_hash` 并丢弃全部继承抑制项。

校验器对新字段的处置一律不丢弃整条 Finding：散文缺失接受为空、超长截断到 2000 字符、类别与置信度越界存 NULL，四种情形都记 warning。词表映射必须发生在写库之前，且 `finding_key` 继续使用模型给出的原始 `category` 字符串——落库那一份用归一化后的词表值。

**理由**：台账把 T-010 记为「页面未承载」，核实后根因在契约最上游——两处 schema 的 `required` 里根本没有说明或建议字段，`finding` 表也没有对应列。这是「从未生产过」，不是「生产了没展示」，所以修复必须贯穿 Prompt、校验器、迁移、实体、API 与前端，只改前端不可能有效。

新增散文安全的前提是它们进不了三个哈希。V6 已经写明两个 hash 不得覆盖模型措辞，否则抑制随措辞漂移；`explanation`、`suggestion` 与 `confidence` 恰是回答里唯一允许随措辞自由变动的输出，一旦其中任何一个渗进 `finding_key`、`evidence_hash` 或 `basis_hash`，同一个问题就会在每轮换个说法后变成「新问题」，跨轮去重、抑制与血缘同时失效，而故障要到下一轮审查才显现。

`category` 落库带来一个必须在写库前挡住的新故障模式：校验器此前从不校验该词表，越界值算完 key 即弃、没有后果；一旦落库且列上有 CHECK，模型幻觉出的值会中止**整批** finding 插入而不是单行（与 `ck_finding_code_quality_has_no_ac` 同形态）。因此 CHECK 是最后一道防线而不是第一道，而一个与 schema 枚举不一致的 CHECK 比没有 CHECK 更糟——它会拒绝合法类别并连带丢掉整批。schema 枚举、`FindingCategory` 与 `ck_finding_category` 三者一致由测试贯通全词表来保证。

散文缺失时接受为空而非拒绝整条，沿用校验器既有哲学：只有确定性部分不可用才丢弃（无 `evidence` 会让所有无证据 finding 共享一个 `evidence_hash`，对一条的驳回会抑制掉不相干的另一条）。散文进不了任何哈希，为它丢掉一条有证据的有效 Finding 是净损失；为啰嗦而拒绝是同一种损失换了个理由，所以超限是截断。

**明确不做**：向量索引（[D019](#d019) 已决策为非目标）；让 AI 产出补丁、自动改码或自动流转 Finding 状态；置信度参与任何自动门禁、排序权重或质量结论；置信度记为数值或声称已校准；修改 `RULE_VERSION` 或任何哈希的输入构成；新增表、顶层包、一级导航、AI runtime、第二 Review 流程或运行时依赖；重跑、修改或扩充正式评测实验。前端不新增路由，`finding_key` 与两个哈希收进折叠区而非移除——答辩时仍要能当场展开证明抑制确实按哈希工作。

**对评测证据的影响**：无。`evaluation/tools/*.py` 对后端零引用，不导入 `ReviewPrompts`、不调用 `/api/`，因此改动 Prompt 契约不会使已冻结的三臂结论失效。答辩表述仍须精确：冻结实验证明的是「知识进上下文有用」，而不是「ForgePilot 检索管线有效」。
