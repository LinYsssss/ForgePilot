# 论文引用材料

本目录只保存适合写入论文、能够独立复算的结果摘要。完整运维记录留在
Trellis 任务归档，正式三臂评测继续由不可变的 `evaluation/` 证据管理。

| 材料 | 用途 |
|---|---|
| [PRODUCTION-REVALIDATION.md](./PRODUCTION-REVALIDATION.md) | 可直接改写或引用的“部署后调用链复验”小节，含方法、结果、解释和有效性威胁 |
| [production-revalidation-20260920.csv](./production-revalidation-20260920.csv) | 15 个 PR 复验的紧凑逐例数据，不含 Prompt、模型正文、Diff、知识原文或凭据 |
| [verify-production-revalidation.py](./verify-production-revalidation.py) | 仅用 Python 标准库复算论文中的全部汇总值 |
| [LIVE-EXERCISES.md](./LIVE-EXERCISES.md) | 2026-09-22 两次线上演练：演示仓在锚定校验器下重跑（跨轮抑制、闭环合并）与 Halo 24 个第三方真实 PR 重放（Finding 逐条人工判定） |
| [demo-rerun-20260922.csv](./demo-rerun-20260922.csv) | 演示仓重跑 15 个审查的逐例数据，含与上轮的 key 匹配、连续性与警告分类 |
| [halo-replay-20260922.csv](./halo-replay-20260922.csv) | Halo 重放 24 个 PR 的逐例数据，含上游 PR 号、关联 issue、AC 裁定与人工判定 |
| [verify-live-exercises.py](./verify-live-exercises.py) | 复算上述两组数据的全部汇总值并校验哈希 |

复算命令：

```bash
python3 docs/thesis/verify-production-revalidation.py
python3 docs/thesis/verify-live-exercises.py
```

生产复验衡量运行完成率、审计关联、快照一致性、覆盖记账与时延；线上演练衡量校验器纠行、跨轮抑制、闭环合并与第三方代码上的 Finding 精确率。模型质量的
精确率、召回率和需求违规召回率必须引用正式三臂评测，两组证据不能合并分母。
