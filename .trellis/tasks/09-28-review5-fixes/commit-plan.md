# 待确认的提交分组

> 本稿保留 review-5 阶段的草案。当前完整工作区还包含 guidance-2，提交时以 [统一分组](../10-01-guidance-usability/commit-plan.md) 为准，不能同时执行两份草案。

尚未执行 git add/commit/push。当前在 main；获准后先建普通分支，再按以下分组提交。共享文件不拆 hunk；这些提交应作为整轮一起部署。

## 1. fix(review): restore explicit finding types and count finding corrections

- backend/src/main/java/com/forgepilot/review/ReviewPrompts.java
- backend/src/main/java/com/forgepilot/review/ReviewOutputValidator.java
- backend/src/test/java/com/forgepilot/review/ReviewOutputValidatorTest.java
- backend/src/test/java/com/forgepilot/review/ReviewPipelineIntegrationTest.java

## 2. fix(review): refine missing-finding hints and claimant permissions

- backend/src/main/java/com/forgepilot/review/ReviewDecisionService.java
- backend/src/main/java/com/forgepilot/review/FindingLifecycleService.java
- backend/src/main/java/com/forgepilot/review/ReviewViews.java
- backend/src/test/java/com/forgepilot/review/ReviewDecisionTest.java
- backend/src/test/java/com/forgepilot/review/FindingLifecycleTest.java
- frontend/src/features/review/FindingCard.vue
- frontend/src/features/review/ReviewDetailPage.vue
- frontend/src/features/review/api.ts
- frontend/tests/journey.spec.ts

## 3. docs: sync review-5 contracts and validation evidence

- README.md
- frontend/README.md
- .github/workflows/ci.yml（仅注释）
- .trellis/spec/backend/{directory-structure,quality-guidelines}.md
- .trellis/spec/frontend/{directory-structure,state-management,quality-guidelines,hook-guidelines,type-safety,component-guidelines}.md
- .trellis/spec/guides/cross-layer-thinking-guide.md（删除重复节）
- backend/src/test/java/com/forgepilot/auth/AuthApiTest.java（仅注释乱码）
- backend/src/main/java/com/forgepilot/notification/{DingTalkSender,NotificationChannelController}.java（仅文档引用）
- backend/src/main/java/com/forgepilot/scm/github/GitHubWebhookController.java（仅事务时序注释）
- docs/v2/DEFENSE-GUIDE.md
- docs/v2/ARCHITECTURE.md
- docs/v2/PRD.md
- docs/v2/API.md
- docs/v2/TESTING-GUIDE.md
- docs/thesis/LIVE-EXERCISES.md
- docs/deliverables/MANUAL.html
- docs/deliverables/WALKTHROUGH.html
- docs/deliverables/TEST-REPORT.html
- docs/deliverables/OPERATIONS.html
- docs/deliverables/SECURITY.html
- docs/deliverables/README.md
- .trellis/tasks/09-28-review5-fixes/（prd/design/implement、上下文清单、task.json、验证与此分组记录）

原工作区只有该任务目录未跟踪；它来自被要求继续的会话，本次保留身份与创建信息、修订计划并补齐设计。没有发现其他未识别改动，也不纳入临时日志或构建产物。

每个提交末尾按约定添加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。此分组不授权推送、部署、真实模型复测；任务归档和会话日志在代码提交后再处理。
