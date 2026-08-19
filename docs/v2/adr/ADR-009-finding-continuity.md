# ADR-009 Finding Continuity：独立快照 + 跨 Review 血缘

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §2.1 · §3.4 · §3.5、[PRD.md](../PRD.md) §5、[ADR-002](./ADR-002-large-pr-review.md)、[ADR-011](./ADR-011-requirement-revision-and-state.md)

## 背景

修复后按新 head SHA 产生新 Review（ADR-003），但旧 Review 中已 `REJECTED` 的误报、已 `CONFIRMED` 的问题在新 Review 中如何处理此前未定义。

不定义的后果是双向的：每次 push 后 Reviewer 都要重新驳回同一批误报，人工闭环体验崩塌；而若简单继承旧状态，又会出现代码已改但 Finding 仍挂 `FIXED/VERIFIED` 的假闭环。

## 决策

1. 每次 Review 保存自己**独立、不可篡改**的 Finding，跨轮关系由血缘字段表达，不改写历史行。
2. `finding` 增加四列，各司其职，**语义维度不得混用**：

   | 列 | 含义 |
   |---|---|
   | `status` | 人工处理生命周期，PRD §5 原状态机不变 |
   | `continuity` | 跨 Review 关系：`NEW / PERSISTING / SUPPRESSED` |
   | `evidence_hash` | 问题依据是否实质未变 |
   | `carried_from_finding_id` | 直接来源，血缘指针 |
   | `finding_type` | `REQUIREMENT / CODE_QUALITY`，决定 `finding_key` 组成 |

3. `NOT_REPORTED`（上轮有、本轮无）**由查询推导，不持久化**。
4. `evidence_hash` 必须基于**确定性源码证据**生成，**禁止哈希 LLM 生成的问题描述**；哈希前统一换行符、去除行号与非语义空白。
5. 上轮 `REJECTED` 且 `evidence_hash` 未变 → 本轮 `SUPPRESSED`，不要求重复驳回；UI 折叠呈现，Reviewer 可重新打开。
6. 上轮问题仍在 → `PERSISTING`，但新 Finding 一律以 `OPEN` 落库，**不继承旧工作流状态**。
7. 本轮未再报告 → 仅推导为 `NOT_REPORTED`，**不得自动认定已修复**。
8. `evidence_hash` 实质变化 → 视为新问题，重新确认。
9. **抑制作用域仅限同一 PR**，不跨 Requirement、仓库或其他 PR。
10. `finding_key` 组成：`CODE_QUALITY` 用路径（保留大小写）+ 归一化位置 + 类别；`REQUIREMENT` 必须额外含 `requirement_id + ac_key`。
11. `ac_key` 是 `acceptance_criterion` 上独立、稳定、不可变的业务身份（如 `AC-0001`），**不得使用行 `id` 或序号**。`Finding.ac_id` 仍指向审查时的具体 AC 版本，仅用于历史审计。
12. **"上一轮"必须有确定性定义**。同一 head 下可能存在多个需求版本的 Review，因此两类判断使用**两条不同的查找规则**：

    | 用途 | 查找范围 |
    |---|---|
    | `PERSISTING` / `NEW` / `NOT_REPORTED` | 同一 PR 内**紧邻的上一条 `COMPLETED` Review**，按 `(created_at, id)` 确定性排序，与 head、需求版本无关 |
    | `SUPPRESSED` | 同一 PR 内**全部历史 Review** 中该 `finding_key` 的**最近一次人工判定**（按 `(finding_event.created_at, finding_event.id)` 排序），取结果为 `REJECTED` 且 `evidence_hash` 未变者 |

13. `continuity` 计算优先级固定为 **`SUPPRESSED > PERSISTING > NEW`**。
14. `carried_from_finding_id` 的来源选择：
    - `SUPPRESSED` → 指向产生最近一次有效 `REJECTED` 判定的那条 Finding；
    - `PERSISTING` → 指向紧邻上一条 `COMPLETED` Review 中 `finding_key` 匹配的那条 Finding。
15. Finding 的上下文归属（`requirement_id` / `requirement_revision_id` 何时为空、与 `ac_id` 的组合约束）由 [ADR-006](./ADR-006-cross-project-referential-integrity.md) §8 的 CHECK 保证，本文不重复。

## 后果与实施注记

- 第 4 条是抑制机制成立的前提：LLM 措辞天生不稳定，哈希描述会让抑制在换一次采样后失效，机制形同虚设。
- 第 7 条与 ADR-002「禁止静默截断」一致：模型本轮未报告可能只是该文件被分批预算挤掉，不能推定已修复。
- 第 11 条防止 AC 归属 revision（ADR-011）后，一次错别字修正让全部 AC 换行 id、进而击穿所有需求类 Finding 的抑制。措辞修正但语义不变时保留 `ac_key`，语义实质改变时创建新 `ac_key`。
- `finding_type` 显式落列而非由 `ac_id IS NULL` 反推，使 `finding_key` 组成规则可单测；存在"违反需求但不指向具体 AC"的 Finding，反推不可靠。
- ADR-002 §7 的 fingerprint 定义随之扩写为「批内去重 + 跨 Review 血缘键」。
- 抑制项必须可见可撤销：UI 折叠区显示"已抑制（N）"，Reviewer 可重新打开，避免抑制变成静默吞噬。
- 第 12 条的两条规则不可合并。`PERSISTING` 问的是"上一次审查里还在不在"，天然只看紧邻一轮；抑制问的是"人有没有判定过这是误报"，一次驳回的效力不应因为中间插入一次无关重审而失效。若共用"紧邻上一轮"，误报会在隔一轮后复活。
- 代码质量类与需求类 Finding **不需要**不同的查找范围：`finding_key` 已把需求维度编入（§10），需求版本变更且 `ac_key` 改变时自然不匹配，无需额外规则。
- `(created_at, id)` 的复合排序是必须的：同一事务内创建的多条记录时间戳可能相同，仅按时间排序会产生不确定的"上一轮"。
