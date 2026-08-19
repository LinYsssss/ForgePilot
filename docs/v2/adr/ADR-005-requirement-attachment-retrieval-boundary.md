# ADR-005 Requirement Attachment Retrieval Boundary：附件是 requirement-scoped 知识

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §5 · §2.1、[PRD.md](../PRD.md) §4

## 背景

Requirement 附件复用 KnowledgeDocument 存储（一次解析、一个事实源），但附件内容属于单个需求的
语境。若 project-scoped TopK 无差别召回，其他需求的 Review 会被无关附件污染，
且附件可能含只对该需求有效的约定。

## 决策

1. Requirement Attachment 继续**复用 KnowledgeDocument**，但定位为 **requirement-scoped knowledge**。
2. `knowledge_document` 增加/明确 `source_type` 与 `source_requirement_id` 两列。
3. Project Rule / Architecture / Design / API 等公共知识允许整个 Project 检索。
4. `source_type = REQUIREMENT_ATTACHMENT` 的文档**默认只允许所属 Requirement 的 AI 场景检索**
   （该需求的 Quality Check、该需求关联 PR 的 Review）。
5. 其他 Requirement 的 project-scoped TopK **不允许召回**该附件。
6. 若需要跨 Requirement 共享，必须**显式提升/沉淀为 Project Knowledge**，不允许隐式共享。

## 后果与实施注记

- 检索边界是 SQL 硬过滤，不是召回后内存过滤：
  `WHERE project_id = :projectId AND (source_type <> 'REQUIREMENT_ATTACHMENT' OR source_requirement_id = :requirementId)`；
  无 Requirement 语境的检索传 NULL，只命中公共知识。
- 依赖方向不变：knowledge 把 `source_requirement_id` 当作**不透明 scope id**，不 import
  requirement 的任何类型；DB 层可对 `requirement(id)` 建 FK 保证完整性。
- "显式提升"是一次有审计的操作（复制/转换为公共 `source_type`），不是改一个布尔位；
  提升后原附件关系保留。
- 跨项目隔离规则（`project_id` 硬过滤）在附件之前依然先行生效。
- Phase 4 退出条件补充用例：需求 A 的附件不得被需求 B 的检索召回。
