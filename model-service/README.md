# Code Risk Model Service

轻量级代码风险分类服务，用于演示“自训练模型 + AI 审查”的辅助能力。

## 1. 能力

- 使用 TF-IDF + LogisticRegression 训练风险分类器。
- 预测风险类型、严重程度和置信度。
- 模型文件不存在时自动回退到规则识别。
- 提供模型状态查询和热重载接口。

## 2. 训练模型

```text
cd model-service
python scripts/train_model.py
```

默认读取：

```text
data/risk_samples.csv
```

默认输出：

```text
models/risk_classifier.joblib
```

指定版本：

```text
python scripts/train_model.py --version demo-v1
```

## 3. 启动服务

```text
cd model-service
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 4. 接口

健康检查：

```text
GET /health
```

模型状态：

```text
GET /model/status
```

预测：

```http
POST /predict
Content-Type: application/json

{
  "filePath": "src/main/java/AdminOrderController.java",
  "changeType": "MODIFIED",
  "diffText": "+@PostMapping(\"/admin/orders/{id}/force-ship\")\n+public void forceShip(Long id) { orderService.forceShip(id); }"
}
```

响应：

```json
{
  "riskType": "AUTH_RISK",
  "severity": "HIGH",
  "confidence": 0.82,
  "modelVersion": "demo-v1",
  "source": "trained-model"
}
```

## 5. 说明

这不是替代大模型，而是作为辅助分类器：

- 大模型负责理解代码语义和生成审查报告。
- 轻量模型负责快速风险分类、排序或兜底判断。
- 后续可以把人工反馈数据导出成训练样本继续迭代。
