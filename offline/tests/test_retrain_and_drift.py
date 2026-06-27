"""Tests for the retrain trigger and drift monitor (task 23.3, R37.2 / R37.7).

These tests run fully OFFLINE and never touch a live database or the serving
quote path. The retrain pipeline steps (train / validate / monotonic gate /
register candidate) are monkeypatched so we can assert the *control flow*:

  * a candidate Model_Version is registered, and it is NEVER auto-promoted;
  * the monotonic gate MUST pass before the candidate is registered (a failing
    gate aborts the chain with no registration).

The drift tests inject drifted distributions / metric values and assert the
recalibration flag is raised.
"""
from __future__ import annotations

import pathlib

import pytest

import offline.retrain_trigger as rt
from offline.drift_monitor import population_stability_index, evaluate_line


# ───────────────────────── Retrain trigger ─────────────────────────────────
class TestRetrainTriggerPipeline:

    def _patch_steps(self, monkeypatch, calls, *, gate_ok=True):
        """Patch every pipeline step to record call order, without side effects.

        register_candidate is patched to return a fake candidate id and is
        recorded so we can assert whether it ran. It NEVER promotes.
        """
        monkeypatch.setattr(rt, "run_training",
                            lambda line: (calls.append("train"), (True, ""))[1])
        monkeypatch.setattr(rt, "run_validation",
                            lambda line: (calls.append("validate"), (True, ""))[1])
        monkeypatch.setattr(rt, "run_monotonic_gate",
                            lambda line: (calls.append("gate"),
                                          (gate_ok, "ok" if gate_ok else "violation"))[1])

        def _fake_register(line):
            calls.append("register")
            return {"candidate_model_version": "cand-123", "line": line, "promoted": False}

        monkeypatch.setattr(rt, "register_candidate", _fake_register)

    def test_candidate_registered_and_not_promoted(self, monkeypatch):
        calls = []
        self._patch_steps(monkeypatch, calls, gate_ok=True)

        result = rt.trigger_retrain("health")

        assert result["status"] == "candidate_registered"
        assert result["candidate_model_version"] == "cand-123"
        # The trigger must NEVER auto-promote.
        assert result["promoted"] is False
        # Full chain ran in order.
        assert calls == ["train", "validate", "gate", "register"]
        assert result["steps"] == ["train", "validate", "monotonic_gate", "register_candidate"]

    def test_monotonic_gate_must_pass_before_register(self, monkeypatch):
        calls = []
        self._patch_steps(monkeypatch, calls, gate_ok=False)

        result = rt.trigger_retrain("health")

        # Gate failed -> registration must NOT happen.
        assert result["status"] == "gate_failed"
        assert "register" not in calls
        assert result["candidate_model_version"] is None
        assert result["promoted"] is False
        # Gate ran (after train+validate) but the chain stopped there.
        assert calls == ["train", "validate", "gate"]

    def test_gate_runs_before_register_in_order(self, monkeypatch):
        calls = []
        self._patch_steps(monkeypatch, calls, gate_ok=True)
        rt.trigger_retrain("car")
        assert calls.index("gate") < calls.index("register")

    def test_train_failure_aborts_before_gate_and_register(self, monkeypatch):
        calls = []
        self._patch_steps(monkeypatch, calls, gate_ok=True)
        monkeypatch.setattr(rt, "run_training",
                            lambda line: (calls.append("train"), (False, "boom"))[1])

        result = rt.trigger_retrain("home")

        assert result["status"] == "failed"
        assert calls == ["train"]  # nothing after the failing step
        assert result["candidate_model_version"] is None

    def test_dry_run_does_not_run_any_step(self, monkeypatch):
        calls = []
        self._patch_steps(monkeypatch, calls, gate_ok=True)
        result = rt.trigger_retrain("travel", dry_run=True)
        assert result["status"] == "dry_run"
        assert calls == []


class TestMonotonicGateExemption:

    def test_travel_is_monotonic_exempt(self):
        ok, msg = rt.run_monotonic_gate("travel")
        assert ok is True
        assert "exempt" in msg.lower()

    def test_non_exempt_line_requires_artifact(self, tmp_path):
        # Point the gate at an empty dir -> missing artifact -> gate fails.
        ok, msg = rt.run_monotonic_gate("health", models_dir=pathlib.Path(tmp_path))
        assert ok is False
        assert "missing" in msg.lower()


# ───────────────────────── Drift monitor ───────────────────────────────────
class TestDriftFlagging:

    def test_injected_feature_drift_sets_flag(self):
        config = {"drift_threshold_psi": 0.2, "drift_threshold_calibration": 0.15}
        # Inject a large PSI (well above threshold).
        result = evaluate_line("health", config, psi_value=0.9, cal_value=0.0)
        assert result["psi"]["needs_recalibration"] is True
        assert result["needs_recalibration"] is True

    def test_injected_calibration_drift_sets_flag(self):
        config = {"drift_threshold_psi": 0.2, "drift_threshold_calibration": 0.15}
        result = evaluate_line("car", config, psi_value=0.0, cal_value=0.5)
        assert result["calibration"]["needs_recalibration"] is True
        assert result["needs_recalibration"] is True

    def test_no_drift_when_metrics_below_threshold(self):
        config = {"drift_threshold_psi": 0.2, "drift_threshold_calibration": 0.15}
        result = evaluate_line("home", config, psi_value=0.01, cal_value=0.01)
        assert result["psi"]["needs_recalibration"] is False
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


# ───────────────────────── Drift-driven retrain (T12-T14) ───────────────────
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
        monkeypatch.setattr(rt, "run_training",
                            lambda line: (calls.append("train"), (True, ""))[1])
        monkeypatch.setattr(rt, "run_validation",
                            lambda line: (calls.append("validate"), (True, ""))[1])
        monkeypatch.setattr(rt, "run_monotonic_gate",
                            lambda line: (calls.append("gate"), (True, "ok"))[1])

        registered = []

        def _fake_register(line):
            registered.append(line)
            calls.append("register")
            return {"candidate_model_version": "cand-drift-001", "line": line, "promoted": False}

        monkeypatch.setattr(rt, "register_candidate", _fake_register)

        result = rt.trigger_retrain("health")
        assert result["status"] == "candidate_registered"
        assert result["promoted"] is False
        assert "register" in calls
        assert registered == ["health"]

    def test_t14_drift_trigger_disabled_returns_empty(self, monkeypatch):
        """T14: drift_trigger_enabled=false → lines_with_drift returns []."""
        config = {"drift_trigger_enabled": False}

        # Even if _get_db_connection would return data, it should never be called.
        def _should_not_be_called():
            raise AssertionError("_get_db_connection should not be called when disabled")

        monkeypatch.setattr(rt, "_get_db_connection", _should_not_be_called)
        result = rt.lines_with_drift(config)
        assert result == []
