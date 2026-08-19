# ForgePilot V2 方案审核与文档修订

> 轻量任务（PRD-only）。审核对象：`docs/v2/` 六份规划文档（main @ `fe271bbf`）。
> 审核结论与证据固化在本任务 `research/v2-plan-review.md`。

## 背景

V2 最终候选方案已提交（`docs/v2/FINAL-CANDIDATE.md` 等），状态为"等待人工评审，禁止实施"。
本任务对方案做实库核查式审核：验证其 Legacy 事实与 KEEP/REWRITE 判断是否与真实代码一致、
文档之间是否自洽、实施前还缺哪些设计决定。

`08-16-forgepilot-upgrade` 是被 V2 绿地路线取代的旧架构升级父任务，本任务不挂其下。

## 范围

1. 核查方案声称的 Legacy 事实（规模、包分布、KEEP 白名单资产、降级理由）与实际代码是否一致。
2. 找出 docs/v2 六份文档之间的不一致，并**直接修复文档**（用户已授权："先修文档"）。
3. 识别 Phase 0 冻结前必须补的设计决定，整理成待裁决清单交用户回答；**不代替用户做决定**。
4. 不修改任何产品代码；不生成 V2 工程；不提交/推送（由用户决定）。

## 验收标准

- [ ] KEEP 白名单与关键降级理由逐项对过实际代码，结论记录在 research。
- [ ] LEGACY-MIGRATION-MATRIX 陈旧单元格（scm_connection、scm→review 直调、四层命名等）与 FINAL-CANDIDATE 对齐。
- [ ] PRD 断链修复；IMPLEMENTATION-PLAN 文件名大小写修复；Review 状态枚举跨文档统一。
- [ ] FINAL-CANDIDATE A4 Finding 状态图重画为与 Legacy `FindingLifecycle` 一致的清晰边列表。
- [ ] PRD "Blocking Open Questions" 如实列出批准前待裁决设计点。
- [ ] 审核报告落 `research/v2-plan-review.md`，含可行性结论与下一步建议。
