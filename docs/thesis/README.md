# 论文引用材料

本目录只保存适合写入论文、能够独立复算的结果摘要。完整运维记录留在
Trellis 任务归档，正式三臂评测继续由不可变的 `evaluation/` 证据管理。

| 材料 | 用途 |
|---|---|
| [PRODUCTION-REVALIDATION.md](./PRODUCTION-REVALIDATION.md) | 可直接改写或引用的“部署后调用链复验”小节，含方法、结果、解释和有效性威胁 |
| [production-revalidation-20260920.csv](./production-revalidation-20260920.csv) | 15 个 PR 复验的紧凑逐例数据，不含 Prompt、模型正文、Diff、知识原文或凭据 |
| [verify-production-revalidation.py](./verify-production-revalidation.py) | 仅用 Python 标准库复算论文中的全部汇总值 |

复算命令：

```bash
python3 docs/thesis/verify-production-revalidation.py
```

生产复验衡量运行完成率、审计关联、快照一致性、覆盖记账与时延。模型质量的
精确率、召回率和需求违规召回率必须引用正式三臂评测，两组证据不能合并分母。
