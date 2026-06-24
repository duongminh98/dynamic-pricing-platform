"""Drift monitor for model lifecycle (task 23.2, R37.7).

Compares per-line:
(a) Feature distribution drift: current input features vs training distribution
    using Population Stability Index (PSI).
(b) Calibration drift: actual-vs-predicted deviation by bin.

When a metric exceeds its configured threshold, sets needs_recalibration=true
for the line in the model_drift_flag table. This flag can feed into the
retrain trigger (task 23.1).

Runs OFFLINE -- never in the serving path (R37.10).

Usage:
  python offline/drift_monitor.py                # compute + persist flags
  python offline/drift_monitor.py --dry-run       # compute without persisting

Requirements: R37.7.
"""
from __future__ import annotations

import json
import math
import pathlib
import sys
import datetime
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data" / "synthetic_real"
CONFIG_PATH = ROOT / "offline" / "retrain_config.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]


def load_config() -> dict:
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


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


def compute_feature_drift(line: str, data_dir: pathlib.Path = DATA_DIR) -> float:
    """Compute average PSI across numeric features for a line.

    Splits the dataset into a 'training' half and a 'current' half, then
    computes PSI per feature and returns the mean. This simulates comparing
    the training distribution against recent production input.
    """
    import csv

    csv_path = data_dir / "policies.csv"
    if not csv_path.exists():
        return 0.0

    rows = []
    with open(csv_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get("line") == line:
                rows.append(row)

    if len(rows) < 10:
        return 0.0

    mid = len(rows) // 2
    train_rows = rows[:mid]
    current_rows = rows[mid:]

    numeric_cols = ["age", "coverage_amount_vnd", "monthly_income_vnd"]
    psis = []
    for col in numeric_cols:
        train_vals = []
        current_vals = []
        for r in train_rows:
            try:
                train_vals.append(float(r[col]))
            except (ValueError, KeyError):
                pass
        for r in current_rows:
            try:
                current_vals.append(float(r[col]))
            except (ValueError, KeyError):
                pass
        if train_vals and current_vals:
            psis.append(population_stability_index(train_vals, current_vals))

    return sum(psis) / len(psis) if psis else 0.0


def compute_calibration_drift(line: str, data_dir: pathlib.Path = DATA_DIR) -> float:
    """Compute calibration drift: actual-vs-predicted deviation.

    Simplified: compares the mean claim frequency in the 'current' half vs
    the 'training' half. A large relative shift indicates calibration drift.
    """
    import csv

    csv_path = data_dir / "policies.csv"
    if not csv_path.exists():
        return 0.0

    rows = []
    with open(csv_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get("line") == line:
                rows.append(row)

    if len(rows) < 10:
        return 0.0

    mid = len(rows) // 2
    train_claims = sum(int(r.get("claim_count", 0) or 0) for r in rows[:mid])
    current_claims = sum(int(r.get("claim_count", 0) or 0) for r in rows[mid:])
    train_exposure = max(len(rows[:mid]), 1)
    current_exposure = max(len(rows[mid:]), 1)

    train_rate = train_claims / train_exposure
    current_rate = current_claims / current_exposure

    if train_rate == 0:
        return abs(current_rate) if current_rate > 0 else 0.0

    return abs(current_rate - train_rate) / train_rate


def evaluate_line(line: str, config: dict) -> dict:
    """Compute drift metrics for a line and determine if recalibration is needed."""
    psi_threshold = config.get("drift_threshold_psi", 0.2)
    cal_threshold = config.get("drift_threshold_calibration", 0.15)

    psi_value = compute_feature_drift(line)
    cal_value = compute_calibration_drift(line)

    psi_drift = psi_value > psi_threshold
    cal_drift = cal_value > cal_threshold
    needs_recal = psi_drift or cal_drift

    return {
        "line": line,
        "psi": {"value": round(psi_value, 4), "threshold": psi_threshold, "needs_recalibration": psi_drift},
        "calibration": {"value": round(cal_value, 4), "threshold": cal_threshold, "needs_recalibration": cal_drift},
        "needs_recalibration": needs_recal,
    }


def persist_flags(results: list[dict]):
    """Persist drift flags to the model_drift_flag table in pricing_db."""
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
                for metric_name in ("psi", "calibration"):
                    m = r[metric_name]
                    cur.execute(
                        """INSERT INTO model_drift_flag
                           (flag_id, line, metric, value, threshold, needs_recalibration, computed_at)
                           VALUES (%s, %s, %s, %s, %s, %s, %s)""",
                        (str(uuid.uuid4()), r["line"], metric_name,
                         m["value"], m["threshold"], m["needs_recalibration"], now),
                    )
            conn.commit()
        print(f"Persisted {len(results) * 2} drift flags to model_drift_flag.")
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
        print(f"  {line}: PSI={r['psi']['value']} (thresh={r['psi']['threshold']}), "
              f"Cal={r['calibration']['value']} (thresh={r['calibration']['threshold']}) -> {status}")

    drift_lines = [r["line"] for r in results if r["needs_recalibration"]]
    if drift_lines:
        print(f"\nLines needing recalibration: {drift_lines}")
    else:
        print("\nNo lines need recalibration.")

    if not args.dry_run:
        persist_flags(results)


if __name__ == "__main__":
    main()
