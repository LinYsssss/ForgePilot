# ForgePilot V2 架构决策记录（ADR）

Phase 0 冻结前由用户裁决的架构决策。修订 ARCHITECTURE/PRD 时以本目录为准；
后续新增决策按 `ADR-NNN-slug.md` 追加，不回改已接受的 ADR。

**例外记录**：2026-08-19 的 R2 契约复审中，用户明确批准修订 ADR-002 §7、ADR-003、ADR-007、ADR-008 —— 这四项与新增的 ADR-009/010/011 是同一批决议的组成部分，另开新号会使同一规则分散在两处，反而违反单一事实源纪律。除此之外不得回改已接受的 ADR。

| ADR | 决策 | 状态 |
|---|---|---|
| [ADR-001](./ADR-001-embedding-schema.md) | Embedding Schema：无维度 `vector` 列，V1 不建向量索引，Phase 4 独立 migration 建 HNSW expression index | 已接受 |
| [ADR-002](./ADR-002-large-pr-review.md) | Large PR Review：唯一引擎分批产 candidate/evidence，Final Synthesis 统一产出 AC verdict 与 Finding | 已接受 |
| [ADR-003](./ADR-003-review-identity.md) | Review Identity：业务键 `UNIQUE(pull_request_id, head_sha)`，engine/prompt/model 仅审计元数据 | 已接受 |
| [ADR-004](./ADR-004-domain-cardinality.md) | Domain Cardinality：LEADER 部分唯一索引 + Service 保底；Requirement 1:N PullRequest | 已接受 |
| [ADR-005](./ADR-005-requirement-attachment-retrieval-boundary.md) | Attachment Retrieval Boundary：附件为 requirement-scoped 知识，默认不跨需求召回 | 已接受 |
| [ADR-006](./ADR-006-cross-project-referential-integrity.md) | 跨项目引用完整性：复合外键兜底，Service 不写跨项目一致性校验 | 已接受 |
| [ADR-007](./ADR-007-pr-requirement-association.md) | PR↔Requirement 关联：分支/标题解析 `REQ-N` 优先，页面可改，失败不阻断 | 已接受 |
| [ADR-008](./ADR-008-review-context-and-recovery.md) | Review Context & Recovery：保存不可变上下文快照，先持久化 PENDING，再用 reconciliation 补偿进程内任务 | 已接受 |
| [ADR-009](./ADR-009-finding-continuity.md) | Finding Continuity：每轮独立快照 + 跨 Review 血缘；`evidence_hash` 基于确定性源码证据，误报抑制不跨 PR | 已接受 |
| [ADR-010](./ADR-010-scm-identity-and-repository-immutability.md) | SCM 身份映射：项目级稳定外部 ID 判定"本人 PR"，禁用户名授权；已有 PR 的仓库不可原地更换 | 已接受 |
| [ADR-011](./ADR-011-requirement-revision-and-state.md) | Requirement：删除 `IN_REVIEW`，引入不可变 `requirement_revision`，评审进展为只读派生量 | 已接受 |
