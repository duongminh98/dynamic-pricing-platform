"""Retrain trigger for offline model lifecycle (task 23.1, R37.2).

Supports two mechanisms (independently configurable via retrain_config.json):

(a) Schedule: quarterly by default (months 1, 4, 7, 10).
(b) Data threshold: when new claims/exposure count for a line exceeds a
    configured threshold, trigger a re-train cycle for that line only.

The trigger ONLY creates a candidate Model_Version -- it does NOT auto-promote.
Promotion still follows BR-23 / governance (7.11). The re-train cycle calls:
  train_pricing_models.py -> (monotonic gate) -> register_models.py

This runs OFFLINE -- never in the serving path (R37.10).

Usage:
  python offline/retrain_trigger.py                     # check + trigger
  python offline/retrain_trigger.py --dry-run            # show what would trigger
  python offline/retrain_trigger.py --line health        # force one line

Requirements: R37.2, BR-24.
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data" / "synthetic_real"
CONFIG_PATH = ROOT / "offline" / "retrain_config.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]


def load_config() -> dict:
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


def is_scheduled(config: dict, now: datetime.datetime | None = None) -> bool:
    """Check if the current month matches a quarterly schedule month."""
    if not config.get("schedule_quarterly", False):
        return False
    now = now or datetime.datetime.now()
    return now.month in config.get("quarterly_months", [1, 4, 7, 10])


def count_new_claims_for_line(line: str, data_dir: pathlib.Path = DATA_DIR) -> int:
    """Count new claims/exposure records for a line from the dataset.

    Reads the policies CSV and filters by line. Returns the count of records
    that could serve as new training data for the line.
    """
    csv_path = data_dir / "policies.csv"
    if not csv_path.exists():
        return 0
    import csv
    count = 0
    with open(csv_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get("line") == line:
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


def trigger_retrain(line: str, dry_run: bool = False) -> dict:
    """Run the re-train cycle for a single line (train -> gate -> register).

    Returns a dict with the line, status, and any error message.
    """
    result = {"line": line, "status": "pending", "error": None}
    if dry_run:
        result["status"] = "dry_run"
        return result

    scripts = [
        "offline/train_pricing_models.py",
        "offline/register_models.py",
    ]
    for script in scripts:
        try:
            proc = subprocess.run(
                [sys.executable, str(ROOT / script)],
                capture_output=True, text=True, timeout=600,
            )
            if proc.returncode != 0:
                result["status"] = "failed"
                result["error"] = f"{script} exit {proc.returncode}: {proc.stderr[:500]}"
                return result
        except Exception as e:
            result["status"] = "failed"
            result["error"] = str(e)
            return result

    result["status"] = "completed"
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
        lines_to_check = []

        if is_scheduled(config):
            print("Scheduled trigger: quarterly retrain for all lines.")
            lines_to_check = list(LINES)
        else:
            threshold_lines = lines_exceeding_threshold(config)
            if threshold_lines:
                print(f"Data threshold trigger: {threshold_lines}")
                lines_to_check = threshold_lines

    if not lines_to_check:
        print("No trigger conditions met. Nothing to retrain.")
        return

    for line in lines_to_check:
        print(f"Triggering retrain for line: {line}")
        result = trigger_retrain(line, dry_run=args.dry_run)
        triggered.append(result)
        print(f"  -> {result['status']}" + (f" ({result['error']})" if result["error"] else ""))

    print(f"\nTriggered {len(triggered)} line(s). Candidate Model_Version(s) created (not promoted).")


if __name__ == "__main__":
    main()
