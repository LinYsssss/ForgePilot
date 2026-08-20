# ADR-006 跨项目引用完整性：复合外键兜底，而非运行时校验

- 状态：已接受（2026-08-19；R2.1 修订：AC 归属 Revision 后的完整约束链；R2.2 修订：current_revision_id 与 carried_from_finding_id 改用可用的复合唯一键）
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
   `pull_request`、`pull_request_requirement_event`、`requirement_attachment`、
   `knowledge_document`（附件归属）、`knowledge_chunk`、`review`、`finding`、
   `finding_event`、`ai_call_log`。
4. 单路径表原则上不加冗余 `project_id`，其项目归属由唯一父路径确定。
   **例外：`requirement_revision` 虽然只有 `→ requirement` 一条路径，仍必须携带 `project_id`**，
   因为第 7 条的 `review → requirement_revision` 复合外键需要 `UNIQUE (project_id, requirement_id, id)`
   作为引用目标。删掉该列会使第 7 条的三条外键同时失效。
5. 可空外键使用默认 `MATCH SIMPLE`：外键列为 NULL 时不校验，符合
   "PR 可以暂不关联需求"（ADR-007）的业务事实。
6. AC 归属 `requirement_revision`（ADR-011），因此 `acceptance_criterion` 配
   `UNIQUE (requirement_revision_id, id)`；`finding.ac_id` 声明
   `(requirement_revision_id, ac_id) → acceptance_criterion(requirement_revision_id, id)`，
   保证 Finding 引用的 AC 属于该 Finding 所依据的需求版本。
7. 需求版本链的完整约束（缺一条即无法在数据库层证明三者同属一个需求版本）：

   ```text
   requirement.current_revision_id
     (project_id, id, current_revision_id)
       → requirement_revision(project_id, requirement_id, id)

   review
     (project_id, requirement_id, requirement_revision_id)
       → requirement_revision(project_id, requirement_id, id)

   finding
     (review_id, requirement_id, requirement_revision_id)
       → review(id, requirement_id, requirement_revision_id)

   finding.carried_from_finding_id
     (project_id, carried_from_finding_id) → finding(project_id, id)
   ```

   `review` 因此需要 `UNIQUE (id, requirement_id, requirement_revision_id)`、`finding` 需要
   `UNIQUE (project_id, id)` 作为引用目标；前三条复用 `requirement_revision` 上已有的
   `UNIQUE (project_id, requirement_id, id)`，不再额外建索引。
   **不再单独声明 `finding → requirement_revision`**：Finding 的三元组已锁定到 Review，
   Review 的三元组又指向 `requirement_revision`，传递保证成立，多一条约束只增加维护成本。
   `carried_from_finding_id` 的外键只能保证来源 Finding **同项目**；"来源必须属于同一 PR"
   （ADR-009 §9 的抑制作用域）无法用外键表达，由 Service 不变式 + 集成测试保证。
8. `review` 与 `finding` 的上下文归属由 CHECK 约束保证：

   ```sql
   -- review
   CHECK ((requirement_id IS NULL AND requirement_revision_id IS NULL)
       OR (requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL))

   -- finding
   CHECK ((requirement_id IS NULL AND requirement_revision_id IS NULL)
       OR (requirement_id IS NOT NULL AND requirement_revision_id IS NOT NULL))

   CHECK (ac_id IS NULL OR requirement_revision_id IS NOT NULL)

   CHECK (finding_type <> 'CODE_QUALITY' OR ac_id IS NULL)
   ```

   语义：Review 未关联 Requirement 时，其全部 Finding 的 `requirement_id` 与
   `requirement_revision_id` 同时为 NULL；Review 已关联时，**所有** Finding（含 `CODE_QUALITY`）
   都复制 Review 的这两列。区分两类 Finding 的是 `ac_id` 而非上下文归属：`CODE_QUALITY`
   要求 `ac_id` 为 NULL，`REQUIREMENT` 的 `ac_id` 可空（允许"不指向具体 AC 的需求问题"）。
9. 审计表的 actor 指向 **`user_account`** 而非 `project_member`。这与
   `pull_request.author_user_id → project_member`（ADR-010）指向不同表，是**刻意区分**：
   前者是活的权限输入，成员退出项目即应失效；后者是审计事实，退出项目不得抹掉历史。
   不得以"保持一致"为由统一二者。

## 后果与实施注记

- Service 层**不再**编写跨项目一致性校验；违反约束由数据库抛错，映射为 409/422。
- ArchUnit 与集成测试的越权用例保留：约束保证数据不可能写错，测试保证读路径不泄露。
- 复合外键要求被引用表先建 `UNIQUE (project_id, id)`；`V1__init.sql` 中注意建表顺序。
- 迁移代价为零（绿地），但后续新增项目内业务表必须遵守本规则，否则视为架构违规。
