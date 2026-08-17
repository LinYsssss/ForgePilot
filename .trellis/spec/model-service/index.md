# Model Service Development Guidelines (ARCHIVED)

> **已归档(2026-08-16,P0 架构清理)**:model-service 已从运行链路下线——backend 调用点、
> compose 编排、CI job 与验证脚本均已移除。`model-service/` 源码目录保留仅作历史参考,
> 本 spec 不再适用于新开发;frozen-contracts 第 8 条(/predict 契约)随之失效。

> `model-service/`:Python 3.12 + FastAPI 的风险分类微服务。单模块(`app/main.py`)+ 训练脚本(`model-service/scripts/train_model.py`)+ 9 个 pytest 用例(`tests/test_main.py`)。对 backend 暴露 `/predict`、`/model/status`、`/health`。

---

## Guidelines Index

| Guide | 内容 |
|-------|------|
| [Guidelines](./guidelines.md) | 单模块布局与 env 常量配置、joblib 安全姿态、有界输入、规则回退、reload 测试范式 |

`/predict` 的请求/响应字段是与 backend 的跨服务契约,见 `.trellis/spec/backend/frozen-contracts.md` 第 8 条。
