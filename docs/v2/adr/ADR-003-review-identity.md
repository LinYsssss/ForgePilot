# ADR-003 Review Identity：业务键收敛为 (pull_request_id, head_sha)

- 状态：已接受（2026-08-19，用户裁决）
- 关联：[ARCHITECTURE.md](../ARCHITECTURE.md) §3.1 · §2.1、[PRD.md](../PRD.md) §6 P4

## 背景

原候选版 Review 唯一键为 `(pull_request_id, head_sha, engine_version)`：引擎升级后同一 head SHA
可产生第二条 Review，与"同一 head SHA 只允许一次终局 Decision"冲突，需要额外的跨 engine_version
部分唯一索引来堵漏。

## 决策

1. Production Review 的唯一业务键修改为 **`UNIQUE(pull_request_id, head_sha)`**。
2. engine_version、prompt_version、model 仅作为**审计元数据**列，不参与 Review Identity。
3. 同一个 head SHA 在生产环境**不因 Engine 升级自动重新 Review**。
4. Engine/Prompt/Model 多版本对比属于 **evaluation**，不进入生产 Review 数据模型。
5. 删除"跨 engine_version 终局 Decision partial unique index"的必要性——
   Decision 保存在 Review 上，Review 唯一即 Decision 唯一。

## 后果与实施注记

- 人工重试**复用同一条 Review 行**（停滞的 PENDING/RUNNING 或 FAILED 重置后重跑），
  不插入第二行；历史尝试通过 `ai_call_log` 与状态时间戳追溯。
- "修复后按新 head SHA 产生新 Review、保留前后结果"的规则不受影响。
- REQUEST_CHANGES 后必须有新 head SHA 才能再次产生终局 Decision 的规则不变，且由唯一键天然保证。
- 评测环境多版本对比使用 evaluation 设施（Phase 8），不写生产 `review` 表。
