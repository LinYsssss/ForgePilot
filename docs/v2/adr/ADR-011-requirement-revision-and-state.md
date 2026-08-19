# ADR-011 Requirement 状态、需求版本化与派生 Review Activity

- 状态：已接受（2026-08-19，用户裁决）
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

11. DRAFT 阶段编辑工作版本；READY 时发布不可变 Revision 1。
12. READY 后修改由 LEADER 创建**新 Revision**，**必须填写变更原因**；AC 随 Revision 重新生成，旧 AC 永久保留。
13. Review 创建时读取当前需求版本并保存 `requirement_revision_id`。
14. **需求版本发布后不自动重审**：关联 PR 显示"审查已过期"，由 LEADER / Reviewer 手动触发重新审查。
15. `acceptance_criterion` 的业务身份是独立、稳定、不可变的 `ac_key`（如 `AC-0001`）；`sort_order` 仅负责显示顺序，**不承担业务身份**（ADR-009 §11）。
16. 同一 head 已存在 `REQUEST_CHANGES` 时，切换需求版本**仍不能** APPROVE，必须产生新 head（ADR-003 Decision Gate）。

## 后果与实施注记

- 第 5 条与第 14 条共同保证依赖方向：`requirement` 既不写 `scm` 也不写 `review`，`scm`/`review` 也不写 `requirement`。任何"自动推进"的实现都会破坏 ARCHITECTURE §1.3，属架构违规。
- 第 7 条中 `FAILED` 一档不可省略：缺它则 AI 调用失败的 PR 在需求页永远显示 `REVIEWING`，与 ARCHITECTURE §3.2「FAILED 靠人工重试」的可见性前提冲突。
- 第 8 条按"至少存在一个已完成 Review"判断是错的：PR push 新 head 后旧 Review 仍满足该条件，页面会显示过期结论。
- 需求列表页必须用**一次聚合查询**计算派生活动，禁止逐需求查 PR 再查 Review（N+1）。
- 第 12 条使需求变更史独立于 Review 存在：`review.context_snapshot_json` 只能证明"有 Review 时"的上下文，未触发 Review 的修改在快照中无任何记录。
- `requirement.current_revision_id` 与 `requirement_revision.requirement_id` 互为外键，按固定顺序解决而**不用 DEFERRABLE**（更直观、更好测）：建 `requirement`（`current_revision_id = NULL`）→ 建 `requirement_revision` → 建该 Revision 下的 AC → 回填 `current_revision_id`。复合外键保证 Revision 确属该 Requirement。
- ADR-006 §6 的 `(requirement_id, ac_id) → acceptance_criterion` 复合外键须随 AC 归属变更同步调整。
- 落地阶段：状态机与 `requirement_revision` 在 Phase 3；派生 Review Activity 在 Phase 6（Review 存在后才有意义），Phase 3 先返回 `NO_PR`。
