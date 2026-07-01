"""Register a candidate model_version with dataset/artifact/comparison lineage."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
PRICING_DIR = ROOT / "pricing"
if str(PRICING_DIR) not in sys.path:
    sys.path.insert(0, str(PRICING_DIR))

from app.database import SessionLocal, AuditTrail, ModelVersion, TrainingDatasetVersion


def _sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_many(paths: list[pathlib.Path]) -> str:
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.name.encode("utf-8"))
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def _artifact_paths(raw: str) -> list[pathlib.Path]:
    return [pathlib.Path(item.strip()) for item in raw.split(",") if item.strip()]


def _git_head() -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except Exception:
        return "unknown"


def _load_json(path: pathlib.Path) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)

def _candidate_id(line: str, dataset_version_id: str, artifact_checksum: str, comparison_path: pathlib.Path) -> str:
    key = f"candidate:{line}:{dataset_version_id}:{artifact_checksum}:{comparison_path}"
    return str(uuid.uuid5(uuid.NAMESPACE_URL, key))


def register_candidate(*, line: str, dataset_version_id: str, artifact_uri: str, comparison_report_uri: str, validation_report_uri: str, fairness_report_uri: str | None, monotonic_passed: bool, smoothness_passed: bool, registered_by: str) -> dict:
    session = SessionLocal()
    try:
        dataset = session.query(TrainingDatasetVersion).filter(TrainingDatasetVersion.dataset_version_id == dataset_version_id).first()
        if dataset is None:
            raise ValueError(f"Unknown dataset version: {dataset_version_id}")

        artifact_paths = _artifact_paths(artifact_uri)
        comparison_path = pathlib.Path(comparison_report_uri)
        validation_path = pathlib.Path(validation_report_uri)
        fairness_path = pathlib.Path(fairness_report_uri) if fairness_report_uri else None
        if not artifact_paths:
            raise FileNotFoundError("No artifact paths provided")
        for required in [*artifact_paths, comparison_path, validation_path]:
            if not required.exists():
                raise FileNotFoundError(required)
        if fairness_path and not fairness_path.exists():
            raise FileNotFoundError(fairness_path)

        comparison_report = _load_json(comparison_path)
        if not comparison_report.get("passed"):
            raise ValueError("Comparison report did not pass")

        validation_report = _load_json(validation_path)
        quality_gates = {
            "comparison_passed": True,
            "monotonic_passed": bool(monotonic_passed),
            "smoothness_passed": bool(smoothness_passed),
            "validation_summary": validation_report,
            "comparison_summary": comparison_report,
            "algorithm": comparison_report.get("candidate", {}).get("algorithm", "lgb"),
            "family": comparison_report.get("candidate", {}).get("family", "tw"),
        }
        if not quality_gates["monotonic_passed"]:
            raise ValueError("Monotonic gate failed")
        if not quality_gates["smoothness_passed"]:
            raise ValueError("Smoothness gate failed")

        artifact_checksum = _sha256_many(artifact_paths)
        now = datetime.datetime.now(datetime.timezone.utc)
        model_version_id = _candidate_id(line, dataset_version_id, artifact_checksum, comparison_path)
        existing = session.query(ModelVersion).filter(ModelVersion.model_version_id == model_version_id).first()
        if existing is not None and existing.status not in (None, "CANDIDATE"):
            raise ValueError(f"Candidate already exists with non-candidate status: {existing.status}")
        model = existing or ModelVersion(model_version_id=model_version_id)
        model.line = line
        model.algorithm = "LightGBM" if quality_gates["algorithm"] == "lgb" else "GLM"
        model.family = quality_gates["family"]
        model.status = "CANDIDATE"
        model.dataset_version_id = dataset_version_id
        model.artifact_uri = artifact_uri
        model.artifact_checksum = artifact_checksum
        model.feature_schema_hash = hashlib.sha256(json.dumps(validation_report.get("feature_columns", []), sort_keys=True).encode("utf-8")).hexdigest()
        model.comparison_report_uri = str(comparison_path)
        model.validation_report_uri = str(validation_path)
        model.fairness_report_uri = str(fairness_path) if fairness_path else None
        model.registered_at = now
        model.registered_by = registered_by
        model.training_code_version = _git_head()
        model.quality_gates = quality_gates
        model.gini = float(comparison_report["candidate"]["metrics"]["gini"])
        model.rmse = float(comparison_report["candidate"]["metrics"]["rmse"])
        model.mae = float(comparison_report["candidate"]["metrics"]["mae"])
        model.deviance = float(comparison_report["candidate"]["metrics"]["deviance"])
        model.trained_at = now
        model.dataset_desc = dataset_version_id
        model.monotonic_applied = bool(monotonic_passed)
        if existing is None:
            session.add(model)
        session.add(AuditTrail(
            audit_id=str(uuid.uuid4()),
            event_type="MODEL_CANDIDATE_REGISTERED",
            actor=registered_by,
            change_detail={
                "line": line,
                "model_version_id": model_version_id,
                "dataset_version_id": dataset_version_id,
                "comparison_report_uri": str(comparison_path),
            },
            created_at=now,
        ))
        session.commit()
        return {"candidate_model_version": model_version_id, "status": "CANDIDATE"}
    finally:
        session.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--line", required=True)
    parser.add_argument("--dataset-version-id", required=True)
    parser.add_argument("--artifact-uri", required=True)
    parser.add_argument("--comparison-report-uri", required=True)
    parser.add_argument("--validation-report-uri", required=True)
    parser.add_argument("--fairness-report-uri")
    parser.add_argument("--registered-by", default="offline-trigger")
    parser.add_argument("--monotonic-passed", action="store_true")
    parser.add_argument("--smoothness-passed", action="store_true")
    args = parser.parse_args()
    result = register_candidate(
        line=args.line,
        dataset_version_id=args.dataset_version_id,
        artifact_uri=args.artifact_uri,
        comparison_report_uri=args.comparison_report_uri,
        validation_report_uri=args.validation_report_uri,
        fairness_report_uri=args.fairness_report_uri,
        monotonic_passed=args.monotonic_passed,
        smoothness_passed=args.smoothness_passed,
        registered_by=args.registered_by,
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
