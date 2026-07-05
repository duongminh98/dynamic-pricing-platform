"""Local/deploy-neutral offline model lifecycle orchestration.

Runs the governed candidate path end-to-end without serving a candidate:
export dataset -> train candidate artifacts -> compare with current champion ->
register CANDIDATE with full artifact/report lineage. Object storage is optional
and selected by URI scheme (s3:// MinIO locally, gs:// GCS in deploy jobs).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import subprocess
import sys
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
PYTHON = sys.executable

# In-process imports below use the `offline.*` and `app.*` packages; make both
# importable when this script is run directly (python offline/model_lifecycle_pipeline.py).
for _p in (str(ROOT), str(ROOT / "pricing")):
    if _p not in sys.path:
        sys.path.insert(0, _p)


def _repo_path(value: str | pathlib.Path) -> pathlib.Path:
    path = pathlib.Path(value)
    return path if path.is_absolute() else ROOT / path


def _run(script: str, *args: str, env: dict[str, str] | None = None) -> None:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    command = [PYTHON, str(ROOT / script), *args]
    proc = subprocess.run(command, cwd=ROOT, env=merged_env, text=True, capture_output=True, timeout=3600)
    if proc.stdout:
        print(proc.stdout, end="")
    if proc.returncode != 0:
        if proc.stderr:
            print(proc.stderr, file=sys.stderr, end="")
        raise RuntimeError(f"{script} failed with exit code {proc.returncode}")


def _configure_database(database_url: str) -> None:
    os.environ["DATABASE_URL"] = database_url
    os.environ["PRICING_DATABASE_URL"] = database_url


def _bootstrap_reference_data_if_configured() -> None:
    if not os.environ.get("REFERENCE_DATA_URI"):
        return
    if str(ROOT / "pricing") not in sys.path:
        sys.path.insert(0, str(ROOT / "pricing"))
    from app.bootstrap_reference_data import bootstrap_reference_data

    bootstrap_reference_data()


def _current_champion_id(line: str) -> str:
    if str(ROOT / "pricing") not in sys.path:
        sys.path.insert(0, str(ROOT / "pricing"))
    from app.database import ChampionAssignment, SessionLocal

    db = SessionLocal()
    try:
        assignment = db.query(ChampionAssignment).filter(
            ChampionAssignment.line == line,
            ChampionAssignment.is_current.is_(True),
        ).first()
        if assignment is None:
            raise RuntimeError(f"No current champion assignment for line={line}")
        return assignment.model_version_id
    finally:
        db.close()


def _candidate_files(candidate_dir: pathlib.Path, line: str, family: str) -> list[pathlib.Path]:
    normalized = "freqsev" if family == "freq_sev" else family
    if normalized == "freqsev":
        files = [
            candidate_dir / f"{line}__lgb_freq.joblib",
            candidate_dir / f"{line}__lgb_sev.joblib",
        ]
    elif normalized == "tw":
        files = [candidate_dir / f"{line}__lgb_tw.joblib"]
    else:
        raise ValueError(f"Unsupported candidate family: {family}")
    missing = [str(path) for path in files if not path.exists()]
    if missing:
        raise FileNotFoundError(f"Missing candidate artifact(s): {missing}")
    return files


def _upload_files(files: list[pathlib.Path], base_uri: str | None) -> list[str]:
    if not base_uri:
        return [str(path) for path in files]
    from offline.object_storage import upload_file

    prefix = base_uri.rstrip("/")
    uris: list[str] = []
    for path in files:
        uri = f"{prefix}/{path.name}"
        upload_file(path, uri)
        uris.append(uri)
    return uris


def _upload_report(path: pathlib.Path, base_uri: str | None) -> str:
    if not base_uri:
        return str(path)
    from offline.object_storage import upload_file

    uri = f"{base_uri.rstrip('/')}/{path.name}"
    upload_file(path, uri)
    return uri


def run_pipeline(
    *,
    line: str,
    database_url: str,
    dataset_version_id: str,
    work_dir: pathlib.Path,
    object_storage_uri: str | None,
    family: str,
) -> dict:
    _configure_database(database_url)
    _bootstrap_reference_data_if_configured()
    run_id = f"{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    output_root = work_dir / dataset_version_id / line / run_id
    dataset_dir = output_root / "dataset"
    candidate_dir = output_root / "models"
    reports_dir = output_root / "reports"
    baseline_dir = output_root / "baselines"
    for directory in (dataset_dir, candidate_dir, reports_dir, baseline_dir):
        directory.mkdir(parents=True, exist_ok=True)

    run_uri = f"{object_storage_uri.rstrip('/')}/{dataset_version_id}/{line}/{run_id}" if object_storage_uri else None
    dataset_uri = f"{run_uri}/dataset" if run_uri else None
    models_uri = f"{run_uri}/models" if run_uri else None
    reports_uri = f"{run_uri}/reports" if run_uri else None

    export_args = [
        "--database-url", database_url,
        "--output-dir", str(dataset_dir),
        "--dataset-version-id", dataset_version_id,
        "--register-registry",
        "--created-by", "model-lifecycle-pipeline",
    ]
    if dataset_uri:
        export_args.extend(["--object-storage-uri", dataset_uri])
    _run("offline/build_training_dataset_from_pricing_db.py", *export_args)

    train_env = {
        "PRICING_TRAIN_DATA_DIR": str(dataset_dir),
        "PRICING_BASE_MODELS_DIR": os.environ.get("PRICING_MODELS_DIR", str(ROOT / "reports" / "modeling" / "models")),
        "PRICING_MODEL_OUTPUT_DIR": str(candidate_dir),
        "PRICING_BASELINE_OUTPUT_DIR": str(baseline_dir),
    }
    _run("offline/train_pricing_models.py", "--line", line, env=train_env)

    champion_id = _current_champion_id(line)
    comparison_path = reports_dir / f"{line}_comparison.json"
    _run(
        "offline/compare_candidate_to_champion.py",
        "--line", line,
        "--dataset-dir", str(dataset_dir),
        "--candidate-artifact-dir", str(candidate_dir),
        "--candidate-algorithm", "lgb",
        "--candidate-family", family,
        "--champion-model-version-id", champion_id,
        "--output-file", str(comparison_path),
    )

    from offline.retrain_trigger import run_monotonic_gate, run_smoothness_gate

    monotonic_ok, monotonic_msg = run_monotonic_gate(line, models_dir=candidate_dir, algorithm="lgb")
    if not monotonic_ok:
        raise RuntimeError(f"Monotonic gate failed: {monotonic_msg}")
    smoothness_ok, smoothness_msg = run_smoothness_gate(line)
    if not smoothness_ok:
        raise RuntimeError(f"Smoothness gate failed: {smoothness_msg}")

    artifact_paths = _candidate_files(candidate_dir, line, family)
    artifact_uris = _upload_files(artifact_paths, models_uri)
    comparison_uri = _upload_report(comparison_path, reports_uri)

    from offline.register_candidate_model import register_candidate

    result = register_candidate(
        line=line,
        dataset_version_id=dataset_version_id,
        artifact_uri=",".join(artifact_uris),
        comparison_report_uri=comparison_uri,
        monotonic_passed=True,
        smoothness_passed=True,
        registered_by="model-lifecycle-pipeline",
    )
    return {
        **result,
        "line": line,
        "dataset_version_id": dataset_version_id,
        "run_id": run_id,
        "dataset_uri": dataset_uri or str(dataset_dir),
        "artifact_uri": artifact_uris,
        "comparison_report_uri": comparison_uri,
        "promoted": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the offline model lifecycle and register a candidate")
    parser.add_argument("--line", required=True, choices=["health", "motorbike", "car", "home", "accident", "travel"])
    parser.add_argument("--database-url", default=os.environ.get("DATABASE_URL", "postgresql+psycopg2://platform_user:platform_password_dev_only@localhost:5440/pricing_db"))
    parser.add_argument("--dataset-version-id", default=None)
    parser.add_argument("--work-dir", default="data/model_lifecycle_runs")
    parser.add_argument("--object-storage-uri", default=os.environ.get("LIFECYCLE_OBJECT_STORAGE_URI"), help="Optional s3:// or gs:// base prefix for dataset/model/report artifacts")
    parser.add_argument("--candidate-family", choices=["freqsev", "freq_sev", "tw"], default="freqsev")
    args = parser.parse_args()

    dataset_version_id = args.dataset_version_id or f"ds-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    result = run_pipeline(
        line=args.line,
        database_url=args.database_url,
        dataset_version_id=dataset_version_id,
        work_dir=_repo_path(args.work_dir),
        object_storage_uri=args.object_storage_uri,
        family=args.candidate_family,
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
