"""Tests for the retrain trigger and drift monitor (task 23.3, R37.2 / R37.7).

These tests run fully OFFLINE and never touch a live database or the serving
quote path. The governed lifecycle pipeline is monkeypatched so we can assert
the *control flow*:

  * a candidate Model_Version is registered, and it is NEVER auto-promoted;
  * lifecycle failures abort the chain with no registered candidate.

The drift tests inject drifted distributions / metric values and assert the
recalibration flag is raised.
"""
from __future__ import annotations

import datetime
import pathlib
import sys

import pytest

import offline.retrain_trigger as rt
from offline.drift_monitor import (
    population_stability_index,
    evaluate_line,
    compute_feature_drift,
    compute_prediction_drift,
    compute_calibration_drift,
)


# Retrain trigger
class TestRetrainTriggerPipeline:

    def _patch_pipeline(self, monkeypatch, calls, *, ok=True):
        """Patch the governed lifecycle pipeline without side effects."""
        def _fake_pipeline(line):
            calls.append("lifecycle_pipeline")
            if not ok:
                raise RuntimeError("boom")
            return {"candidate_model_version": "cand-123", "line": line, "promoted": False}

        monkeypatch.setattr(rt, "run_lifecycle_pipeline", _fake_pipeline)

    def test_candidate_registered_and_not_promoted(self, monkeypatch):
        calls = []
        self._patch_pipeline(monkeypatch, calls, ok=True)

        result = rt.trigger_retrain("health")

        assert result["status"] == "candidate_registered"
        assert result["candidate_model_version"] == "cand-123"
        # The trigger must NEVER auto-promote.
        assert result["promoted"] is False
        assert calls == ["lifecycle_pipeline"]
        assert result["steps"] == ["lifecycle_pipeline"]

    def test_lifecycle_failure_aborts_before_candidate(self, monkeypatch):
        calls = []
        self._patch_pipeline(monkeypatch, calls, ok=False)

        result = rt.trigger_retrain("health")

        assert result["status"] == "failed"
        assert result["candidate_model_version"] is None
        assert result["promoted"] is False
        assert calls == ["lifecycle_pipeline"]
        assert "lifecycle pipeline failed" in result["error"]

    def test_dry_run_does_not_run_any_step(self, monkeypatch):
        calls = []
        self._patch_pipeline(monkeypatch, calls, ok=True)
        result = rt.trigger_retrain("travel", dry_run=True)
        assert result["status"] == "dry_run"
        assert calls == []


class TestMonotonicGateExemption:

    def test_travel_glm_candidate_is_exempt(self):
        # Exemption applies ONLY to a GLM candidate on an exempt line.
        ok, msg = rt.run_monotonic_gate("travel", algorithm="glm")
        assert ok is True
        assert "exempt" in msg.lower()

    def test_travel_lgb_candidate_is_not_exempt(self, tmp_path):
        # travel's champion is now lgb freqsev: a LightGBM candidate must NOT be
        # waved through. With no artifact in the dir the gate fails on missing.
        ok, msg = rt.run_monotonic_gate("travel", models_dir=pathlib.Path(tmp_path),
                                        algorithm="lgb")
        assert ok is False
        assert "missing" in msg.lower()

    def test_non_exempt_line_requires_artifact(self, tmp_path):
        # Point the gate at an empty dir -> missing artifact -> gate fails.
        ok, msg = rt.run_monotonic_gate("health", models_dir=pathlib.Path(tmp_path))
        assert ok is False
        assert "missing" in msg.lower()


# Drift monitor
class TestDriftFlagging:

    def test_injected_feature_drift_sets_flag(self):
        config = {
            "drift_threshold_psi": 0.2,
            "drift_threshold_prediction_psi": 0.2,
            "drift_threshold_calibration": 0.15,
            "calibration_min_total_outcomes": 100,
            "calibration_min_samples_per_bin": 20,
        }
        baseline = {
            "feature_bins": {
                "age": {"edges": [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100],
                         "proportions": [0.1]*10},
            },
            "prediction_bins": {"edges": [0]*11, "proportions": [1.0] + [0.0]*9},
            "calibration_reference": [],
        }
        current = [{"age": 95.0} for _ in range(100)]
        result = evaluate_line("health", config, baseline=baseline, current_quotes=current, outcomes=[])
        assert result["feature_psi"]["needs_recalibration"] is True
        assert result["needs_recalibration"] is True

    def test_injected_calibration_drift_sets_flag(self):
        config = {
            "drift_threshold_psi": 0.2,
            "drift_threshold_prediction_psi": 0.2,
            "drift_threshold_calibration": 0.15,
            "calibration_min_total_outcomes": 100,
            "calibration_min_samples_per_bin": 20,
        }
        baseline = {
            "feature_bins": {},
            "prediction_bins": {"edges": [0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000],
                                 "proportions": [0.1]*10},
            "calibration_reference": [
                {"bin_index": i, "count": 25, "actual_rate": 1000.0}
                for i in range(10)
            ],
        }
        outcomes = []
        for i in range(200):
            outcomes.append({
                "pure_premium_vnd": float((i % 10) * 100 + 50),
                "actual_loss_vnd": 50000.0,
            })
        result = evaluate_line("car", config, baseline=baseline, current_quotes=[], outcomes=outcomes)
        assert result["calibration"]["needs_recalibration"] is True
        assert result["needs_recalibration"] is True

    def test_no_drift_when_metrics_below_threshold(self):
        config = {
            "drift_threshold_psi": 0.2,
            "drift_threshold_prediction_psi": 0.2,
            "drift_threshold_calibration": 0.15,
            "calibration_min_total_outcomes": 100,
            "calibration_min_samples_per_bin": 20,
        }
        baseline = {
            "feature_bins": {
                "age": {"edges": [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100],
                         "proportions": [0.1]*10},
            },
            "prediction_bins": {"edges": [0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000],
                                 "proportions": [0.1]*10},
            "calibration_reference": [
                {"bin_index": i, "count": 25, "actual_rate": 1000.0}
                for i in range(10)
            ],
        }
        current = [{"age": float(i % 100), "pure_premium_vnd": float((i % 10) * 100 + 50)}
                   for i in range(200)]
        outcomes = []
        for i in range(200):
            outcomes.append({
                "pure_premium_vnd": float((i % 10) * 100 + 50),
                "actual_loss_vnd": 1000.0,
            })
        result = evaluate_line("home", config, baseline=baseline, current_quotes=current, outcomes=outcomes)
        assert result["feature_psi"]["needs_recalibration"] is False
        assert result["calibration"]["needs_recalibration"] is False
        assert result["needs_recalibration"] is False

    def test_psi_rises_with_injected_distribution_shift(self):
        baseline = [float(i % 10) for i in range(500)]
        # Shift the distribution far to the right -> high PSI.
        drifted = [float(50 + (i % 10)) for i in range(500)]
        psi_same = population_stability_index(baseline, baseline)
        psi_drift = population_stability_index(baseline, drifted)
        assert psi_same < 0.1
        assert psi_drift > 0.25  # "significant drift" band


# Drift-driven retrain (T12-T14)
class TestDriftDrivenRetrain:

    def test_t12_lines_with_drift_reads_needs_recalibration(self, monkeypatch):
        """T12: lines_with_drift reads model_drift_flag needs_recalibration=true."""
        config = {"drift_trigger_enabled": True}

        class FakeCursor:
            def __init__(self):
                self._data = {
                    "health": (True,),
                    "car": (False,),
                    "motorbike": (True,),
                    "home": (False,),
                    "accident": (False,),
                    "travel": (False,),
                }
                self._current = None

            def execute(self, sql, params):
                self._current = params[0] if params else None

            def fetchone(self):
                if self._current and self._current in self._data:
                    return self._data[self._current]
                return None

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

        class FakeConn:
            def cursor(self):
                return FakeCursor()

            def close(self):
                pass

        monkeypatch.setattr(rt, "_get_db_connection", lambda: FakeConn())
        result = rt.lines_with_drift(config)
        assert "health" in result
        assert "motorbike" in result
        assert "car" not in result
        assert "home" not in result

    def test_t13_drift_trigger_registers_candidate_not_promote(self, monkeypatch):
        """T13: Drift trigger chain registers candidate, never touches champion_assignment."""
        calls = []

        registered = []

        def _fake_pipeline(line):
            registered.append(line)
            calls.append("lifecycle_pipeline")
            return {"candidate_model_version": "cand-drift-001", "line": line, "promoted": False}

        monkeypatch.setattr(rt, "run_lifecycle_pipeline", _fake_pipeline)

        result = rt.trigger_retrain("health")
        assert result["status"] == "candidate_registered"
        assert result["promoted"] is False
        assert calls == ["lifecycle_pipeline"]
        assert registered == ["health"]

    def test_t14_drift_trigger_disabled_returns_empty(self, monkeypatch):
        """T14: drift_trigger_enabled=false â†’ lines_with_drift returns []."""
        config = {"drift_trigger_enabled": False}

        # Even if _get_db_connection would return data, it should never be called.
        def _should_not_be_called():
            raise AssertionError("_get_db_connection should not be called when disabled")

        monkeypatch.setattr(rt, "_get_db_connection", _should_not_be_called)
        result = rt.lines_with_drift(config)
        assert result == []

    def test_main_returns_failure_when_any_triggered_line_fails(self, monkeypatch):
        """A failed line must make the CI process fail."""
        monkeypatch.setattr(sys, "argv", ["retrain_trigger.py", "--line", "health"])
        monkeypatch.setattr(rt, "load_config", lambda: {})
        monkeypatch.setattr(
            rt,
            "trigger_retrain",
            lambda line, dry_run=False: {
                "line": line,
                "status": "failed",
                "error": "train failed",
                "steps": ["train"],
                "candidate_model_version": None,
                "promoted": False,
            },
        )

        assert rt.main() == 1


# Quarterly-schedule period dedup (fix #1)
class TestScheduledPeriodDedup:
    """A DAILY cron must fire the quarterly retrain at most once per quarter."""

    _CONFIG = {"schedule_quarterly": True, "quarterly_months": [1, 4, 7, 10]}

    def test_all_lines_due_when_none_retrained_this_period(self, monkeypatch):
        monkeypatch.setattr(rt, "lines_retrained_since", lambda _start: set())
        # day 1 so the merged is_scheduled (now requires now.day == quarterly_day,
        # default 1) fires; this test exercises the dedup filter, not the day gate.
        july = datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc)
        due = rt.scheduled_lines_needing_retrain(self._CONFIG, now=july)
        assert due == rt.LINES

    def test_already_retrained_lines_are_skipped(self, monkeypatch):
        monkeypatch.setattr(rt, "lines_retrained_since",
                            lambda _start: {"health", "car"})
        july = datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc)
        due = rt.scheduled_lines_needing_retrain(self._CONFIG, now=july)
        assert "health" not in due and "car" not in due
        assert "motorbike" in due

    def test_empty_when_schedule_month_does_not_match(self, monkeypatch):
        monkeypatch.setattr(rt, "lines_retrained_since", lambda _start: set())
        february = datetime.datetime(2026, 2, 10, tzinfo=datetime.timezone.utc)
        assert rt.scheduled_lines_needing_retrain(self._CONFIG, now=february) == []

    def test_period_start_is_current_quarter_month(self):
        july = datetime.datetime(2026, 7, 20, tzinfo=datetime.timezone.utc)
        start = rt._quarter_period_start(self._CONFIG, july)
        assert (start.year, start.month, start.day) == (2026, 7, 1)


# Data-threshold reads the read-model, not a local CSV (fix #2)
class TestDataThresholdReadModel:

    def _fake_conn(self, count):
        class FakeCursor:
            def execute(self, sql, params):
                assert "claim_outcome" in sql  # reads the read-model, not CSV
            def fetchone(self):
                return (count,)
            def __enter__(self):
                return self
            def __exit__(self, *a):
                pass

        class FakeConn:
            def cursor(self):
                return FakeCursor()
            def close(self):
                pass

        return FakeConn()

    def test_count_reads_claim_outcome(self, monkeypatch):
        monkeypatch.setattr(rt, "_get_db_connection", lambda: self._fake_conn(742))
        assert rt.count_new_claims_for_line("car", window_days=90) == 742

    def test_db_error_counts_zero(self, monkeypatch):
        def _boom():
            raise RuntimeError("db down")
        monkeypatch.setattr(rt, "_get_db_connection", _boom)
        assert rt.count_new_claims_for_line("car") == 0

    def test_lines_exceeding_threshold_uses_count(self, monkeypatch):
        counts = {"car": 500, "home": 10}
        monkeypatch.setattr(rt, "count_new_claims_for_line",
                            lambda line, window_days=90: counts.get(line, 0))
        config = {"data_threshold_enabled": True,
                  "line_thresholds": {"car": 300, "home": 200}}
        result = rt.lines_exceeding_threshold(config)
        assert "car" in result and "home" not in result


# Monotonic gate validates the SERVED freqsev artifacts (fix #3)
class TestMonotonicGateFreqSev:

    def test_incomplete_freqsev_pair_fails(self, tmp_path):
        # Only freq present (no sev, no tw fallback): the gate needs the whole
        # served pair, so it reports missing WITHOUT trying to load anything.
        (tmp_path / "car__lgb_freq.joblib").write_bytes(b"x")
        ok, msg = rt.run_monotonic_gate("car", models_dir=pathlib.Path(tmp_path))
        assert ok is False
        assert "missing" in msg.lower()


# Drift readers must NOT swallow DB errors as "no drift" (fix #4)
class TestDriftErrorPropagation:

    def test_load_current_quotes_propagates_db_error(self, monkeypatch):
        import offline.drift_monitor as dm

        def _boom():
            raise RuntimeError("connection refused")

        monkeypatch.setattr(dm, "_connect", _boom)
        with pytest.raises(RuntimeError):
            dm.load_current_quotes("car", 30)

    def test_load_outcomes_propagates_db_error(self, monkeypatch):
        import offline.drift_monitor as dm

        def _boom():
            raise RuntimeError("connection refused")

        monkeypatch.setattr(dm, "_connect", _boom)
        with pytest.raises(RuntimeError):
            dm.load_outcomes("car", 30)
