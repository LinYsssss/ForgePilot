# 评审活动 NPE 修复 验证记录（2026-10-14）

## 状态

已实现（提交 `6bdb750`）、推送（分支 `fix/review-audit-fixes`）并通过 CI 全量验证，任务归档。本机无 JDK 21 与 Docker，本地从未编译或执行；CI 是第一次真实执行。未部署，未触发真实模型调用。

## 后端

CI run 38555642951（2026-10-14），backend verify job 115824136668：

```text
Tests run: 430, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

相关类：`ReviewActivityTest` 14 项（含新增的软删需求用例）、`ReviewDecisionTest` 16 项，均零失败；ArchUnit 与真实 PostgreSQL/pgvector 约束测试同批通过。未跑第二次定向重跑。

## 其余门（同一 run）

frontend 18 个测试文件全过；evaluation 契约（CORPUS VALID、SELFTEST OK 30 项、HOLDOUT GUARD OK）通过；两次空库 Compose 冷启动通过（pgvector 0.8.6，21 张表）。

## 边界

- 未部署：部署身份仍是 `54e2953`，本分支含未部署改动。
- 文档改动 `docs/v2/API.md` 软删一节在提交 `c0604cb`。
- 通过的自动化套件不覆盖浏览器人工验收，其范围不受本次改动影响。
