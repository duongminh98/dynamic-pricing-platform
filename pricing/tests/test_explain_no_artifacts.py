"""Tests for app.pricing_engine.explain — functions that don't need model artifacts.

Covers _direction, _extract_model_and_features, and explain() with mocked models.
"""
from __future__ import annotations

import sys
import types
from unittest.mock import MagicMock, patch

import numpy as np
import pandas as pd
import pytest

# Inject a fake ``shap`` module so that ``import shap`` inside explain() works
# even on CI runners where shap is not installed.
if "shap" not in sys.modules:
    _fake_shap = types.ModuleType("shap")
    _fake_shap.TreeExplainer = MagicMock()
    _fake_shap.LinearExplainer = MagicMock()
    sys.modules["shap"] = _fake_shap

from app.pricing_engine.explain import (
    _direction,
    _extract_model_and_features,
    explain,
    LABEL_EN,
)


def test_direction_positive():
    assert _direction(0.5) == "increase"


def test_direction_zero():
    assert _direction(0.0) == "increase"


def test_direction_negative():
    assert _direction(-0.3) == "decrease"


def test_label_en_contains_known_features():
    assert "age" in LABEL_EN
    assert "coverage_amount_vnd" in LABEL_EN
    assert LABEL_EN["age"] == "Age"
    assert LABEL_EN["coverage_amount_vnd"] == "Coverage amount"


def test_extract_model_and_features_lgbm():
    model = MagicMock()
    model.feature_name_ = ["age", "coverage_amount_vnd"]
    est, names = _extract_model_and_features(model)
    assert est is model
    assert names == ["age", "coverage_amount_vnd"]



def test_extract_model_and_features_freqsev_composite_prefers_frequency_model():
    freq_model = MagicMock()
    freq_model.feature_name_ = ["age", "claim_count_36m_prior"]
    sev_model = MagicMock()
    sev_model.feature_name_ = ["age", "avg_incurred_36m_prior"]

    est, names = _extract_model_and_features({"freq": freq_model, "sev": sev_model})

    assert est is freq_model
    assert names == ["age", "claim_count_36m_prior"]

def test_explain_freqsev_composite_returns_frequency_and_severity_components():
    class LocalFakeLGBMRegressor:
        feature_name_ = ["age", "coverage_amount_vnd", "deductible_vnd"]
        def predict(self, df):
            return np.array([500_000.0])

    freq_model = LocalFakeLGBMRegressor()
    freq_model.feature_name_ = ["age", "coverage_amount_vnd", "deductible_vnd"]
    sev_model = LocalFakeLGBMRegressor()
    sev_model.feature_name_ = ["age", "coverage_amount_vnd", "deductible_vnd"]
    feature_df = pd.DataFrame({"age": [30], "coverage_amount_vnd": [100_000_000], "deductible_vnd": [0]})

    mock_explainer = MagicMock()
    mock_explainer.shap_values.return_value = np.array([[0.3, -0.5, 0.1]])

    with patch("app.pricing_engine.explain._get_tree_explainer", return_value=mock_explainer), \
         patch.dict(sys.modules, {"shap": sys.modules["shap"]}):
        result = explain({"freq": freq_model, "sev": sev_model}, feature_df)

    assert result["available"] is True
    assert result["method"] == "freqsev_components"
    assert result["items"]
    assert result["components"]["frequency"]["available"] is True
    assert result["components"]["severity"]["available"] is True
    assert result["components"]["frequency"]["method"] == "freqsev_frequency_tree_shap"
    assert result["components"]["severity"]["method"] == "freqsev_severity_tree_shap"


def test_explain_component_exclusions_hide_product_terms_from_severity():
    class LocalFakeLGBMRegressor:
        feature_name_ = ["coverage_amount_vnd", "product_id", "age", "bmi", "smoker"]

        def predict(self, df):
            return np.array([500_000.0])

    freq_model = LocalFakeLGBMRegressor()
    sev_model = LocalFakeLGBMRegressor()
    feature_df = pd.DataFrame({
        "coverage_amount_vnd": [100_000_000],
        "product_id": ["HEALTH_BASIC"],
        "age": [30],
        "bmi": [22.5],
        "smoker": [False],
    })

    mock_explainer = MagicMock()
    mock_explainer.shap_values.return_value = np.array([[0.9, 0.8, 0.3, -0.2, 0.1]])

    with patch("app.pricing_engine.explain._get_tree_explainer", return_value=mock_explainer), \
         patch.dict(sys.modules, {"shap": sys.modules["shap"]}):
        result = explain(
            {"freq": freq_model, "sev": sev_model},
            feature_df,
            component_excluded_features={"severity": frozenset({"coverage_amount_vnd", "product_id"})},
        )

    severity_items = result["components"]["severity"]["items"]
    severity_features = {item["feature"] for item in severity_items}
    frequency_features = {item["feature"] for item in result["components"]["frequency"]["items"]}
    assert "coverage_amount_vnd" not in severity_features
    assert "product_id" not in severity_features
    assert "coverage_amount_vnd" in frequency_features
    assert [item["feature"] for item in severity_items[:3]] == ["age", "bmi", "smoker"]

def test_extract_model_and_features_pipeline():
    model = MagicMock()
    del model.feature_name_
    model.named_steps = {
        "prep": MagicMock(transformers=[("num", "passthrough", ["age", "bmi"])]),
        "est": "inner_estimator",
    }
    est, names = _extract_model_and_features(model)
    assert est == "inner_estimator"
    assert names == ["age", "bmi"]


def test_extract_model_and_features_fallback():
    model = MagicMock()
    del model.feature_name_
    del model.named_steps
    model.feature_names_in_ = ["x", "y"]
    est, names = _extract_model_and_features(model)
    assert est is model
    assert names == ["x", "y"]


def test_explain_empty_df_returns_unavailable():
    model = MagicMock()
    model.feature_name_ = ["age"]
    result = explain(model, pd.DataFrame())
    assert result["available"] is False
    assert result["items"] == []


def test_explain_none_df_returns_unavailable():
    model = MagicMock()
    model.feature_name_ = ["age"]
    result = explain(model, None)
    assert result["available"] is False


class FakeLGBMRegressor:
    """Fake model whose type name contains 'lgbm' to trigger tree explainer path."""
    feature_name_ = ["age", "coverage_amount_vnd", "deductible_vnd"]

    def predict(self, df):
        return np.array([500_000.0])


def test_explain_with_tree_model():
    mock_model = FakeLGBMRegressor()

    feature_df = pd.DataFrame({"age": [30], "coverage_amount_vnd": [100_000_000], "deductible_vnd": [0]})

    mock_explainer = MagicMock()
    mock_explainer.shap_values.return_value = np.array([[0.3, -0.5, 0.1]])

    with patch("app.pricing_engine.explain._get_tree_explainer", return_value=mock_explainer), \
         patch.dict(sys.modules, {"shap": sys.modules["shap"]}):
        result = explain(mock_model, feature_df)

    assert result["available"] is True
    assert result["method"] == "tree_shap"
    assert len(result["items"]) >= 3
    assert result["items"][0]["feature"] in ("coverage_amount_vnd", "age", "deductible_vnd")
    # Verify English label key and direction values
    item = result["items"][0]
    assert "label" in item
    assert "label_vi" not in item
    assert item["direction"] in ("increase", "decrease")


class FakeGLMEstimator:
    """Fake GLM estimator with coef_ attribute for linear explainer path."""
    coef_ = np.array([0.1, -0.2, 0.05])

    def predict(self, df):
        return np.array([500_000.0])


def test_explain_with_linear_model():
    class FakePipeline:
        named_steps = {
            "prep": MagicMock(transformers=[("num", "passthrough", ["age", "bmi", "smoker"])]),
            "est": FakeGLMEstimator(),
        }

    mock_model = FakePipeline()

    feature_df = pd.DataFrame({"age": [30], "bmi": [22.5], "smoker": [1]})

    mock_linear_explainer = MagicMock()
    mock_linear_explainer.shap_values.return_value = np.array([[0.1, -0.2, 0.05]])

    fake_shap = sys.modules["shap"]
    with patch("app.pricing_engine.explain._get_tree_explainer", side_effect=Exception("no tree")), \
         patch.object(fake_shap, "LinearExplainer", return_value=mock_linear_explainer):
        result = explain(mock_model, feature_df)

    assert result["available"] is True
    assert len(result["items"]) >= 3


class FakeGenericModel:
    """Fake model with no tree/linear attributes to trigger fallback perturbation."""
    feature_name_ = ["age", "bmi", "smoker"]

    def predict(self, df):
        return np.array([500_000.0])


def test_explain_fallback_perturbation():
    mock_model = FakeGenericModel()

    feature_df = pd.DataFrame({"age": [30], "bmi": [22.5], "smoker": [1]})

    fake_shap = sys.modules["shap"]
    with patch("app.pricing_engine.explain._get_tree_explainer", side_effect=Exception("no tree")), \
         patch.object(fake_shap, "LinearExplainer", side_effect=Exception("no linear")):
        result = explain(mock_model, feature_df)

    assert result["available"] is True
    assert len(result["items"]) >= 3


def test_explain_exception_returns_unavailable():
    model = MagicMock()
    model.feature_name_ = None
    del model.named_steps
    model.feature_names_in_ = None
    result = explain(model, pd.DataFrame({"a": [1]}))
    assert result["available"] is False
    assert result["items"] == []


def test_explain_3d_shap_values():
    mock_model = FakeLGBMRegressor()
    mock_model.feature_name_ = ["age", "bmi", "smoker"]

    feature_df = pd.DataFrame({"age": [30], "bmi": [22.5], "smoker": [1]})

    mock_explainer = MagicMock()
    mock_explainer.shap_values.return_value = np.array([[[0.1], [-0.2], [0.05]]])

    with patch("app.pricing_engine.explain._get_tree_explainer", return_value=mock_explainer), \
         patch.dict(sys.modules, {"shap": sys.modules["shap"]}):
        result = explain(mock_model, feature_df)

    assert result["available"] is True
    assert len(result["items"]) >= 3


def test_explain_less_than_3_features_returns_unavailable():
    class FakeSingleFeatureLGBM:
        feature_name_ = ["age"]
        def predict(self, df):
            return np.array([500_000.0])

    mock_model = FakeSingleFeatureLGBM()

    feature_df = pd.DataFrame({"age": [30]})

    mock_explainer = MagicMock()
    mock_explainer.shap_values.return_value = np.array([[0.5]])

    with patch("app.pricing_engine.explain._get_tree_explainer", return_value=mock_explainer), \
         patch.dict(sys.modules, {"shap": sys.modules["shap"]}):
        result = explain(mock_model, feature_df)

    assert result["available"] is False
    assert result["items"] == []
