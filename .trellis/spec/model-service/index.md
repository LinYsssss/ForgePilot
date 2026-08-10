# Model Service Development Guidelines

> `model-service/`:Python 3.12 + FastAPI 的风险分类微服务。单模块(`app/main.py`)+ 训练脚本(`model-service/scripts/train_model.py`)+ 9 个 pytest 用例(`tests/test_main.py`)。对 backend 暴露 `/predict`、`/model/status`、`/health`。

---

## Guidelines Index

| Guide | 内容 |
|-------|------|
| [Guidelines](./guidelines.md) | 单模块布局与 env 常量配置、joblib 安全姿态、有界输入、规则回退、reload 测试范式 |

`/predict` 的请求/响应字段是与 backend 的跨服务契约,见 `.trellis/spec/backend/frozen-contracts.md` 第 8 条。
