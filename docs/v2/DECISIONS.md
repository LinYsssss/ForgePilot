# ForgePilot V2 决策记录

状态：**R2.3 已验收开发基线（2026-08-20）**。

本文将原来分散的 11 份 ADR 收敛为一个决策记录，只解释“为什么这样定”和不可逆后果；可执行规则分别以 [PRD](./PRD.md)、[ARCHITECTURE](./ARCHITECTURE.md) 和 [IMPLEMENTATION-PLAN](./IMPLEMENTATION-PLAN.md) 为准。未来新增决策按 `D012...` 追加；修改已接受决策须用户明确批准并在 Git 历史中留痕。

<a id="d001"></a>
## D001 无维度向量列与延迟索引

**决定**：`knowledge_chunk.embedding` 使用无 typmod 的 pgvector `vector`；初始化 schema 不绑定模型维度、不建向量索引。Phase 4 冻结单一 Embedding Profile 后，用独立 migration 创建与检索 cast 完全一致的 HNSW expression index。

**理由**：模型维度是部署配置，不能让相同 Flyway 版本在不同环境生成不同结构。

**后果**：索引前顺序扫描；应用写入时显式校验维度。更换 Profile 是停写、重嵌入和重建索引的维护操作，不做在线双版本。

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
2. **Phase 6 的运行边界是实测输出**，不是可以预先写死的常量。并发 Review 上限（1 或 2）、单 Batch 输入预算和 changed-file 上限必须由目标 4 GB 机上的实际 Review 运行确定，并按 [ARCHITECTURE §7.2](./ARCHITECTURE.md#72-运行边界初值phase-6-评测后可调调整需更新本表) 更新该表。
3. **数据库约束的反馈回路必须保留**。[D006](#d006) 已经声明约束触发器与 ORM 可能不兼容，且该情况必须先新增决策而不是静默降级。批次划分刻意把这一风险留在批次 1（第一次真正建业务表时）暴露，而不是等六个 Phase 的代码堆完再发现。

**理由**：R2.3 已把 16 张表、依赖方向、状态机和 D001–D011 冻结得足够彻底，逐个 Phase 停一次的边际收益低，而横切模式（`project_id` 复合外键、Service/Query facade、错误映射）一次性统一实现比分批引入更一致。同时，把批次做到"一次写完全部 Phase"则会同时破坏上述三条：holdout 不可逆污染、Phase 6 参数变成猜测、schema 风险在六个 Phase 的代码之下暴露。批次为 2 个 Phase 是这两种失效模式之间的平衡点。

**后果**：授权闸门从 7 次减为 4 次。用户于 2026-08-21 明确批准本决策，同批次内不再逐 Phase 请求授权，但批次之间仍须停止并等待明确授权。
