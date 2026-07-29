import importlib
import os
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def load_app(**env):
    """按给定环境变量重新导入模块 —— 开关是模块级常量,必须重载才生效。"""
    for key, value in env.items():
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value
    import app.main as main

    importlib.reload(main)
    main.load_model()
    return main


@pytest.fixture
def main_module():
    return load_app(MODEL_RELOAD_ENABLED=None)


def test_health_is_up(main_module):
    with TestClient(main_module.app) as client:
        assert client.get("/health").json() == {"status": "UP"}


def test_status_never_leaks_path_or_raw_exception(main_module):
    with TestClient(main_module.app) as client:
        body = client.get("/model/status").json()

    # 绝对路径会暴露宿主目录结构,原始异常会暴露实现细节
    assert "modelPath" not in body
    assert set(body) == {"status", "modelLoaded", "modelVersion", "error"}
    if body["error"] is not None:
        assert body["error"] in {"MODEL_FILE_MISSING", "MODEL_LOAD_FAILED"}
        assert "/" not in body["error"]


def test_missing_model_reports_a_category_not_a_path(tmp_path):
    main = load_app(MODEL_PATH=str(tmp_path / "does-not-exist.joblib"))
    with TestClient(main.app) as client:
        body = client.get("/model/status").json()

    assert body["modelLoaded"] is False
    assert body["error"] == "MODEL_FILE_MISSING"
    assert str(tmp_path) not in str(body)


def test_reload_is_disabled_by_default(main_module):
    with TestClient(main_module.app) as client:
        # joblib 加载即执行 pickle,重载端点默认不可用
        assert client.post("/model/reload").status_code == 404


def test_reload_can_be_enabled_explicitly():
    main = load_app(MODEL_RELOAD_ENABLED="true")
    try:
        with TestClient(main.app) as client:
            assert client.post("/model/reload").status_code == 200
    finally:
        load_app(MODEL_RELOAD_ENABLED=None)


def test_predict_returns_a_verdict(main_module):
    with TestClient(main_module.app) as client:
        body = client.post("/predict", json={
            "filePath": "AdminOrderController.java",
            "diffText": '+@PostMapping("/admin/orders/{id}/force-ship")',
        }).json()

    assert body["riskType"]
    assert body["severity"] in {"HIGH", "MEDIUM", "LOW"}
    assert 0.0 <= body["confidence"] <= 1.0
    assert body["source"] in {"trained-model", "fallback-rules"}


def test_oversized_diff_is_rejected_rather_than_processed(main_module):
    with TestClient(main_module.app) as client:
        response = client.post("/predict", json={
            "diffText": "x" * (main_module.MAX_DIFF_CHARS + 1),
        })

    # 没有上限时,一个超大请求就能把内存和 CPU 吃满
    assert response.status_code == 422


def test_oversized_path_and_change_type_are_rejected(main_module):
    with TestClient(main_module.app) as client:
        assert client.post("/predict", json={"filePath": "a" * 2000}).status_code == 422
        assert client.post("/predict", json={"changeType": "b" * 100}).status_code == 422


def test_rules_fallback_flags_unprotected_admin_endpoint(main_module):
    verdict = main_module.predict_with_rules(main_module.PredictRequest(
        filePath="AdminController.java",
        diffText='+@PostMapping("/admin/force")',
    ))

    assert verdict.riskType == "AUTH_RISK"
    assert verdict.severity == "HIGH"
    assert verdict.source == "fallback-rules"
