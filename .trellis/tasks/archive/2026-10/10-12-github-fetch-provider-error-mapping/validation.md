# GitHub 权威读取错误映射 验证记录（2026-10-14）

## 状态

已实现（提交 `30941a3`）、推送（`fix/review-audit-fixes`）并通过 CI 全量验证，任务归档。本地从未编译或执行；CI 是第一次真实执行。未部署。

## 后端

CI run 38555642951（2026-10-14），backend verify job 115824136668：

```text
Tests run: 430, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

相关类：`GitHubClientGuardTest` 31 项（含新增的 429/500/404 三种映射与「文件列表非数组」四个用例）、`GitHubWebhookIngestionTest` 12 项、`GitLabWebhookIngestionTest` 7 项，均零失败。`merge()` 路径未改动，`ScmMergeTest` 同批通过。

## 其余门（同一 run）

frontend 18 个测试文件全过；evaluation 契约三项通过；两次空库 Compose 冷启动通过。

## 边界

- 403 二级限流仍映射 502 `provider_error`（已记为可选优化，见 `10-14-review-outstanding-followups` 第 10 项）。
- 文档：`.trellis/spec/backend/error-handling.md`（提交 `30941a3`）、`docs/v2/API.md` webhook 行（提交 `c0604cb`）。
