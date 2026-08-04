# 修复批1：解除CI阻塞(F-01/F-02/F-13)

> 审计依据：`.trellis/tasks/archive/2026-08/08-03-project-reality-audit/audit-report.md` 第四节 F-01/F-02/F-13。CI 已连红 12 天（自 `7ff9265`，2026-07-23 起 5 次构建），两个作业各有独立根因。

## Goal

三项小改动让 CI 转绿：供应链门禁自建立以来首次真正运行；`verify` 作业走完全程；关闭已被 main 取代的 Draft PR #3。

## Requirements

### R1（F-01）修复 trivy-action 版本引用

- `.github/workflows/ci.yml:81` 的 `aquasecurity/trivy-action@0.28.0` 改为 `@v0.28.0`（该 action 的发布 tag 均带 `v` 前缀，`0.28.0` 不存在，`supply-chain` 作业 2 秒死在 Set up job）。
- 修复后**必须核实扫描有真实产出**（Maven/Python 依赖扫描 + 四个镜像 HIGH/CRITICAL 扫描的日志/报告存在），不能只看作业变绿——这个门禁从未运行过，可能一跑就报出真实漏洞，报出来的按严重度评估：CRITICAL 当场处理，其余记录后转入 r3 或独立任务。

### R2（F-02）隔离 sandbox-runner 测试的归档路径

- 根因：`SandboxRunnerApplicationTest` 未覆盖 `app.sandbox.archive-root`（默认 `/app/archives`，见 `SandboxRunnerApplication.java:60`），`WorkspaceArchiveResolver` 构造函数 `Files.createDirectories` 在非 root 的 GitHub runner 上抛 `AccessDeniedException: /app`。
- 修法：测试配置将 `app.sandbox.archive-root` 指向临时目录（`@TestPropertySource` 或等价方式，优先与现有测试风格一致）。
- 禁止的修法：给 CI runner 提权、mkdir /app、跳过该测试。

### R3（F-13）关闭 Draft PR #3

- `gh pr close 3 --comment "main 已以更好的实现修复此问题（TEST_DB_PASSWORD 28 字符+说明注释），本 PR 落后 main 111 提交，无可抢救内容，按审计 F-13 关闭"`。不合并、不重构其内容。

### R4 观察窗口（关键，容易漏）

- F-02 修复后，`verify` 作业将**首次**执行前端测试、前端构建、model-service 测试步骤（此前 12 天全部 skipped，从未在 CI 跑过）。推送后必须盯完整趟 CI：这些步骤可能暴露新问题。
- 已知风险预告：前端 `npm ci` 在 CI 用 Node 22（npm 10）可装 npmmirror lockfile，预计能过；若失败参照 F-06（属 r3 范围，可提前拉入本批处理，注明即可）。

## Out of Scope

- F-06 lockfile 换源（r3）；供应链扫描报出的非 CRITICAL 漏洞修复（记录转出）。

## Acceptance Criteria

- [ ] GitHub Actions 两个作业（`verify`、`supply-chain`）全绿。
- [ ] trivy 扫描日志证实真实执行：可见 Maven/Python/镜像扫描结果输出（截取存档到本任务目录）。
- [ ] CI 中前端测试 21 项、前端构建、model-service 9 项测试**首次**全部实际执行且通过。
- [ ] PR #3 已关闭且留有理由评论。
- [ ] 若供应链扫描报出漏洞：CRITICAL 已处理，其余已登记去向。

## Validation

```bash
gh run watch                                   # 盯最新一次 CI
gh run view --log | grep -A5 "Trivy"           # 确认扫描有产出
gh pr view 3 --json state                      # CLOSED
```
