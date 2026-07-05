"""Retrain trigger for offline model lifecycle (task 23.1, R37.2).

Supports two mechanisms (independently configurable via retrain_config.json):

(a) Schedule: quarterly by default (months 1, 4, 7, 10).
(b) Data threshold: when new claims/exposure count for a line exceeds a
    configured threshold, trigger a re-train cycle for that line only.

What the trigger does (and does NOT do)
---------------------------------------
For each triggered line it CHAINS, in order:

    train  ->  validate  ->  monotonic gate  ->  register CANDIDATE

and stops as soon as a step fails. The monotonic gate MUST pass before the
candidate is registered. The trigger ONLY registers a *candidate* Model_Version
row -- it NEVER touches champion_assignment and therefore NEVER auto-promotes.
Promotion stays governed by BR-23 via the existing governance promote endpoint
(pricing_engine/governance.py), driven by an Administrator. This separation is
the core safety property of R37.2 / BR-24.

This runs OFFLINE -- never in the serving path (R37.10).

Scheduler hook
--------------
The quarterly schedule is meant to be driven by an external scheduler that runs
this script (cron or a GitHub Actions ``schedule`` trigger). The script itself
only DECIDES whether a trigger condition is met today; it does not daemonize.
See offline/README.md for the cron / GitHub Actions ``schedule`` snippet.

Usage:
  python offline/retrain_trigger.py                     # check + trigger
  python offline/retrain_trigger.py --dry-run            # show what would trigger
  python offline/retrain_trigger.py --line health        # force one line

Requirements: R37.2, BR-24.
"""
from __future__ import annotations

import datetime
import json
import os
import pathlib
import subprocess
import sys
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data" / "synthetic_real_1m_history_lift_v2"
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
CONFIG_PATH = ROOT / "offline" / "retrain_config.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]

# Lines whose champion is a GLM are exempt from the artifact-level monotonic
# gate (BR-19 travel exemption, task 20.8b). Mirrors
# pricing/app/config.MONOTONIC_EXEMPT_LINES and the "monotonic_exempt" flag in
# reports/modeling/models/champion_config.json. The gate passes for these lines
# because the GLM coefficient signs are enforced at fit time, not via
# LightGBM-style monotone_constraints.
MONOTONIC_EXEMPT_LINES = frozenset({"travel"})

# Monotone constraint directions the gate expects on tree/LightGBM artifacts.
MONOTONE_COMMON = {
    "coverage_amount_vnd": 1,
    "deductible_vnd": -1,
    "claim_count_12m_prior": 1,
    "claim_count_36m_prior": 1,
    "claim_count_lifetime_prior": 1,
    "total_incurred_36m_prior": 1,
    "avg_incurred_36m_prior": 1,
    "max_incurred_36m_prior": 1,
    "days_since_last_claim_prior": -1,
    "claim_severity_score_prior": 1,
}
MONOTONE_HEALTH = {**MONOTONE_COMMON, "age": 1, "bmi": 1}
MONOTONE_VEHICLE = {**MONOTONE_COMMON, "annual_mileage_km": 1}


def load_config() -> dict:
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


# Trigger-condition detection
def is_scheduled(config: dict, now: datetime.datetime | None = None) -> bool:
    """Check whether today is the configured quarterly retrain day."""
    if not config.get("schedule_quarterly", False):
        return False
    now = now or datetime.datetime.now()
    return (
        now.month in config.get("quarterly_months", [1, 4, 7, 10])
        and now.day == int(config.get("quarterly_day", 1))
    )


def count_new_claims_for_line(line: str, data_dir: pathlib.Path = DATA_DIR) -> int:
    """Count new claims/exposure records for a line from the dataset."""
    csv_path = data_dir / "policies.csv"
    import csv
    count = 0
    if csv_path.exists():
        with open(csv_path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                if row.get("line") == line:
                    count += 1
        return count

    freq_path = data_dir / f"pricing_freq_{line}.csv"
    if not freq_path.exists():
        return 0
    with open(freq_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for _ in reader:
            count += 1
    return count


def lines_exceeding_threshold(config: dict) -> list[str]:
    """Return lines whose new-claims count exceeds the configured threshold."""
    if not config.get("data_threshold_enabled", False):
        return []
    thresholds = config.get("line_thresholds", {})
    result = []
    for line in LINES:
        threshold = thresholds.get(line, 0)
        if threshold <= 0:
            continue
        count = count_new_claims_for_line(line)
        if count >= threshold:
            result.append(line)
    return result


def lines_with_drift(config: dict) -> list[str]:
    """Return lines whose latest model_drift_flag has needs_recalibration=true.

    Reads the same model_drift_flag table that GET /pricing/drift exposes to
    admins, so the trigger source of truth matches what administrators see.
    Disabled entirely when drift_trigger_enabled is false in retrain_config.json.
    """
    if not config.get("drift_trigger_enabled", False):
        return []
    result = []
    try:
        conn = _get_db_connection()
        try:
            with conn.cursor() as cur:
                for line in LINES:
                    cur.execute(
                        """
                        SELECT needs_recalibration FROM model_drift_flag
                        WHERE line = %s
                        ORDER BY computed_at DESC
                        LIMIT 1
                        """,
                        (line,),
                    )
                    row = cur.fetchone()
                    if row and row[0]:
                        result.append(line)
        finally:
            conn.close()
    except Exception:
        pass
    return result


# Pipeline steps are independently monkeypatchable in tests
def _run_script(script_rel: str, *args: str) -> tuple[bool, str]:
    """Run an offline script as a subprocess. Returns (ok, error_message)."""
    try:
        proc = subprocess.run(
            [sys.executable, str(ROOT / script_rel), *args],
            capture_output=True, text=True, timeout=1200,
        )
        if proc.returncode != 0:
            return False, f"{script_rel} exit {proc.returncode}: {proc.stderr[:500]}"
        return True, ""
    except Exception as e:  # noqa: BLE001 - surface any launch failure as a step failure
        return False, f"{script_rel}: {e}"


def run_training(line: str) -> tuple[bool, str]:
    """Step 1: re-fit champion artifacts (offline/train_pricing_models.py)."""
    return _run_script("offline/train_pricing_models.py", "--line", line)


def run_validation(line: str) -> tuple[bool, str]:
    """Step 2: produce validation/comparison metrics (scripts/validate_pricing_models.py)."""
    return _run_script("scripts/validate_pricing_models.py")


def run_smoothness_gate(line: str) -> tuple[bool, str]:
    """Step 4: validate local premium smoothness before candidate registration."""
    if line != "health":
        return True, "not applicable"
    return _run_script("offline/model_smoothness_gate.py", "--line", line)


def _expected_monotone(line: str) -> dict:
    if line == "health":
        return MONOTONE_HEALTH
    return MONOTONE_VEHICLE if line in ("car", "motorbike") else MONOTONE_COMMON


def run_monotonic_gate(line: str, models_dir: pathlib.Path = MODELS_DIR) -> tuple[bool, str]:
    """Step 3: BR-19 monotonic gate. MUST pass before a candidate is registered.

    For monotonic-exempt GLM lines (e.g. travel) the gate passes by construction.
    For all other lines the LightGBM Tweedie artifact must carry monotone_constraints
    matching the design directions.
    """
    if line in MONOTONIC_EXEMPT_LINES:
        return True, "monotonic-exempt (GLM line)"

    import joblib

    path = models_dir / f"{line}__lgb_tw.joblib"
    if not path.exists():
        return False, f"missing artifact {path.name}"
    model = joblib.load(path)
    mc = model.get_params().get("monotone_constraints")
    if mc is None:
        return False, f"{path.name} missing monotone_constraints"
    feature_names = list(model.feature_name_)
    if len(mc) != len(feature_names):
        return False, f"{path.name} constraint/feature length mismatch"
    actual = {f: c for f, c in zip(feature_names, mc) if c != 0}
    expected = _expected_monotone(line)
    for feat, direction in expected.items():
        if actual.get(feat) != direction:
            return False, f"{path.name}: {feat} expected {direction}, got {actual.get(feat)}"
    return True, "ok"


def _get_db_connection():
    import psycopg2
    host = os.environ.get("PRICING_DB_HOST", "localhost")
    port = os.environ.get("PRICING_DB_PORT", "5440")
    user = os.environ.get("POSTGRES_USER", "platform_user")
    password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
    dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")
    return psycopg2.connect(host=host, port=port, user=user, password=password, dbname=dbname)


def _candidate_metadata(line: str) -> dict:
    """Read line metadata (algorithm/gini/monotonic) from champion_config.json."""
    cfg_path = MODELS_DIR / "champion_config.json"
    try:
        with open(cfg_path, encoding="utf-8") as f:
            cfg = json.load(f)
        return cfg.get("champion_by_line", {}).get(line, {})
    except (OSError, json.JSONDecodeError):
        return {}


def register_candidate(line: str) -> dict:
    """Step 4: register a CANDIDATE Model_Version row. NEVER promotes.

    Inserts a new model_version row with a fresh (random) id so it does NOT
    overwrite the deterministic champion id, and crucially does NOT insert or
    update any champion_assignment row. Promotion remains an explicit, governed
    Administrator action (BR-23). Returns a dict including the candidate id and
    ``promoted=False``.
    """
    meta = _candidate_metadata(line)
    candidate_id = str(uuid.uuid4())
    algorithm = "LightGBM" if meta.get("algorithm") == "lgb" else "GLM"
    gini = float(meta.get("gini", 0.0) or 0.0)
    monotonic_applied = bool(meta.get("monotonic_applied", False))
    now = datetime.datetime.now(datetime.timezone.utc)

    conn = _get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO model_version
                (model_version_id, line, algorithm, gini, rmse, mae, deviance,
                 trained_at, dataset_desc, monotonic_applied)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (candidate_id, line, algorithm, gini, 0.0, 0.0, 0.0, now,
                 meta.get("dataset_desc", "synthetic_real"), monotonic_applied),
            )
            # NOTE: deliberately NO champion_assignment write here (no promotion).
        conn.commit()
    finally:
        conn.close()

    return {"candidate_model_version": candidate_id, "line": line, "promoted": False}


def trigger_retrain(line: str, dry_run: bool = False) -> dict:
    """Run the re-train cycle for a single line.

    Chain: train -> validate -> monotonic gate -> smoothness gate -> register
    CANDIDATE. Stops at the first failing step. Both gates MUST pass before the
    candidate is registered. NEVER promotes (promotion stays governed by BR-23).

    Returns a dict with line, status, steps completed, the candidate id (if any),
    and ``promoted`` (always False).
    """
    result = {"line": line, "status": "pending", "error": None,
              "steps": [], "candidate_model_version": None, "promoted": False}

    if dry_run:
        result["status"] = "dry_run"
        return result

    ok, err = run_training(line)
    result["steps"].append("train")
    if not ok:
        result["status"] = "failed"
        result["error"] = f"train failed: {err}"
        return result

    ok, err = run_validation(line)
    result["steps"].append("validate")
    if not ok:
        result["status"] = "failed"
        result["error"] = f"validate failed: {err}"
        return result

    ok, gate_msg = run_monotonic_gate(line)
    result["steps"].append("monotonic_gate")
    if not ok:
        # Gate failed -> abort BEFORE registering anything (BR-19).
        result["status"] = "gate_failed"
        result["error"] = f"monotonic gate failed: {gate_msg}"
        return result

    ok, gate_msg = run_smoothness_gate(line)
    result["steps"].append("smoothness_gate")
    if not ok:
        result["status"] = "gate_failed"
        result["error"] = f"smoothness gate failed: {gate_msg}"
        return result

    candidate = register_candidate(line)
    result["steps"].append("register_candidate")
    result["candidate_model_version"] = candidate["candidate_model_version"]
    result["promoted"] = False
    result["status"] = "candidate_registered"
    return result


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Offline retrain trigger")
    parser.add_argument("--dry-run", action="store_true", help="Show what would trigger without running")
    parser.add_argument("--line", type=str, help="Force trigger for a specific line")
    args = parser.parse_args()

    config = load_config()
    triggered = []

    if args.line:
        lines_to_check = [args.line]
    else:
        # Merge trigger sources: schedule (all lines), data threshold, drift.
        # Dedupe while preserving order.
        lines_to_check = []
        seen = set()

        if is_scheduled(config):
            print("Scheduled trigger: quarterly retrain for all lines.")
            for line in LINES:
                if line not in seen:
                    lines_to_check.append(line)
                    seen.add(line)

        threshold_lines = lines_exceeding_threshold(config)
        for line in threshold_lines:
            if line not in seen:
                print(f"Data threshold trigger: {line}")
                lines_to_check.append(line)
                seen.add(line)

        drift_lines = lines_with_drift(config)
        for line in drift_lines:
            if line not in seen:
                print(f"Drift trigger: {line}")
                lines_to_check.append(line)
                seen.add(line)

    if not lines_to_check:
        print("No trigger conditions met. Nothing to retrain.")
        return 0

    for line in lines_to_check:
        print(f"Triggering retrain for line: {line}")
        result = trigger_retrain(line, dry_run=args.dry_run)
        triggered.append(result)
        suffix = f" ({result['error']})" if result["error"] else ""
        print(f"  -> {result['status']} steps={result['steps']}"
              f" candidate={result['candidate_model_version']}{suffix}")

    print(f"\nTriggered {len(triggered)} line(s). "
          f"Candidate Model_Version(s) created (NOT promoted; promotion is governed by BR-23).")
    failed = [r for r in triggered if r["status"] not in ("candidate_registered", "dry_run")]
    if failed:
        print(f"Retrain failed for {len(failed)} line(s): {[r['line'] for r in failed]}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
