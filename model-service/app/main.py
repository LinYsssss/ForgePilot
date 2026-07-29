import os
from pathlib import Path
from typing import Any

import joblib
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


DEFAULT_MODEL_PATH = Path(__file__).resolve().parents[1] / "models" / "risk_classifier.joblib"
MODEL_PATH = Path(os.getenv("MODEL_PATH", str(DEFAULT_MODEL_PATH)))
MODEL_VERSION = os.getenv("MODEL_VERSION", "fallback-rules-v1")
# joblib 底层是 pickle,加载即执行任意代码。模型文件必须来自可信来源(镜像内置或受控卷),
# 因此重载端点默认关闭:开着它等于给任何能访问本服务的人一个"换模型即执行"的入口。
RELOAD_ENABLED = os.getenv("MODEL_RELOAD_ENABLED", "false").lower() == "true"
# 上游传来的是 diff 文本,没有上限时一个超大请求就能把内存和 CPU 吃满。
MAX_DIFF_CHARS = int(os.getenv("MODEL_MAX_DIFF_CHARS", "200000"))
MAX_PATH_CHARS = 1024

app = FastAPI(title="Code Risk Model Service", version="0.2.0")

_model_bundle: dict[str, Any] | None = None
_model_error: str | None = None


class PredictRequest(BaseModel):
    diffText: str = Field(default="", max_length=MAX_DIFF_CHARS,
                          description="Git diff or code snippet to classify")
    filePath: str | None = Field(default=None, max_length=MAX_PATH_CHARS)
    changeType: str | None = Field(default=None, max_length=64)


class PredictResponse(BaseModel):
    riskType: str
    severity: str
    confidence: float
    modelVersion: str
    source: str


class ModelStatusResponse(BaseModel):
    status: str
    modelLoaded: bool
    modelVersion: str
    # 只回一个稳定的原因分类。此前这里返回 str(exc) 与模型绝对路径,
    # 等于把宿主目录结构和底层异常细节交给任何能调用该端点的人。
    error: str | None = None


def load_model() -> None:
    global _model_bundle, _model_error
    if not MODEL_PATH.exists():
        _model_bundle = None
        _model_error = "MODEL_FILE_MISSING"
        return
    try:
        loaded = joblib.load(MODEL_PATH)
        if isinstance(loaded, dict) and "pipeline" in loaded:
            _model_bundle = loaded
        else:
            _model_bundle = {"pipeline": loaded, "modelVersion": MODEL_VERSION}
        _model_error = None
    except Exception:
        _model_bundle = None
        # 细节只进服务端日志,不进响应体
        _model_error = "MODEL_LOAD_FAILED"


@app.on_event("startup")
def startup() -> None:
    load_model()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/model/status", response_model=ModelStatusResponse)
def model_status() -> ModelStatusResponse:
    return ModelStatusResponse(
        status="UP",
        modelLoaded=_model_bundle is not None,
        modelVersion=current_model_version(),
        error=_model_error,
    )


@app.post("/model/reload", response_model=ModelStatusResponse)
def reload_model() -> ModelStatusResponse:
    if not RELOAD_ENABLED:
        raise HTTPException(status_code=404, detail="Not Found")
    load_model()
    return model_status()


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    if _model_bundle is not None:
        predicted = predict_with_model(request)
        if predicted is not None:
            return predicted
    return predict_with_rules(request)


def predict_with_model(request: PredictRequest) -> PredictResponse | None:
    pipeline = _model_bundle.get("pipeline") if _model_bundle else None
    if pipeline is None:
        return None
    text = feature_text(request)
    try:
        label = str(pipeline.predict([text])[0])
        confidence = confidence_for(pipeline, text, label)
        return PredictResponse(
            riskType=label,
            severity=severity_for(label),
            confidence=confidence,
            modelVersion=current_model_version(),
            source="trained-model",
        )
    except Exception:
        return None


def confidence_for(pipeline: Any, text: str, label: str) -> float:
    if not hasattr(pipeline, "predict_proba"):
        return 0.65
    probabilities = pipeline.predict_proba([text])[0]
    classes = list(getattr(pipeline, "classes_", []))
    if label in classes:
        return round(float(probabilities[classes.index(label)]), 4)
    return round(float(max(probabilities)), 4)


def predict_with_rules(request: PredictRequest) -> PredictResponse:
    text = feature_text(request).lower()
    if "/admin" in text and "preauthorize" not in text and "hasrole" not in text:
        return response("AUTH_RISK", "HIGH", 0.78, "fallback-rules")
    if "select" in text and ("+ keyword" in text or "\" +" in text or "concat" in text):
        return response("SQL_INJECTION", "HIGH", 0.76, "fallback-rules")
    if ("findbyid" in text or "getbyid" in text) and ".get" in text and "null" not in text:
        return response("NULL_POINTER", "MEDIUM", 0.68, "fallback-rules")
    if "paystatus" in text and "paid" in text:
        return response("BUSINESS_RULE_RISK", "HIGH", 0.82, "fallback-rules")
    if "@transactional" not in text and ("save(" in text and "update" in text):
        return response("TRANSACTION_RISK", "MEDIUM", 0.61, "fallback-rules")
    return response("UNKNOWN", "LOW", 0.40, "fallback-rules")


def response(risk_type: str, severity: str, confidence: float, source: str) -> PredictResponse:
    return PredictResponse(
        riskType=risk_type,
        severity=severity,
        confidence=confidence,
        modelVersion=current_model_version(),
        source=source,
    )


def feature_text(request: PredictRequest) -> str:
    parts = [
        request.filePath or "",
        request.changeType or "",
        request.diffText or "",
    ]
    return "\n".join(parts)


def severity_for(risk_type: str) -> str:
    return {
        "AUTH_RISK": "HIGH",
        "SQL_INJECTION": "HIGH",
        "BUSINESS_RULE_RISK": "HIGH",
        "TRANSACTION_RISK": "HIGH",
        "NULL_POINTER": "MEDIUM",
        "PERFORMANCE_RISK": "MEDIUM",
        "UNKNOWN": "LOW",
    }.get(risk_type, "LOW")


def current_model_version() -> str:
    if _model_bundle and _model_bundle.get("modelVersion"):
        return str(_model_bundle["modelVersion"])
    return MODEL_VERSION
