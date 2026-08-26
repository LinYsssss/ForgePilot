# Evaluation

这里保存 ForgePilot 的版本化评测契约、确定性评分器和可重算的合成参考结果。

三臂正式评测已完成，holdout 按约定在配置冻结后只运行一次。正式语料、配置冻结、一次性 ledger 与原始输出是**不可变证据**，位于 Git 忽略的 `private-formal-corpus/` 与 `results/`，由 [`formal/README.md`](formal/README.md) 描述，**不得删除、覆盖或重跑**。

本目录下入库的其余内容是**评分口径的可复现基础设施**：契约 schema、确定性评分器、12 例 development quick corpus 与合成参考运行。`reference-runs/` 只验证评分口径，不能作为产品质量证据。

## 评分口径与语料

CI 每次运行的就是这一组契约检查，它保护的是评分口径本身。

- `manifest.quick.json`：冻结的 12 个 development case 及真值，来源提交固定为 `96137dd3b43e14c5e8881c99688663afd979cf4e`。
- `case-sets/phase1-quick.json`：quick corpus 唯一允许执行的 case set。
- `cases/`：只包含上述 12 个 fixture；不包含任何保留集 fixture。
- `contracts/`：manifest、run、score report 的 JSON Schema 与指标定义。
- `reference-runs/`：明确标记为 synthetic/reference 的规范化运行输入。
- `fixtures/phase1-reference-score.json`：确定性重算 snapshot。
- `tools/score.py`：仅使用 Python 标准库的 corpus validator、评分器、自测与 snapshot 比较器。
- `results/`：运行时输出目录，除 `.gitkeep` 外不入库。

```bash
python3 evaluation/tools/score.py --validate-corpus --manifest evaluation/manifest.quick.json --case-set evaluation/case-sets/phase1-quick.json
python3 evaluation/tools/score.py --selftest
python3 evaluation/tools/score.py --manifest evaluation/manifest.quick.json --runs evaluation/reference-runs --out-dir /tmp/forgepilot-scores
python3 evaluation/tools/score.py --compare-report /tmp/forgepilot-scores/scores-reference.json evaluation/fixtures/phase1-reference-score.json
python3 evaluation/tools/score.py --guard-no-holdout --root evaluation
```

## development 三臂试跑

`tools/run_development.py` 是离线评测适配器，**不是第二个生产 Review Engine**。它固定读取上述 12 个 development case，不接受 manifest/case-set 路径参数；三臂只改变暴露给模型的上下文，输出仍使用既有 run contract 并立即交给确定性评分器。OpenAI-compatible structured outputs 请求按[官方文档](https://developers.openai.com/api/docs/guides/structured-outputs)显式设置 `json_schema.strict=true`，且不会把真值、nonFinding 或 selectionReason 放进 prompt。

```bash
python3 -m unittest evaluation/tools/test_run_development.py
python3 evaluation/tools/run_development.py --all-arms --dry-run

export FORGEPILOT_EVAL_MODEL='<frozen model id>'
python3 evaluation/tools/run_development.py --all-arms --out-dir evaluation/results/development-<run-id>
```

模型运行还需要进程环境中的 `OPENAI_API_KEY`；自建兼容端点可用 `OPENAI_BASE_URL` 覆盖。runner 对 429/5xx/网络错误最多重试一次，逐 case 如实记录 `PROVIDER` 或 `STRUCTURE` 失败，**不会把失败改写为空 Findings**。

评分分别报告 Finding Precision/Recall、`falseReportRate`、miss rate、需求违规召回、AC verdict、结构失败、`notRun`、Token 与耗时；**不存在综合质量分数**。分母为零时输出 `null`，不会伪造为零。

## 三臂对照

1. `Diff + LLM`
2. `Diff + Requirement + Acceptance Criteria + LLM`
3. `Diff + Requirement + Acceptance Criteria + Project Knowledge + LLM`

这三臂比较的是**上下文构成**，而评测 runner 自行组装上下文、不调用运行中的应用——因此结论是「把需求与项目知识放进上下文有用」，不是「某条检索管线有效」。论文表述须保持这个区分。

保留集不得据其调参。本目录刻意不保存保留集 ID、fixture 或结果；`score.py` 只接受 `split=development` 的 quick corpus。

## 正式实验

正式 26+12 语料、配置冻结、一次性 ledger、三臂运行与 Wilson 区间报告由 [`formal/README.md`](formal/README.md) 描述。正式工具不会放宽 quick scorer 的默认边界；私有 corpus 与运行输出继续位于 Git 忽略目录，冻结证据只保存非秘密配置和内容哈希。
