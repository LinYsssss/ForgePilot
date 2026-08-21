# ForgePilot Evaluation Metrics v1

评分器使用 `forgepilot-deterministic-match-v1`：路径仅统一斜杠并移除前导 `./`，仍区分大小写；类别必须命中标注类别或版本化 alias；预测与标注行区间必须相交。两侧按路径、范围、类别、原始序号排序后执行贪心 1:1 匹配，每条 Finding 最多参与一次。

| 指标 | 定义 |
| --- | --- |
| `precision` | `TP / (TP + FP)`。 |
| `recall` | `TP / (TP + FN)`。 |
| `falseReportRate` | `FP / (TP + FP)`，即 `1 - precision`；不是统计学的 `FP/(FP+TN)`。 |
| `missRate` | `FN / (TP + FN)`，即 `1 - recall`。 |
| `requirementViolationRecall` | 仅统计真值中显式 `findingType=REQUIREMENT` 的 Recall；评分时不从显示类别推断。 |
| `acVerdict` | `COVERED / NOT_FOUND / AT_RISK` 的 exact accuracy，以及每类 one-vs-rest Precision/Recall。缺失预测保持缺失。 |
| `structureFailureRate` | `failureKind=STRUCTURE` 的 attempted cases / attempted cases；`NOT_RUN` 不进入分母。 |
| `usage` | 完成 case 的 input/output token、latency 总量和均值；任一缺失时相应指标为 `null`，不补零。 |
| `notRun` | 缺少 case 输出或显式 `NOT_RUN` 的数量、ID 和原因；不进入 Finding 或结构失败分母。 |

`COMPLETED` 且 findings 为空是有效空结果。显式 `FAILED/STRUCTURE` 是一次结构失败尝试，不等于 `notRun`。所有零分母输出 JSON `null`。指标彼此独立，不合成单一总分。

