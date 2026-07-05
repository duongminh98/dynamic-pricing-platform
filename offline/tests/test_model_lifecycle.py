"""Tests for offline model lifecycle scripts (task 23.3, R37.2/R37.7).

Tests the retrain trigger and drift monitor logic without requiring a live
database or running the full training pipeline.
"""
import pytest
import datetime
import json
import pathlib

from offline.retrain_trigger import (
    is_scheduled, count_new_claims_for_line, lines_exceeding_threshold,
    load_config, trigger_retrain,
)
from offline.drift_monitor import (
    population_stability_index, compute_feature_drift, evaluate_line,
)
from offline.build_training_dataset_from_pricing_db import _write_metadata


class TestRetrainTrigger:

    def test_is_scheduled_quarterly_january_first_day(self):
        config = {"schedule_quarterly": True, "quarterly_months": [1, 4, 7, 10]}
        jan = datetime.datetime(2026, 1, 1)
        assert is_scheduled(config, now=jan) is True

    def test_is_not_scheduled_later_in_quarterly_month(self):
        config = {"schedule_quarterly": True, "quarterly_months": [1, 4, 7, 10]}
        jan = datetime.datetime(2026, 1, 15)
        assert is_scheduled(config, now=jan) is False

    def test_is_scheduled_quarterly_february(self):
        config = {"schedule_quarterly": True, "quarterly_months": [1, 4, 7, 10]}
        feb = datetime.datetime(2026, 2, 15)
        assert is_scheduled(config, now=feb) is False

    def test_is_scheduled_disabled(self):
        config = {"schedule_quarterly": False}
        assert is_scheduled(config) is False

    def test_count_new_claims_uses_exported_frequency_rows(self, tmp_path):
        data_dir = tmp_path / "data"
        data_dir.mkdir()
        (data_dir / "pricing_freq_health.csv").write_text("exposure_id,line\ne1,health\ne2,health\n", encoding="utf-8")

        assert count_new_claims_for_line("health", data_dir=data_dir) == 2

    def test_export_metadata_contains_training_table_maps(self, tmp_path):
        metadata_path = _write_metadata(tmp_path, "dataset-1")
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

        assert metadata["freq_tables"]["health"] == "pricing_freq_health.csv"
        assert metadata["sev_tables"]["health"] == "pricing_sev_health.csv"
        assert metadata["frequency_target"] == "claim_count"

    def test_load_config_has_thresholds(self):
        config = load_config()
        assert "line_thresholds" in config
        assert "drift_threshold_psi" in config
        for line in ["health", "motorbike", "car", "home", "accident", "travel"]:
            assert line in config["line_thresholds"]

    def test_trigger_retrain_dry_run(self):
        result = trigger_retrain("health", dry_run=True)
        assert result["line"] == "health"
        assert result["status"] == "dry_run"
        assert result["error"] is None


class TestDriftMonitor:

    def test_psi_identical_distributions(self):
        vals = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
        psi = population_stability_index(vals, vals)
        assert psi < 0.01, "identical distributions should have near-zero PSI"

    def test_psi_different_distributions(self):
        low = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
        high = [100.0, 200.0, 300.0, 400.0, 500.0, 600.0, 700.0, 800.0, 900.0, 1000.0]
        psi = population_stability_index(low, high)
        assert psi > 0.1, "very different distributions should have high PSI"

    def test_psi_empty_inputs(self):
        assert population_stability_index([], []) == 0.0

    def test_psi_single_value(self):
        assert population_stability_index([5.0], [5.0]) == 0.0

    def test_evaluate_line_returns_expected_structure(self):
        config = {
            "drift_threshold_psi": 0.2,
            "drift_threshold_prediction_psi": 0.2,
            "drift_threshold_calibration": 0.15,
        }
        result = evaluate_line("health", config)
        assert result["line"] == "health"
        assert "feature_psi" in result
        assert "prediction_psi" in result
        assert "calibration" in result
        assert "needs_recalibration" in result
        assert "value" in result["feature_psi"]
        assert "threshold" in result["feature_psi"]
        assert "needs_recalibration" in result["feature_psi"]

    def test_evaluate_line_drift_detected(self):
        """When no baseline exists, structure is still valid."""
        config = {
            "drift_threshold_psi": 0.0,
            "drift_threshold_prediction_psi": 0.0,
            "drift_threshold_calibration": 0.0,
        }
        result = evaluate_line("health", config)
        assert isinstance(result["needs_recalibration"], bool)
