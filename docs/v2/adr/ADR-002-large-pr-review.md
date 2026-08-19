# ADR-002 Large PR Review：分批产证据、终局统一合成

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §3.4 · §3.5、[PRD.md](../PRD.md) §6 P5

## 背景

大 PR 的 changed-file patch 可能超出单次 LLM 调用预算。若"一次 Review = 一次 LLM 调用"，
只能靠截断牺牲覆盖；若分批各自产出 AC verdict，会出现跨批矛盾（批 1 `NOT_FOUND` + 批 2 `COVERED` → ?）
与 Finding 跨批重复。

## 决策

1. 保持**唯一 ReviewEngine**；"一次 Review"不等于"一次 LLM 调用"。
2. 小 PR 可单次调用完成。
3. 大 PR 使用 `ChangedFileBatcher` 分批处理。
4. Batch 阶段只输出 **Finding candidate** 和 **AC evidence**，不产生最终 AC verdict。
5. 所有 Batch 完成后执行 **Final Merge/Synthesis**，统一产生 ReviewOutput。
6. 最终 AC verdict 仅允许 `COVERED | NOT_FOUND | AT_RISK`。
7. Finding 使用稳定 fingerprint 去重。该 fingerprint 即 [ADR-009](./ADR-009-finding-continuity.md) 的 `finding_key`，用途为**批内去重 + 跨 Review 血缘键**两项。
8. 仍需保存 truncation manifest；未审查文件显式呈现，不得静默截断。
9. **不得由此创建第二套 Review Pipeline**。

## 后果与实施注记

- Merge/Synthesis 的机制（确定性证据聚合，或一次 synthesis LLM 调用）是 Phase 6 的实现细节，
  须在实现时以补充 ADR 记录；无论哪种，全量 AC 补齐规则不变——无任何证据的 AC 由
  `ReviewOutputValidator` 补为 `NOT_FOUND`。
- 任一 Batch 输出非法 JSON 且 format-repair 后仍失败 → 整个 Review = FAILED，
  不得输出部分成功报告（沿用"绝不生成成功空报告"纪律）。
- `ai_call_log` 逐调用记录并关联 review_id：一次 Review 产生 1..N 条调用日志，评测口径按 Review 聚合。
- fingerprint 需包含 path（保留大小写）+ 归一化位置 + 类别/AC 语义，见迁移矩阵 `FindingFingerprint` 行；
  完整组成规则与 `REQUIREMENT` / `CODE_QUALITY` 的分叉见 [ADR-009](./ADR-009-finding-continuity.md) §10–11。
