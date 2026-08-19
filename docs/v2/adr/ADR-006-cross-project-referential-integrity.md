# ADR-006 跨项目引用完整性：复合外键兜底，而非运行时校验

- 状态：已接受（2026-08-19）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §2.3、[ADR-004](./ADR-004-domain-cardinality.md)、[ADR-005](./ADR-005-requirement-attachment-retrieval-boundary.md)

## 背景

D2 的项目隔离此前只有**运行时纪律**（"查询必须带 projectId"），数据库层没有任何约束。
当一张表存在两条及以上通往项目的外键路径时，数据库允许两条路径指向不同项目。已确认的漏洞：

- `pull_request`：`repository_id`（→ 项目 A）与 `requirement_id`（→ 项目 B）可跨项目。
- `requirement_attachment`：`requirement_id` 与 `document_id` 可分属不同项目。
- `knowledge_document`：自身 `project_id` 与 `source_requirement_id` 所属项目可不一致。
- `knowledge_chunk`、`finding`、`ai_call_log` 同理。

若靠 Service 层逐处补 "两个 id 是否同项目" 的判断，该判断会散落到十几个方法里——
既是累赘代码，又只要漏一处就形成越权入口。

## 决策

1. **规则**：每张项目内业务表都携带 `project_id`；凡指向另一张项目内业务表的外键，
   一律声明为**包含 `project_id` 的复合外键**。
2. 被引用表除主键外增加 `UNIQUE (project_id, id)`，作为复合外键的引用目标。
3. 适用表（存在 2 条及以上项目路径者）：
   `pull_request`、`requirement_attachment`、`knowledge_document`（附件归属）、
   `knowledge_chunk`、`review`、`finding`、`finding_event`、`ai_call_log`。
4. 单路径表（如 `acceptance_criterion → requirement`）不加冗余 `project_id`，
   其项目归属由唯一父路径确定。
5. 可空外键使用默认 `MATCH SIMPLE`：外键列为 NULL 时不校验，符合
   "PR 可以暂不关联需求"（ADR-007）的业务事实。
6. `finding.ac_id` 额外声明 `(requirement_id, ac_id) → acceptance_criterion(requirement_id, id)`，
   保证 Finding 引用的 AC 属于其 Requirement。

## 后果与实施注记

- Service 层**不再**编写跨项目一致性校验；违反约束由数据库抛错，映射为 409/422。
- ArchUnit 与集成测试的越权用例保留：约束保证数据不可能写错，测试保证读路径不泄露。
- 复合外键要求被引用表先建 `UNIQUE (project_id, id)`；`V1__init.sql` 中注意建表顺序。
- 迁移代价为零（绿地），但后续新增项目内业务表必须遵守本规则，否则视为架构违规。
