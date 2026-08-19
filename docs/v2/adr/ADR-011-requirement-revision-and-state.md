# ADR-011 Requirement 状态、需求版本化与派生 Review Activity

- 状态：已接受（2026-08-19，用户裁决；R2.1 修订：明确 DRAFT Revision 编辑与冻结语义、质量结果归属、重审权限）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §1.3 · §2.1 · §3.5、[PRD.md](../PRD.md) §5 · §6、[ADR-003](./ADR-003-review-identity.md)、[ADR-004](./ADR-004-domain-cardinality.md)、[ADR-009](./ADR-009-finding-continuity.md)

## 背景

两个缺口同源，一并裁决。

其一，状态流 `DRAFT → READY → IN_DEVELOPMENT → IN_REVIEW → DONE` 只定义了 `DRAFT → READY` 与 `→ DONE` 的推进者，`IN_DEVELOPMENT` 与 `IN_REVIEW` 由谁在何事件推进未定义。若由 PR 或 Review 事件自动推进，则 `scm`/`review` 必须写 requirement 状态，违反 ARCHITECTURE §1.3 的单向依赖与"自动化不改变业务状态"原则；且 ADR-004 已定"一个需求可有多个 PR"，多 PR 时"何时算进入评审"无法自动判定。

其二，ARCHITECTURE §3.5 规定 Requirement 进入 READY 后正文与 AC 默认锁定，但真实研发中 AC 必然修订，方案未给任何变更路径。原地修改会使 Review 出现"结论说 AC-3 未覆盖，点进去 AC-3 已是另一条内容"的错位，正是 ADR-008 保存上下文快照要防的事。

## 决策

### 一、持久状态

1. **删除 `IN_REVIEW`**。Requirement 持久状态为 `DRAFT / READY / IN_DEVELOPMENT / DONE / CANCELED`。
2. `DRAFT → READY`：LEADER 确认需求。
3. `READY → IN_DEVELOPMENT`：与**首次指派**在同一事务内完成；后续更换负责人不再改变状态。
4. `→ DONE`：LEADER 确认全部关联工作完成（ADR-004）。
5. AI、Webhook、PR、Review **一律不得推进**这些状态。

### 二、派生 Review Activity

6. 评审进展是**只读派生量**，不落表，不是 `requirement.status` 的取值。
7. 取值与确定性归并优先级：
   `FAILED > CHANGES_REQUESTED > REVIEWING > PENDING > MIXED > APPROVED > NO_PR`。
8. 只统计**当前有效 Review**，须同时匹配 PR 当前 `head_sha` 与 PR 当前关联的 `requirement_revision_id`（ADR-003）。
9. UI 上两个维度正交呈现，例如「需求状态：开发中 / 评审活动：修改待复审」。

### 三、需求版本化

10. 引入不可变的 `requirement_revision`：

    ```text
    requirement                  稳定业务身份、负责人、状态、current_revision_id
    └── requirement_revision     不可变需求正文版本 + 修改人/原因/时间
        └── acceptance_criterion 归属具体 revision，旧 AC 永久保留
    ```

11. **Revision 的可编辑性由父 Requirement 的状态唯一决定**，Revision 自身不带状态位：
    - 创建 Requirement 时**同步创建 Revision 1**，`requirement.current_revision_id` 立即指向它。
    - Requirement 处于 `DRAFT` 时，Revision 1 **允许原地编辑**（正文与 AC）。
    - `DRAFT → READY` 在同一事务内**冻结** Revision 1。
    - READY 之后创建的每个新 Revision **一次性创建为已发布状态**，不存在可编辑的新 Revision。

    因此"不可变"的准确表述是**已发布的 Revision 不可变**。**禁止**给 `requirement_revision` 增加
    `is_draft` / `status` 之类的列——可编辑性只有一个事实源。
12. READY 后修改由 LEADER 创建**新 Revision**，**必须填写变更原因**；AC 随 Revision 重新生成，旧 AC 永久保留。
13. Review 创建时读取当前需求版本并保存 `requirement_revision_id`。
14. **需求版本发布后不自动重审**：关联 PR 显示"审查已过期"，由人工触发重新审查。触发权限统一遵循 PRD §3 的「触发/重试 Review」一行：LEADER 与 REVIEWER 可操作项目内任意 PR，DEVELOPER 仅限本人 PR；`FAILED` 重试与版本过期后的重审**共用同一套权限**，不另设规则。
15. `acceptance_criterion` 的业务身份是独立、稳定、不可变的 `ac_key`（如 `AC-0001`）；`sort_order` 仅负责显示顺序，**不承担业务身份**（ADR-009 §11）。
16. **需求质量检查结果归属 `requirement_revision`**，不再挂在稳定的 `requirement` 上：`quality_json` / `quality_version` / `quality_checked_at` 三列随 Revision 存储。DRAFT 期间 Revision 可编辑，正文或 AC 一经修改，必须在**同一事务内清空**该 Revision 的质量结果，避免展示与正文对不上的旧报告。
17. 同一 head 已存在 `REQUEST_CHANGES` 时，切换需求版本**仍不能** APPROVE，必须产生新 head（ADR-003 Decision Gate）。

## 后果与实施注记

- 第 5 条与第 14 条共同保证依赖方向：`requirement` 既不写 `scm` 也不写 `review`，`scm`/`review` 也不写 `requirement`。任何"自动推进"的实现都会破坏 ARCHITECTURE §1.3，属架构违规。
- 第 7 条中 `FAILED` 一档不可省略：缺它则 AI 调用失败的 PR 在需求页永远显示 `REVIEWING`，与 ARCHITECTURE §3.2「FAILED 靠人工重试」的可见性前提冲突。
- 第 8 条按"至少存在一个已完成 Review"判断是错的：PR push 新 head 后旧 Review 仍满足该条件，页面会显示过期结论。
- 需求列表页必须用**一次聚合查询**计算派生活动，禁止逐需求查 PR 再查 Review（N+1）。
- 第 12 条使需求变更史独立于 Review 存在：`review.context_snapshot_json` 只能证明"有 Review 时"的上下文，未触发 Review 的修改在快照中无任何记录。
- `requirement.current_revision_id` 与 `requirement_revision.requirement_id` 互为外键，按固定顺序解决而**不用 DEFERRABLE**（更直观、更好测）：建 `requirement`（`current_revision_id = NULL`）→ 建 `requirement_revision` → 建该 Revision 下的 AC → 回填 `current_revision_id`。该回填由复合外键 `(id, current_revision_id) → requirement_revision(requirement_id, id)` 保证指向自身 Requirement 的 Revision（ADR-006 §7）。
- 需求版本链、Finding 上下文归属与 CHECK 约束的完整定义在 [ADR-006](./ADR-006-cross-project-referential-integrity.md) §6–8，本文不重复。
- 落地阶段：状态机、`requirement_revision` 与质量结果归属在 Phase 3；派生 Review Activity 在 Phase 6（Review 存在后才有意义），Phase 3 先返回 `NO_PR`。
