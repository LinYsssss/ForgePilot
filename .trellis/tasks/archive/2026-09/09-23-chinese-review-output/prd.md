# 审查输出改为中文

## Goal
Review 的 `explanation` / `suggestion`、需求质量检查的 AI 意见、一次性实现建议全部以简体中文输出；逐字引用的 `evidence` / `excerpt`、路径、枚举值、代码标识符保持原样。

## Requirements
- `ReviewPrompts` 三条指令加统一的语言规则；按 AGENTS.md，指令变更即 `VERSION` 递增到 `review-3`。
- 质量检查与实现建议的指令由「按需求语言作答」改为明确的简体中文；`QUALITY_VERSION` 不变（规则语义未变）。
- 三个哈希不受影响：`explanation`/`suggestion` 本就不入 hash。
- validator 警告只进日志与 summary，不面向用户，保持英文。
- ARCHITECTURE §4.1 记一句输出语言约定。

## Acceptance Criteria
- [ ] `ChangedFileBatcherTest`、`ReviewPipelineIntegrationTest`、`RequirementQualityTest`、`ImplementationGuidanceTest` 通过。
- [ ] 部署后重审一个 PR，`explanation` 为中文且 `prompt_version = review-3`。
