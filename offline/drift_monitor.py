"""Drift monitor for model lifecycle — hardened compute layer.

Compares per-line:
(a) Feature distribution drift: current input features vs baseline bins
    using Population Stability Index (PSI).
(b) Prediction distribution drift: current model predictions vs baseline
    prediction bins using PSI.
(c) Calibration drift: actual-vs-predicted deviation by bin, using
    claim_outcome read-model joined to quote predictions.

When a metric exceeds its configured threshold, sets needs_recalibration=true
for the line in the model_drift_flag table. This flag feeds into the
retrain trigger.

Runs OFFLINE -- never in the serving path.

Usage:
  python offline/drift_monitor.py                # compute + persist flags
  python offline/drift_monitor.py --dry-run       # compute without persisting
"""
from __future__ import annotations

import json
import math
import pathlib
import sys
import datetime
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINES_DIR = ROOT / "reports" / "modeling" / "baselines"
CONFIG_PATH = ROOT / "offline" / "retrain_config.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]


def load_config() -> dict:
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


# ── Pure compute functions ─────────────────────────────────────────────────

def population_stability_index(expected: list[float], actual: list[float], bins: int = 10) -> float:
    """Compute PSI between two distributions.

    PSI < 0.1: no significant drift.
    PSI 0.1-0.25: moderate drift.
    PSI > 0.25: significant drift.

    Returns the PSI value (float). Handles empty/zero buckets gracefully.
    """
    if not expected or not actual:
        return 0.0

    all_vals = expected + actual
    if len(all_vals) < 2:
        return 0.0

    lo, hi = min(all_vals), max(all_vals)
    if lo == hi:
        return 0.0

    edges = [lo + i * (hi - lo) / bins for i in range(bins + 1)]

    def bucketize(vals):
        counts = [0] * bins
        for v in vals:
            idx = min(int((v - lo) / (hi - lo) * bins), bins - 1)
            counts[idx] += 1
        total = sum(counts)
        return [c / total if total > 0 else 0.0 for c in counts]

    exp_pcts = bucketize(expected)
    act_pcts = bucketize(actual)

    psi = 0.0
    for e, a in zip(exp_pcts, act_pcts):
        e_eps = max(e, 1e-6)
        a_eps = max(a, 1e-6)
        psi += (a_eps - e_eps) * math.log(a_eps / e_eps)
    return psi


def compute_feature_drift(baseline: dict, current_df: list[dict]) -> dict:
    """Compute feature PSI from baseline bins vs current data.

    Pure function: takes baseline artifact (feature_bins dict) and a list of
    current quote dicts (with numeric feature columns). Returns mean PSI
    across all features present in both baseline and current data.
    """
    feature_bins = baseline.get("feature_bins", {})
    if not feature_bins or not current_df:
        return {"value": 0.0, "features_evaluated": 0}

    psis = []
    for col, bins_data in feature_bins.items():
        edges = bins_data.get("edges", [])
        baseline_props = bins_data.get("proportions", [])
        if not edges or not baseline_props:
            continue

        current_vals = []
        for row in current_df:
            try:
                current_vals.append(float(row.get(col, 0)))
            except (ValueError, TypeError):
                pass

        if not current_vals:
            continue

        n_bins = len(baseline_props)
        lo, hi = edges[0], edges[-1]
        if lo == hi:
            continue

        current_counts = [0] * n_bins
        for v in current_vals:
            idx = min(int((v - lo) / (hi - lo) * n_bins), n_bins - 1)
            if idx < 0:
                idx = 0
            current_counts[idx] += 1
        total = sum(current_counts)
        current_props = [c / total if total > 0 else 0.0 for c in current_counts]

        psi = 0.0
        for e, a in zip(baseline_props, current_props):
            e_eps = max(e, 1e-6)
            a_eps = max(a, 1e-6)
            psi += (a_eps - e_eps) * math.log(a_eps / e_eps)
        psis.append(psi)

    mean_psi = sum(psis) / len(psis) if psis else 0.0
    return {"value": round(mean_psi, 4), "features_evaluated": len(psis)}


def compute_prediction_drift(baseline: dict, current_df: list[dict]) -> dict:
    """Compute prediction PSI from baseline prediction bins vs current predictions.

    Pure function: takes baseline artifact (prediction_bins dict) and a list of
    current quote dicts (with pure_premium_vnd field). Returns PSI.
    """
    pred_bins = baseline.get("prediction_bins", {})
    if not pred_bins or not current_df:
        return {"value": 0.0, "predictions_evaluated": 0}

    edges = pred_bins.get("edges", [])
    baseline_props = pred_bins.get("proportions", [])
    if not edges or not baseline_props:
        return {"value": 0.0, "predictions_evaluated": 0}

    current_vals = []
    for row in current_df:
        try:
            current_vals.append(float(row.get("pure_premium_vnd", 0)))
        except (ValueError, TypeError):
            pass

    if not current_vals:
        return {"value": 0.0, "predictions_evaluated": 0}

    n_bins = len(baseline_props)
    lo, hi = edges[0], edges[-1]
    if lo == hi:
        return {"value": 0.0, "predictions_evaluated": len(current_vals)}

    current_counts = [0] * n_bins
    for v in current_vals:
        idx = min(int((v - lo) / (hi - lo) * n_bins), n_bins - 1)
        if idx < 0:
            idx = 0
        current_counts[idx] += 1
    total = sum(current_counts)
    current_props = [c / total if total > 0 else 0.0 for c in current_counts]

    psi = 0.0
    for e, a in zip(baseline_props, current_props):
        e_eps = max(e, 1e-6)
        a_eps = max(a, 1e-6)
        psi += (a_eps - e_eps) * math.log(a_eps / e_eps)

    return {"value": round(psi, 4), "predictions_evaluated": len(current_vals)}


def compute_calibration_drift(baseline: dict, outcomes_df: list[dict], config: dict) -> dict:
    """Compute calibration drift from baseline reference vs actual outcomes.

    Pure function: takes baseline artifact (calibration_reference list) and
    a list of outcome dicts (with quote_id, actual_loss_vnd, pure_premium_vnd).
    Returns calibration drift metric with status and bins_evaluated.

    Calibration drift = mean absolute relative error between baseline actual_rate
    and current actual_rate per prediction bin.

    If insufficient data (below calibration_min_total_outcomes or
    calibration_min_samples_per_bin), returns status "insufficient_data".
    """
    cal_ref = baseline.get("calibration_reference", [])
    if not cal_ref or not outcomes_df:
        return {"value": 0.0, "status": "insufficient_data", "bins_evaluated": 0}

    min_total = config.get("calibration_min_total_outcomes", 100)
    min_per_bin = config.get("calibration_min_samples_per_bin", 20)

    if len(outcomes_df) < min_total:
        return {"value": 0.0, "status": "insufficient_data", "bins_evaluated": 0}

    pred_bins = baseline.get("prediction_bins", {})
    edges = pred_bins.get("edges", [])
    if not edges:
        return {"value": 0.0, "status": "insufficient_data", "bins_evaluated": 0}

    n_bins = len(cal_ref)
    lo, hi = edges[0], edges[-1]
    if lo == hi:
        return {"value": 0.0, "status": "insufficient_data", "bins_evaluated": 0}

    current_rates = []
    baseline_rates = []
    bins_evaluated = 0

    for i, ref_bin in enumerate(cal_ref):
        lo_b = edges[i]
        hi_b = edges[i + 1] if i < n_bins - 1 else hi + 1
        mask_outcomes = []
        for o in outcomes_df:
            try:
                pred = float(o.get("pure_premium_vnd", 0))
                if lo_b <= pred < hi_b:
                    mask_outcomes.append(o)
            except (ValueError, TypeError):
                pass

        if len(mask_outcomes) < min_per_bin:
            continue

        total_loss = sum(float(o.get("actual_loss_vnd", 0)) for o in mask_outcomes)
        current_rate = total_loss / len(mask_outcomes) if mask_outcomes else 0.0
        baseline_rate = ref_bin.get("actual_rate", 0.0)

        current_rates.append(current_rate)
        baseline_rates.append(baseline_rate)
        bins_evaluated += 1

    if bins_evaluated == 0:
        return {"value": 0.0, "status": "insufficient_data", "bins_evaluated": 0}

    drift_values = []
    for base, curr in zip(baseline_rates, current_rates):
        if base > 0:
            drift_values.append(abs(curr - base) / base)
        elif curr > 0:
            drift_values.append(1.0)
        else:
            drift_values.append(0.0)

    mean_drift = sum(drift_values) / len(drift_values)
    return {
        "value": round(mean_drift, 4),
        "status": "sufficient_data",
        "bins_evaluated": bins_evaluated,
    }


# ── Orchestration / data loading functions ─────────────────────────────────

def load_baseline(line: str) -> dict | None:
    """Load baseline drift artifact for a line from reports/modeling/baselines/."""
    path = BASELINES_DIR / f"{line}_baseline.json"
    if not path.exists():
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_current_quotes(line: str, window_days: int) -> list[dict]:
    """Load recent quotes from pricing_db for a line within the window.

    Returns list of dicts with feature columns + pure_premium_vnd.
    """
    try:
        import psycopg2
        import os

        host = os.environ.get("PRICING_DB_HOST", "localhost")
        port = os.environ.get("PRICING_DB_PORT", "5440")
        user = os.environ.get("POSTGRES_USER", "platform_user")
        password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
        dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")

        conn = psycopg2.connect(host=host, port=port, user=user, password=password, dbname=dbname)
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT quote_id, line, profile, pure_premium_vnd, created_at
                    FROM quote
                    WHERE line = %s
                      AND created_at >= NOW() - INTERVAL '%s days'
                    ORDER BY created_at DESC
                    """,
                    (line, window_days),
                )
                rows = cur.fetchall()
                result = []
                for row in rows:
                    entry = {"quote_id": str(row[0]), "line": row[1], "pure_premium_vnd": row[3]}
                    profile = row[2] if row[2] else {}
                    if isinstance(profile, str):
                        try:
                            profile = json.loads(profile)
                        except (json.JSONDecodeError, TypeError):
                            profile = {}
                    if isinstance(profile, dict):
                        entry.update(profile)
                    result.append(entry)
                return result
        finally:
            conn.close()
    except Exception:
        return []


def load_outcomes(line: str, window_days: int) -> list[dict]:
    """Load claim outcomes joined with quote predictions for calibration drift.

    Returns list of dicts with quote_id, actual_loss_vnd, pure_premium_vnd.
    """
    try:
        import psycopg2
        import os

        host = os.environ.get("PRICING_DB_HOST", "localhost")
        port = os.environ.get("PRICING_DB_PORT", "5440")
        user = os.environ.get("POSTGRES_USER", "platform_user")
        password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
        dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")

        conn = psycopg2.connect(host=host, port=port, user=user, password=password, dbname=dbname)
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT co.claim_id, co.quote_id, co.actual_loss_vnd,
                           q.pure_premium_vnd, co.settled_at
                    FROM claim_outcome co
                    LEFT JOIN quote q ON co.quote_id = q.quote_id
                    WHERE COALESCE(q.line, co.line) = %s
                      AND co.settled_at >= NOW() - INTERVAL '%s days'
                    ORDER BY co.settled_at DESC
                    """,
                    (line, window_days),
                )
                rows = cur.fetchall()
                result = []
                for row in rows:
                    result.append({
                        "claim_id": str(row[0]),
                        "quote_id": str(row[1]) if row[1] else None,
                        "actual_loss_vnd": row[2],
                        "pure_premium_vnd": row[3] if row[3] else 0,
                    })
                return result
        finally:
            conn.close()
    except Exception:
        return []


def evaluate_line(line: str, config: dict,
                  baseline: dict | None = None,
                  current_quotes: list[dict] | None = None,
                  outcomes: list[dict] | None = None) -> dict:
    """Compute all 3 drift metrics for a line and determine if recalibration is needed.

    When baseline/current_quotes/outcomes are supplied directly, uses them
    (for testing). Otherwise loads from DB/filesystem.
    """
    psi_threshold = config.get("drift_threshold_psi", 0.2)
    pred_psi_threshold = config.get("drift_threshold_prediction_psi", 0.2)
    cal_threshold = config.get("drift_threshold_calibration", 0.15)
    window_days = config.get("drift_window_days", 30)

    if baseline is None:
        baseline = load_baseline(line)
    if current_quotes is None:
        current_quotes = load_current_quotes(line, window_days)
    if outcomes is None:
        outcomes = load_outcomes(line, window_days)

    if baseline is None:
        return {
            "line": line,
            "feature_psi": {"value": 0.0, "threshold": psi_threshold, "needs_recalibration": False, "features_evaluated": 0},
            "prediction_psi": {"value": 0.0, "threshold": pred_psi_threshold, "needs_recalibration": False, "predictions_evaluated": 0},
            "calibration": {"value": 0.0, "threshold": cal_threshold, "needs_recalibration": False, "status": "no_baseline", "bins_evaluated": 0},
            "needs_recalibration": False,
        }

    feature_result = compute_feature_drift(baseline, current_quotes)
    prediction_result = compute_prediction_drift(baseline, current_quotes)
    calibration_result = compute_calibration_drift(baseline, outcomes, config)

    feature_drift = feature_result["value"] > psi_threshold
    prediction_drift = prediction_result["value"] > pred_psi_threshold
    cal_drift = (calibration_result["status"] == "sufficient_data"
                 and calibration_result["value"] > cal_threshold)

    needs_recal = feature_drift or prediction_drift or cal_drift

    return {
        "line": line,
        "feature_psi": {
            "value": feature_result["value"],
            "threshold": psi_threshold,
            "needs_recalibration": feature_drift,
            "features_evaluated": feature_result["features_evaluated"],
        },
        "prediction_psi": {
            "value": prediction_result["value"],
            "threshold": pred_psi_threshold,
            "needs_recalibration": prediction_drift,
            "predictions_evaluated": prediction_result["predictions_evaluated"],
        },
        "calibration": {
            "value": calibration_result["value"],
            "threshold": cal_threshold,
            "needs_recalibration": cal_drift,
            "status": calibration_result["status"],
            "bins_evaluated": calibration_result["bins_evaluated"],
        },
        "needs_recalibration": needs_recal,
    }


def persist_flags(results: list[dict]):
    """Persist drift flags to the model_drift_flag table in pricing_db.

    Now persists 3 metrics per line: feature_psi, prediction_psi, calibration.
    """
    import psycopg2
    import os

    host = os.environ.get("PRICING_DB_HOST", "localhost")
    port = os.environ.get("PRICING_DB_PORT", "5440")
    user = os.environ.get("POSTGRES_USER", "platform_user")
    password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
    dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")

    conn = psycopg2.connect(host=host, port=port, user=user, password=password, dbname=dbname)
    try:
        with conn.cursor() as cur:
            for r in results:
                now = datetime.datetime.now(datetime.timezone.utc)
                for metric_name, key in [
                    ("feature_psi", "feature_psi"),
                    ("prediction_psi", "prediction_psi"),
                    ("calibration", "calibration"),
                ]:
                    m = r[key]
                    cur.execute(
                        """INSERT INTO model_drift_flag
                           (flag_id, line, metric, value, threshold, needs_recalibration, computed_at)
                           VALUES (%s, %s, %s, %s, %s, %s, %s)""",
                        (str(uuid.uuid4()), r["line"], metric_name,
                         m["value"], m["threshold"], m["needs_recalibration"], now),
                    )
            conn.commit()
        print(f"Persisted {len(results) * 3} drift flags to model_drift_flag.")
    finally:
        conn.close()


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Model drift monitor")
    parser.add_argument("--dry-run", action="store_true", help="Compute without persisting")
    args = parser.parse_args()

    config = load_config()
    results = []
    for line in LINES:
        r = evaluate_line(line, config)
        results.append(r)
        status = "DRIFT" if r["needs_recalibration"] else "OK"
        print(f"  {line}: FeaturePSI={r['feature_psi']['value']} "
              f"PredPSI={r['prediction_psi']['value']} "
              f"Cal={r['calibration']['value']} ({r['calibration']['status']}) -> {status}")

    drift_lines = [r["line"] for r in results if r["needs_recalibration"]]
    if drift_lines:
        print(f"\nLines needing recalibration: {drift_lines}")
    else:
        print("\nNo lines need recalibration.")

    if not args.dry_run:
        persist_flags(results)


if __name__ == "__main__":
    main()
