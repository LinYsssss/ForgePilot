# 本地提交分组

用户已授权按推荐方案顺序完成本地提交：第 1 组为 `3248ffd`，第 2 组为 `b56f670`；本文件属于第 3 组文档提交。本稿覆盖 review-5、全仓整理和 guidance-2，取代 09-28 任务里的旧分组草案。推送、部署和任务归档不在本次执行范围内。

## 前置状态

- 规划时基线：`9af6857`，当时分支 main。
- 后端当前工作树完整 verify：423 项通过，0 失败/错误/跳过。
- 前端：lint/typecheck、单工作进程 66 项测试及 build 通过；随后源码未变化。
- 冻结与订正已通过；不纳入 evaluation、迁移、原始实验数据、缓存或临时测试日志。
- 已创建普通分支 `fix/review-guidance-usability`；未创建 worktree。

## 1. fix(review): correct finding classification, hints and claimant permissions

- backend/src/main/java/com/forgepilot/review/FindingLifecycleService.java
- backend/src/main/java/com/forgepilot/review/ReviewDecisionService.java
- backend/src/main/java/com/forgepilot/review/ReviewOutputValidator.java
- backend/src/main/java/com/forgepilot/review/ReviewPrompts.java
- backend/src/main/java/com/forgepilot/review/ReviewViews.java
- backend/src/test/java/com/forgepilot/review/FindingLifecycleTest.java
- backend/src/test/java/com/forgepilot/review/ReviewDecisionTest.java
- backend/src/test/java/com/forgepilot/review/ReviewOutputValidatorTest.java
- backend/src/test/java/com/forgepilot/review/ReviewPipelineIntegrationTest.java
- frontend/src/features/review/FindingCard.vue
- frontend/src/features/review/ReviewDetailPage.vue
- frontend/src/features/review/api.ts

## 2. feat(requirement): make implementation guidance actionable and revision-safe

- backend/src/main/java/com/forgepilot/requirement/ImplementationGuidance.java
- backend/src/main/java/com/forgepilot/requirement/ImplementationGuidanceService.java
- backend/src/test/java/com/forgepilot/requirement/ImplementationGuidanceTest.java
- frontend/src/features/requirement/RequirementDetailPage.vue
- frontend/src/features/requirement/api.ts
- frontend/tests/requirement.spec.ts
- frontend/tests/journey.spec.ts

journey.spec.ts 同时含认领权限回归和 guidance-2 响应夹具，整文件放在第 2 组，不手工拆暂存区、不改写工作区。三组作为完整批次交付；验证结论对应最终工作树，不宣称各中间提交已分别重测。

## 3. docs: reconcile current contracts, operating guidance and validation records

- .github/workflows/ci.yml（仅注释）
- .trellis/spec/backend/directory-structure.md
- .trellis/spec/backend/quality-guidelines.md
- .trellis/spec/frontend/component-guidelines.md
- .trellis/spec/frontend/directory-structure.md
- .trellis/spec/frontend/hook-guidelines.md
- .trellis/spec/frontend/quality-guidelines.md
- .trellis/spec/frontend/state-management.md
- .trellis/spec/frontend/type-safety.md
- .trellis/spec/guides/cross-layer-thinking-guide.md
- README.md
- frontend/README.md
- backend/src/main/java/com/forgepilot/notification/DingTalkSender.java（仅注释）
- backend/src/main/java/com/forgepilot/notification/NotificationChannelController.java（仅注释）
- backend/src/main/java/com/forgepilot/scm/github/GitHubWebhookController.java（仅注释）
- backend/src/test/java/com/forgepilot/auth/AuthApiTest.java（仅注释）
- docs/deliverables/MANUAL.html
- docs/deliverables/OPERATIONS.html
- docs/deliverables/README.md
- docs/deliverables/SECURITY.html
- docs/deliverables/TEST-REPORT.html
- docs/deliverables/WALKTHROUGH.html
- docs/thesis/LIVE-EXERCISES.md
- docs/v2/API.md
- docs/v2/ARCHITECTURE.md
- docs/v2/DEFENSE-GUIDE.md
- docs/v2/PRD.md
- docs/v2/TESTING-GUIDE.md
- .trellis/tasks/09-28-review5-fixes/（计划、审计、验证与任务状态）
- .trellis/tasks/10-01-guidance-usability/（计划、验证、当前分组与任务状态）

## 确认与排除项

当前未发现本批次之外的未识别修改。两个任务目录中的早期草稿来自用户要求继续的工作，均保留历史与创建信息；只把旧提交草案指向本稿。

每条 commit message 末尾使用：

`Co-Authored-By: Claude Code <noreply@anthropic.com>`

本次确认只授权上述**本地提交**，不包含 push、远端合并、部署、真实模型样例或浏览器环境安装。任务归档与会话日志在工作提交之后另行处理；不 amend，不改历史。
