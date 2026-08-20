# Evaluation

这里用于保存 ForgePilot V2 的版本化评测契约、评分器和可重算结果。Phase 1 只建立契约与确定性评分器骨架；真实 Review 评测从后续授权阶段开始。

论文核心对比：

1. `Diff + LLM`
2. `Diff + Requirement + Acceptance Criteria + LLM`
3. `Diff + Requirement + Acceptance Criteria + Project Knowledge + LLM`

至少报告 Precision、Recall、误报率、漏报率、需求违规召回率、AC verdict、结构失败率、Token 和耗时。沿用 RepoSage 的 development 26 / holdout 12 切分，Phase 8 配置冻结后首次运行 holdout，不得据 holdout 调参；旧语料与工具按迁移矩阵选择性引入。
