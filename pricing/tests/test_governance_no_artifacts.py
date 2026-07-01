"""Tests for app.pricing_engine.governance — champion promotion/rollback with mocked DB.

Does not need model artifacts; uses unittest.mock for DB session and models.
"""
from __future__ import annotations

import datetime
from unittest.mock import MagicMock, patch

import pytest

from app.pricing_engine import governance
from common.errors import ErrorCode, ServiceException


def _mock_model(model_version_id="mv1", line="health", gini=0.8, algorithm="lgb",
                monotonic_applied=True):
    m = MagicMock()
    m.model_version_id = model_version_id
    m.line = line
    m.gini = gini
    m.rmse = 100.0
    m.mae = 50.0
    m.deviance = 200.0
    m.algorithm = algorithm
    m.monotonic_applied = monotonic_applied
    m.status = "CANDIDATE"
    m.quality_gates = {"comparison_passed": True, "smoothness_passed": True}
    m.comparison_report_uri = "reports/comparison.json"
    return m


def _mock_db():
    db = MagicMock()
    return db


def test_promote_champion_success():
    db = _mock_db()
    challenger = _mock_model("mv2", gini=0.85)
    db.query.return_value.filter.return_value.first.side_effect = [challenger, None]
    db.query.return_value.filter.return_value.update.return_value = 0

    with patch("app.pricing_engine.governance.is_monotonic_exempt", return_value=False):
        result = governance.promote_champion(db, "health", "mv2")

    assert result["promoted"] is True
    assert result["champion"] == "mv2"
    db.commit.assert_called_once()


def test_promote_champion_rejected_monotonic_not_applied():
    db = _mock_db()
    challenger = _mock_model("mv2", gini=0.90, monotonic_applied=False)
    db.query.return_value.filter.return_value.first.side_effect = [challenger, None]

    with patch("app.pricing_engine.governance.is_monotonic_exempt", return_value=False):
        result = governance.promote_champion(db, "health", "mv2")

    assert result["promoted"] is False
    assert result["reason"] == "MONOTONIC_NOT_APPLIED"


def test_promote_champion_rejected_gini_not_improved():
    db = _mock_db()
    challenger = _mock_model("mv2", gini=0.75)
    current = _mock_model("mv1", gini=0.80)
    mock_assignment = MagicMock()
    mock_assignment.is_current = True
    mock_assignment.model_version_id = "mv1"

    # _get_model -> challenger, _current_champion assignment -> mock_assignment, _current_champion model -> current
    db.query.return_value.filter.return_value.first.side_effect = [challenger, mock_assignment, current]

    with patch("app.pricing_engine.governance.is_monotonic_exempt", return_value=False):
        result = governance.promote_champion(db, "health", "mv2")

    assert result["promoted"] is False
    assert result["reason"] == "GINI_NOT_IMPROVED"


def test_promote_champion_line_mismatch():
    db = _mock_db()
    challenger = _mock_model("mv2", line="car")
    db.query.return_value.filter.return_value.first.return_value = challenger

    with pytest.raises(ServiceException) as exc_info:
        governance.promote_champion(db, "health", "mv2")
    assert exc_info.value.error_code == ErrorCode.BAD_REQUEST


def test_promote_champion_model_not_found():
    db = _mock_db()
    db.query.return_value.filter.return_value.first.return_value = None

    with pytest.raises(ServiceException) as exc_info:
        governance.promote_champion(db, "health", "nonexistent")
    assert exc_info.value.error_code == ErrorCode.BAD_REQUEST


def test_promote_champion_exempt_line_allows_no_monotonic():
    db = _mock_db()
    challenger = _mock_model("mv2", line="travel", gini=0.85, algorithm="glm", monotonic_applied=False)
    db.query.return_value.filter.return_value.first.side_effect = [challenger, None]
    db.query.return_value.filter.return_value.update.return_value = 0

    with patch("app.pricing_engine.governance.is_monotonic_exempt", return_value=True):
        result = governance.promote_champion(db, "travel", "mv2")

    assert result["promoted"] is True
    assert result["champion"] == "mv2"


def test_rollback_champion_success():
    db = _mock_db()
    current_assignment = MagicMock()
    current_assignment.is_current = True
    current_assignment.model_version_id = "mv2"

    previous_assignment = MagicMock()
    previous_assignment.is_current = False
    previous_assignment.model_version_id = "mv1"

    db.query.return_value.filter.return_value.order_by.return_value.all.return_value = [
        current_assignment, previous_assignment
    ]
    db.query.return_value.filter.return_value.update.return_value = 0

    result = governance.rollback_champion(db, "health")

    assert result["rolled_back"] is True
    assert result["champion"] == "mv1"
    db.commit.assert_called_once()


def test_rollback_champion_no_previous():
    db = _mock_db()
    current_assignment = MagicMock()
    current_assignment.is_current = True
    current_assignment.model_version_id = "mv1"

    db.query.return_value.filter.return_value.order_by.return_value.all.return_value = [
        current_assignment
    ]

    with pytest.raises(ServiceException) as exc_info:
        governance.rollback_champion(db, "health")
    assert exc_info.value.error_code == ErrorCode.BAD_REQUEST


def test_rollback_champion_no_history():
    db = _mock_db()
    db.query.return_value.filter.return_value.order_by.return_value.all.return_value = []

    with pytest.raises(ServiceException) as exc_info:
        governance.rollback_champion(db, "health")
    assert exc_info.value.error_code == ErrorCode.BAD_REQUEST
