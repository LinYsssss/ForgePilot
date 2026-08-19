# ForgePilot V2 方案审核、决策落地与文档收敛

> 轻量任务（PRD-only）。基线：main @ `fe271bbf`。

## 背景

V2 方案已提交但状态为"等待人工评审"。本任务做实库核查式审核，把争议决策落为 ADR，
并解决审核中暴露的**文档重复与漂移**问题。

## 范围

1. 核查方案声称的 Legacy 事实与实际代码是否一致。
2. 识别设计缺口，交用户裁决后写成正式 ADR。
3. 修订所有冲突表述，复检 14 表 / 依赖 / 业务一致性。
4. 收敛文档结构，建立单一事实源纪律。
5. 不修改业务代码；不开始 Phase 1 编码。

## 完成情况

- [x] Legacy 事实核查：规模、KEEP 白名单 7 项、38 例语料、DiffSplitter/FindingDeduplicator 降级理由，逐项对过真实代码，全部属实。
- [x] 5 个设计缺口经用户裁决 → ADR-001..005。
- [x] 外部 AI 审查意见评估：采纳 2 条（跨项目一致性、PR 关联）、改法采纳 1 条（附件挪 Phase 4）、驳回 3 条（Schema 契约文档、评测阈值、权限矩阵时机）→ 新增 ADR-006/007 + Review Decision 补 actor/time/comment。
- [x] 发现并解决根本问题：PRD 与 FINAL-CANDIDATE 系统性重复（14 表、依赖规则、MVP 范围各两份），依赖规则已出现格式漂移。
- [x] 文档收敛：15 份 1401 行 → 5 份规范 + 7 ADR + 3 归档；FINAL-CANDIDATE 拆分为 PRD（产品）+ ARCHITECTURE（技术）后删除。
- [x] 补齐缺失规范：角色权限矩阵（12×3）、命名约定、运行边界参数表。
- [x] 全局校验：链接完整性、单一事实源、14 表计数、ADR 交叉引用章节号，全部通过。

## 产出

| 路径 | 说明 |
|---|---|
| `docs/v2/PRD.md` | 产品权威（重写） |
| `docs/v2/ARCHITECTURE.md` | 技术权威（新建，取代 FINAL-CANDIDATE） |
| `docs/v2/IMPLEMENTATION-PLAN.md` | Phase 0 标记冻结 |
| `docs/v2/adr/ADR-001..007` | 7 条决策 |
| `docs/v2/archive/` | 过程记录（Legacy 审计、交叉审查、R2 报告） |
| 本任务 `research/v2-plan-review.md` | 审核证据与判断依据 |

## 状态

Phase 0 已冻结，Phase 1 可开始。改动**未提交**，等用户决定。业务代码零改动。
