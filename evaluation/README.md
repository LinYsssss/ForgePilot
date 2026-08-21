# Evaluation

这里保存 ForgePilot V2 的版本化评测契约、确定性评分器和可重算的合成参考结果。Phase 1 不包含 Review Engine 或模型调用；`reference-runs/` 只验证评分口径，不能作为产品质量证据。

## Phase 1 文件

- `manifest.quick.json`：冻结的 12 个 development case 及真值，来源提交固定为 `96137dd3b43e14c5e8881c99688663afd979cf4e`。
- `case-sets/phase1-quick.json`：唯一允许在 Phase 1 执行的 case set。
- `cases/`：只包含上述 12 个 fixture；不包含任何保留集 fixture。
- `contracts/`：manifest、run、score report JSON Schema 和指标定义。
- `reference-runs/`：明确标记为 synthetic/reference 的规范化运行输入。
- `fixtures/phase1-reference-score.json`：确定性重算 snapshot。
- `tools/score.py`：仅使用 Python 标准库的 corpus validator、评分器、自测和 snapshot 比较器。
- `results/`：运行时输出目录，除 `.gitkeep` 外不入库。

## 验证

```bash
python3 evaluation/tools/score.py --validate-corpus --manifest evaluation/manifest.quick.json --case-set evaluation/case-sets/phase1-quick.json
python3 evaluation/tools/score.py --selftest
python3 evaluation/tools/score.py --manifest evaluation/manifest.quick.json --runs evaluation/reference-runs --out-dir /tmp/forgepilot-phase1-scores
python3 evaluation/tools/score.py --compare-report /tmp/forgepilot-phase1-scores/scores-reference.json evaluation/fixtures/phase1-reference-score.json
python3 evaluation/tools/score.py --guard-no-holdout --root evaluation
```

评分分别报告 Finding Precision/Recall、`falseReportRate`、miss rate、需求违规召回、AC verdict、结构失败、`notRun`、Token 与耗时；不存在综合质量分数。分母为零时输出 `null`，不会伪造为零。

论文正式实验仍比较：

1. `Diff + LLM`
2. `Diff + Requirement + Acceptance Criteria + LLM`
3. `Diff + Requirement + Acceptance Criteria + Project Knowledge + LLM`

保留集只能在 Phase 8 配置冻结后首次运行，且不得据其调参。本目录刻意不保存保留集 ID、fixture 或结果；`score.py` 只接受 `split=development` 的 Phase 1 quick corpus。
