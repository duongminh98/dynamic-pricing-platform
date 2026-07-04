"""Tests for app.pricing_engine.selection — champion model resolution.

There is exactly one serving (champion) model per line. select_model resolves it
from the loader's champion_config + artifact caches, raising on misconfiguration.
Uses mocked loader state so no model artifacts are needed.
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from app.pricing_engine import selection
from common.errors import ErrorCode, ServiceException


def _patch_loader(*, lines=("health",), champion=None, all_artifacts=None, artifacts=None):
    """Patch the loader globals select_model reads through."""
    return (
        patch.object(selection.loader, "LINES", list(lines)),
        patch.object(selection.loader, "ensure_loaded", lambda: None),
        patch.object(selection.loader, "get_champion", lambda line: champion or {}),
        patch.object(selection.loader, "all_artifacts", all_artifacts or {}),
        patch.object(selection.loader, "artifacts", artifacts or {}),
    )


def _apply(patches):
    for p in patches:
        p.start()


def _stop(patches):
    for p in patches:
        p.stop()


def test_select_model_freqsev_from_all_artifacts():
    freq, sev = MagicMock(), MagicMock()
    champ = {"model_version": "mv-1", "algorithm": "lgb", "family": "freqsev"}
    patches = _patch_loader(
        champion=champ,
        all_artifacts={"health": {"freq": {"lgb": freq}, "sev": {"lgb": sev}}},
    )
    _apply(patches)
    try:
        result = selection.select_model("health")
    finally:
        _stop(patches)

    assert result["model_version"] == "mv-1"
    assert result["family"] == "freqsev"
    assert result["model"] == {"freq": freq, "sev": sev}


def test_select_model_tw_from_all_artifacts():
    model = MagicMock()
    champ = {"model_version": "mv-tw", "algorithm": "lgb", "family": "tw"}
    patches = _patch_loader(champion=champ, all_artifacts={"health": {"tw": {"lgb": model}}})
    _apply(patches)
    try:
        result = selection.select_model("health")
    finally:
        _stop(patches)

    assert result["family"] == "tw"
    assert result["model"] is model


def test_select_model_falls_back_to_artifacts_dict():
    model = MagicMock()
    champ = {"model_version": "mv-tw", "algorithm": "lgb", "family": "tw"}
    # all_artifacts empty for this line → fall back to loader.artifacts
    patches = _patch_loader(
        champion=champ,
        all_artifacts={"health": {"tw": {}}},
        artifacts={"health": {"tw": model}},
    )
    _apply(patches)
    try:
        result = selection.select_model("health")
    finally:
        _stop(patches)

    assert result["model"] is model


def test_select_model_unsupported_line():
    patches = _patch_loader(lines=("health",))
    _apply(patches)
    try:
        with pytest.raises(ServiceException) as exc:
            selection.select_model("spaceship")
    finally:
        _stop(patches)
    assert exc.value.error_code == ErrorCode.UNSUPPORTED_LINE


def test_select_model_missing_champion_config():
    patches = _patch_loader(champion={})  # no model_version
    _apply(patches)
    try:
        with pytest.raises(ServiceException) as exc:
            selection.select_model("health")
    finally:
        _stop(patches)
    assert exc.value.error_code == ErrorCode.MISSING_CHAMPION


def test_select_model_missing_artifact_object():
    champ = {"model_version": "mv-1", "algorithm": "lgb", "family": "tw"}
    # champion configured but no artifact object present anywhere
    patches = _patch_loader(champion=champ, all_artifacts={}, artifacts={})
    _apply(patches)
    try:
        with pytest.raises(ServiceException) as exc:
            selection.select_model("health")
    finally:
        _stop(patches)
    assert exc.value.error_code == ErrorCode.MISSING_CHAMPION


def test_select_model_freqsev_missing_one_side():
    freq = MagicMock()
    champ = {"model_version": "mv-1", "algorithm": "lgb", "family": "freqsev"}
    # only freq present, sev missing → MISSING_CHAMPION
    patches = _patch_loader(
        champion=champ,
        all_artifacts={"health": {"freq": {"lgb": freq}, "sev": {}}},
        artifacts={"health": {"freq": freq}},
    )
    _apply(patches)
    try:
        with pytest.raises(ServiceException) as exc:
            selection.select_model("health")
    finally:
        _stop(patches)
    assert exc.value.error_code == ErrorCode.MISSING_CHAMPION
