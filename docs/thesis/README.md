# 论文引用材料

本目录保存适合写入论文、能够独立复算**摘要汇总值**的材料。完整运维记录留在
Trellis 任务归档，正式三臂评测继续由不可变的 `evaluation/` 证据管理。摘要可复算不代表模型输出可重现；私有原文的进一步核实需要作者提供相应证据。

## 证据用途先分清

- **正式三臂评测**：预先登记的主结果，不被后续小样例替换。
- **敏感性分析**：事后分析语料提示与匹配口径的影响。
- **生产复验、PR 演练与稳定性复测**：对应各自的历史版本与运行条件。
- **实现建议验收**：最新三样例与限定浏览器检查，程序交互通过、内容目标部分达成；不是准确率或开发效率研究。

## 材料索引

| 材料 | 用途 |
|---|---|
| [PRODUCTION-REVALIDATION.md](./PRODUCTION-REVALIDATION.md) | 可直接改写或引用的“部署后调用链复验”小节，含方法、结果、解释和有效性威胁 |
| [production-revalidation-20260920.csv](./production-revalidation-20260920.csv) | 15 个 PR 复验的紧凑逐例数据，不含 Prompt、模型正文、Diff、知识原文或凭据 |
| [verify-production-revalidation.py](./verify-production-revalidation.py) | 仅用 Python 标准库复算论文中的全部汇总值 |
| [LIVE-EXERCISES.md](./LIVE-EXERCISES.md) | 2026-09-22 两次线上演练：演示仓在锚定校验器下重跑（跨轮抑制、闭环合并）与 Halo 24 个第三方真实 PR 重放（Finding 逐条人工判定） |
| [demo-rerun-20260922.csv](./demo-rerun-20260922.csv) | 演示仓重跑 15 个审查的逐例数据，含与上轮的 key 匹配、连续性与警告分类 |
| [halo-replay-20260922.csv](./halo-replay-20260922.csv) | Halo 重放 24 个 PR 的逐例数据，含上游 PR 号、关联 issue、AC 裁定与人工判定 |
| [verify-live-exercises.py](./verify-live-exercises.py) | 复算上述两组数据的全部汇总值并校验哈希 |
| [stability-20260925.csv](./stability-20260925.csv) | 2026-09-25 稳定性复测：同一批 PR 相邻两轮审查的逐 PR 对比（叙述见 LIVE-EXERCISES 第五节） |
| [verify-stability.py](./verify-stability.py) | 复算稳定性复测的全部汇总值并校验哈希 |
| [EVALUATION-SENSITIVITY.md](./EVALUATION-SENSITIVITY.md) | 正式三臂评测的效度分析：语料里的定位提示、三种匹配口径下的重算，以及一次去提示敏感性实验 |
| [evaluation-sensitivity-20260925.csv](./evaluation-sensitivity-20260925.csv) | 冻结结果与去提示实验的逐例匹配数（不透明编号，不含用例 ID） |
| [verify-evaluation-sensitivity.py](./verify-evaluation-sensitivity.py) | 复算上述全部汇总值，并断言原规则一列与冻结报告相等 |
| [GUIDANCE-ACCEPTANCE.md](./GUIDANCE-ACCEPTANCE.md) | 实现建议模块的功能验收与输出观察，含三例、旧版对照、浏览器范围、图注与局限 |
| [guidance-acceptance-20261003.csv](./guidance-acceptance-20261003.csv) | 四行公开摘要：一条历史基线与三条本轮样例，不含模型正文、知识原文或账号 |
| [verify-guidance-acceptance.py](./verify-guidance-acceptance.py) | 只读复算摘要表与差值，校验 CSV 哈希及小节中的表格一致性 |

复算命令：

```bash
python3 docs/thesis/verify-production-revalidation.py
python3 docs/thesis/verify-live-exercises.py
python3 docs/thesis/verify-stability.py
python3 docs/thesis/verify-evaluation-sensitivity.py
python3 docs/thesis/verify-guidance-acceptance.py
```

生产复验衡量运行完成率、审计关联、快照一致性、覆盖记账与时延；线上演练衡量校验器纠行、跨轮抑制、闭环合并、第三方代码上的 Finding 精确率与相邻两轮的稳定性。模型质量的
精确率、召回率和需求违规召回率必须引用正式三臂评测，各组证据不能合并分母；引用正式评测时同时说明效度分析里的定位提示与口径问题。
