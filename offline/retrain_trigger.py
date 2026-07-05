"""Retrain trigger for offline model lifecycle (task 23.1, R37.2).

Supports two mechanisms (independently configurable via retrain_config.json):

(a) Schedule: quarterly by default (months 1, 4, 7, 10).
(b) Data threshold: when new claims/exposure count for a line exceeds a
    configured threshold, trigger a re-train cycle for that line only.

What the trigger does (and does NOT do)
---------------------------------------
For each triggered line it runs the governed lifecycle pipeline
(offline/model_lifecycle_pipeline.py) in-process:

    export dataset -> train -> compare vs champion -> monotonic gate ->
    smoothness gate -> register CANDIDATE

Every gate MUST pass before the candidate is registered; the pipeline aborts on
the first failure. The trigger ONLY registers a *candidate* Model_Version row --
it NEVER touches champion_assignment and therefore NEVER auto-promotes.
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

ROOT = pathlib.Path(__file__).resolve().parent.parent
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
    """Check if the current month matches a quarterly schedule month."""
    if not config.get("schedule_quarterly", False):
        return False
    now = now or datetime.datetime.now()
    return now.month in config.get("quarterly_months", [1, 4, 7, 10])


def _quarter_period_start(config: dict, now: datetime.datetime) -> datetime.datetime:
    """First day of the current quarterly period.

    The external scheduler runs this script DAILY, but the quarterly schedule is
    meant to fire ONCE per quarter. This returns the start of the most recent
    quarterly month <= now.month so the caller can skip lines already retrained
    within the window (see scheduled_lines_needing_retrain). Without this guard a
    daily cron would retrain every line every day for the whole quarter month.
    """
    months = sorted(config.get("quarterly_months", [1, 4, 7, 10]))
    start_month = next((m for m in reversed(months) if m <= now.month), months[0])
    return datetime.datetime(now.year, start_month, 1, tzinfo=datetime.timezone.utc)


def lines_retrained_since(period_start: datetime.datetime) -> set[str]:
    """Lines with a candidate model_version registered on/after period_start.

    Reads the same model_version rows the lifecycle pipeline writes. On any DB
    error returns an empty set (fail-open: the scheduled retrain still runs).
    """
    try:
        conn = _get_db_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT DISTINCT line FROM model_version
                    WHERE COALESCE(registered_at, trained_at) >= %s
                    """,
                    (period_start,),
                )
                return {row[0] for row in cur.fetchall()}
        finally:
            conn.close()
    except Exception:
        return set()


def scheduled_lines_needing_retrain(config: dict, now: datetime.datetime | None = None) -> list[str]:
    """Lines due for the quarterly refresh that have NOT been retrained this period.

    Empty when the schedule does not fire today. Otherwise LINES minus any line
    that already got a candidate registered since the current quarter started, so
    a daily cron triggers each line at most once per quarter.
    """
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if not is_scheduled(config, now):
        return []
    already = lines_retrained_since(_quarter_period_start(config, now))
    return [line for line in LINES if line not in already]


def count_new_claims_for_line(line: str, window_days: int = 90) -> int:
    """Count settled claim outcomes for a line within the recent window.

    Reads the ``claim_outcome`` read-model (the same source drift_monitor uses),
    NOT a local CSV: the lifecycle image bakes only small reference files, so the
    old ``data/.../policies.csv`` path is absent in deploy jobs and always
    returned 0. On any DB error returns 0 (fail-safe: no spurious retrain).
    """
    try:
        conn = _get_db_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT COUNT(*) FROM claim_outcome
                    WHERE line = %s
                      AND recorded_at >= NOW() - INTERVAL '%s days'
                    """,
                    (line, window_days),
                )
                row = cur.fetchone()
                return int(row[0]) if row else 0
        finally:
            conn.close()
    except Exception:
        return 0


def lines_exceeding_threshold(config: dict) -> list[str]:
    """Return lines whose new-claims count exceeds the configured threshold."""
    if not config.get("data_threshold_enabled", False):
        return []
    thresholds = config.get("line_thresholds", {})
    window_days = config.get("data_threshold_window_days", 90)
    result = []
    for line in LINES:
        threshold = thresholds.get(line, 0)
        if threshold <= 0:
            continue
        count = count_new_claims_for_line(line, window_days)
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


def run_smoothness_gate(line: str) -> tuple[bool, str]:
    """Step 4: validate local premium smoothness before candidate registration."""
    if line != "health":
        return True, "not applicable"
    return _run_script("offline/model_smoothness_gate.py", "--line", line)


def _expected_monotone(line: str) -> dict:
    if line == "health":
        return MONOTONE_HEALTH
    return MONOTONE_VEHICLE if line in ("car", "motorbike") else MONOTONE_COMMON


def _gate_artifact_paths(models_dir: pathlib.Path, line: str) -> list[pathlib.Path]:
    """Artifacts the gate must validate: the ones actually registered/served.

    The lifecycle pipeline registers the ``freqsev`` family (freq + sev) by
    default, so the gate must check THOSE artifacts, not a ``tw`` model that the
    pipeline never serves. Prefer the freq+sev pair; fall back to a lone tw
    artifact for lines/runs trained in the Tweedie family.
    """
    freq = models_dir / f"{line}__lgb_freq.joblib"
    sev = models_dir / f"{line}__lgb_sev.joblib"
    if freq.exists() and sev.exists():
        return [freq, sev]
    tw = models_dir / f"{line}__lgb_tw.joblib"
    if tw.exists():
        return [tw]
    return []


def run_monotonic_gate(line: str, models_dir: pathlib.Path = MODELS_DIR,
                       algorithm: str = "lgb") -> tuple[bool, str]:
    """Step 3: BR-19 monotonic gate. MUST pass before a candidate is registered.

    Exemption mirrors pricing/app/config.is_monotonic_exempt: it applies ONLY to
    a GLM candidate on an exempt line. A tree/LightGBM candidate is NEVER exempt,
    even on an exempt line, because its monotone directions are enforced via
    monotone_constraints rather than at fit time. travel's champion is now lgb
    freqsev (not a GLM), so a bare `line in MONOTONIC_EXEMPT_LINES` check would
    wrongly skip the gate for a LightGBM travel candidate.

    For every non-exempt line each LightGBM artifact that will be served (the
    freq+sev pair, or a tw model) must carry monotone_constraints matching the
    design directions.
    """
    algo = (algorithm or "").strip().lower()
    is_glm = algo in ("glm", "tweedieregressor", "tweedie", "poissonregressor", "gamma")
    if line in MONOTONIC_EXEMPT_LINES and is_glm:
        return True, "monotonic-exempt (GLM line)"

    import joblib

    paths = _gate_artifact_paths(models_dir, line)
    if not paths:
        return False, f"missing artifact {line}__lgb_freq/sev (or tw)"
    expected = _expected_monotone(line)
    for path in paths:
        model = joblib.load(path)
        mc = model.get_params().get("monotone_constraints")
        if mc is None:
            return False, f"{path.name} missing monotone_constraints"
        feature_names = list(model.feature_name_)
        if len(mc) != len(feature_names):
            return False, f"{path.name} constraint/feature length mismatch"
        actual = {f: c for f, c in zip(feature_names, mc) if c != 0}
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


def _database_url_from_env() -> str:
    if os.environ.get("DATABASE_URL"):
        return os.environ["DATABASE_URL"]
    host = os.environ.get("PRICING_DB_HOST", "localhost")
    port = os.environ.get("PRICING_DB_PORT", "5440")
    user = os.environ.get("POSTGRES_USER", "platform_user")
    password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
    dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")
    return f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{dbname}"


def run_lifecycle_pipeline(line: str) -> dict:
    """Run the governed local/deploy-neutral lifecycle and register a candidate.

    This replaces the legacy minimal candidate insert: the pipeline exports a
    dataset, trains from that export, compares against the current champion, and
    calls register_candidate_model.py semantics with full artifact/report lineage.
    """
    from offline.model_lifecycle_pipeline import run_pipeline

    dataset_version_id = os.environ.get("RETRAIN_DATASET_VERSION_ID") or (
        f"retrain-{line}-{datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    )
    return run_pipeline(
        line=line,
        database_url=_database_url_from_env(),
        dataset_version_id=dataset_version_id,
        work_dir=pathlib.Path(os.environ.get("LIFECYCLE_WORK_DIR", "data/model_lifecycle_runs")),
        object_storage_uri=os.environ.get("LIFECYCLE_OBJECT_STORAGE_URI"),
        family=os.environ.get("LIFECYCLE_CANDIDATE_FAMILY", "freqsev"),
    )


def trigger_retrain(line: str, dry_run: bool = False) -> dict:
    """Run the re-train cycle for a single line via the governed lifecycle pipeline.

    Delegates to run_lifecycle_pipeline, which chains export -> train -> compare
    -> monotonic gate -> smoothness gate -> register CANDIDATE in one process and
    aborts at the first failing step (both gates + the champion comparison MUST
    pass before register_candidate_model writes the row). NEVER promotes
    (promotion stays governed by BR-23).

    Returns a dict with line, status, steps completed, the candidate id (if any),
    and ``promoted`` (always False).
    """
    result = {"line": line, "status": "pending", "error": None,
              "steps": [], "candidate_model_version": None, "promoted": False}

    if dry_run:
        result["status"] = "dry_run"
        return result

    try:
        candidate = run_lifecycle_pipeline(line)
    except Exception as exc:  # noqa: BLE001 - lifecycle failures are surfaced as trigger failures
        result["steps"].append("lifecycle_pipeline")
        result["status"] = "failed"
        result["error"] = f"lifecycle pipeline failed: {exc}"
        return result

    result["steps"].append("lifecycle_pipeline")
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

        # Quarterly schedule fires once per quarter even though the external
        # cron runs daily: scheduled_lines_needing_retrain drops lines already
        # retrained since the quarter started.
        for line in scheduled_lines_needing_retrain(config):
            if line not in seen:
                print(f"Scheduled trigger: quarterly retrain for {line}")
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
        return

    for line in lines_to_check:
        print(f"Triggering retrain for line: {line}")
        result = trigger_retrain(line, dry_run=args.dry_run)
        triggered.append(result)
        suffix = f" ({result['error']})" if result["error"] else ""
        print(f"  -> {result['status']} steps={result['steps']}"
              f" candidate={result['candidate_model_version']}{suffix}")

    print(f"\nTriggered {len(triggered)} line(s). "
          f"Candidate Model_Version(s) created (NOT promoted; promotion is governed by BR-23).")


if __name__ == "__main__":
    main()
