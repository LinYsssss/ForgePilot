# Model Service Guidelines

> 全部服务代码在 `app/main.py` 一个模块里。在真实功能增长之前保持单模块——不要预先拆 router/service/schema 目录。

---

## 配置 = 模块级 env 常量(及其测试后果)

配置项是模块加载时求值的常量:`MODEL_PATH` / `MODEL_VERSION` / `MODEL_RELOAD_ENABLED` / `MODEL_MAX_DIFF_CHARS`(`app/main.py` 顶部)。**后果:测试改环境变量后必须 `importlib.reload`**——`tests/test_main.py` 的 `load_app(**env)` 夹具即标准做法(设 env → reload 模块 → `load_model()`),新配置项照此补 reload 感知的用例,不要发明进程内开关。

## 安全姿态(三条都有代码注释背书,不可回退)

1. **`/model/reload` 默认 404 关闭**。joblib 底层是 pickle,**加载即执行任意代码**;开着重载端点等于给任何能访问本服务的人"换模型即 RCE"入口(`app/main.py` 的 `RELOAD_ENABLED` 注释)。只能经 `MODEL_RELOAD_ENABLED=true` 显式打开,且测试 `test_reload_is_disabled_by_default` 钉死默认值。模型文件只来自可信来源(镜像内置——Dockerfile 在构建期 `train_model.py` 生成,或受控卷)。
2. **输入有界,超限 422 而不是处理**。pydantic `Field(max_length=…)`:diff 200k 字符(`MODEL_MAX_DIFF_CHARS`)、路径 1024、changeType 64——上游是无上限的 diff 文本,不设界一个请求就能吃满内存(`test_oversized_diff_is_rejected_rather_than_processed`)。新入参一律带上限。
3. **错误只回稳定分类,不回细节**。`/model/status` 的 `error` 只有 `MODEL_FILE_MISSING` / `MODEL_LOAD_FAILED` 两个类别;绝不回 `str(exc)` 或绝对路径(泄露宿主目录结构)。响应字段集合被 `test_status_never_leaks_path_or_raw_exception` 用 `set(body) == {...}` 钉死——加字段先改该测试。

## 降级链:trained-model → fallback-rules

`/predict` 先试已加载模型,任何失败(未加载、预测异常)落到 `predict_with_rules` 规则引擎;响应 `source` 字段如实标注来源(`trained-model` / `fallback-rules`)。**规则回退是确定性兜底,永不移除**(对应 prompt 治理的 recall-first 兜底层,见 `.trellis/spec/backend/prompt-management.md` 规则二);模型加载失败必须静默降级而不是 5xx——backend 的 `HttpModelRiskClient` 把本服务当可选增强,失败只记 `ai_call_log`。

## 契约与依赖

- 响应字段 camelCase(`riskType`/`severity`/`confidence`/`modelVersion`/`source`),与 backend `HttpModelRiskClient` 的 record 逐字段对应;改字段=跨服务契约变更,两侧同批 + 契约测试(`.trellis/spec/guides/contract-testing.md`)。
- `requirements.txt` 全部 `==` 精确钉版(fastapi/starlette/uvicorn/scikit-learn/joblib/pytest/httpx);升级走 CI supply-chain 门禁核对(`.trellis/spec/backend/security-guidelines.md`)。

## 测试运行

```bash
cd model-service && python3 -m pytest tests/ -q          # 本机有 pytest 时(scripts/verify-local.sh 同口径)
# 本机无 pip 时容器化:
docker run --rm -v "$PWD/model-service":/ws -w /ws python:3.12-slim \
  sh -c "pip install -q -r requirements.txt && python -m pytest tests/ -q"
```
