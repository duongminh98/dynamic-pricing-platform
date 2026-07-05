import datetime
import hashlib
import json
import uuid
from pathlib import Path
import sys

import joblib
import pandas as pd
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

ROOT = Path(__file__).resolve().parents[2]
PRICING_DIR = ROOT / "pricing"
if str(PRICING_DIR) not in sys.path:
    sys.path.insert(0, str(PRICING_DIR))
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.database import Base, ModelVersion, TrainingDatasetVersion
from offline.build_training_dataset_from_pricing_db import _write_manifest, _window_from_frames
from offline.compare_candidate_to_champion import _premium_delta
from offline.register_candidate_model import register_candidate


class DummyModel:
    feature_name_ = ["a", "b"]


def test_manifest_contains_checksums_and_rows(tmp_path: Path):
    freq = tmp_path / "pricing_freq_car.csv"
    sev = tmp_path / "pricing_sev_car.csv"
    meta = tmp_path / "pricing_modeling_metadata.json"
    pd.DataFrame([{"a": 1}, {"a": 2}]).to_csv(freq, index=False)
    pd.DataFrame([{"b": 1}]).to_csv(sev, index=False)
    meta.write_text(json.dumps({"ok": True}), encoding="utf-8")
    manifest_path, manifest = _write_manifest(
        dataset_version_id="ds-1",
        output_dir=tmp_path,
        written=[freq, sev, meta],
        counts={"frequency_rows": 2, "severity_rows": 1, "exposure_rows": 3, "settled_claim_rows": 1, "quote_snapshot_rows": 4},
        started_at=datetime.datetime.now(datetime.timezone.utc),
        completed_at=datetime.datetime.now(datetime.timezone.utc),
        created_by="tester",
    )
    assert manifest_path.exists()
    assert manifest["dataset_version_id"] == "ds-1"
    assert len(manifest["files"]) == 3
    assert all(item["checksum_sha256"] for item in manifest["files"])
    assert manifest["counts"]["frequency_rows"] == 2


def test_source_window_uses_exported_read_model_dates():
    exposures = pd.DataFrame([{"segment_start": "2026-01-01T00:00:00Z", "segment_end": "2026-12-31T00:00:00Z"}])
    claims = pd.DataFrame([{"settled_at": "2026-06-01T00:00:00Z"}])
    snapshots = pd.DataFrame([{"created_at": "2026-02-01T00:00:00Z"}])
    window = _window_from_frames(exposures, claims, snapshots)
    assert window["window_start"].startswith("2026-01-01")
    assert window["window_end"].startswith("2026-12-31")


def test_premium_delta_guardrail_stats_deterministic():
    candidate = pd.Series([100.0, 120.0, 80.0, 130.0]).to_numpy()
    champion = pd.Series([100.0, 100.0, 100.0, 100.0]).to_numpy()
    delta = _premium_delta(candidate, champion)
    assert round(delta["average_premium_delta_pct"], 2) == 7.5
    assert round(delta["p50_premium_delta_pct"], 2) == 10.0
    assert round(delta["over_30_percent_delta_rate_pct"], 2) == 0.0


@pytest.fixture
def lifecycle_db(monkeypatch, tmp_path: Path):
    engine = create_engine(f"sqlite:///{tmp_path / 'lifecycle.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()
    session.add(TrainingDatasetVersion(
        dataset_version_id="ds-1",
        source_type="pricing_db_read_models",
        artifact_uri=str(tmp_path),
        manifest_uri=str(tmp_path / "manifest.json"),
        data_hash="abc",
        export_started_at=datetime.datetime.now(datetime.timezone.utc),
        export_completed_at=datetime.datetime.now(datetime.timezone.utc),
        status="EXPORTED",
        frequency_rows=1,
        severity_rows=1,
        exposure_rows=1,
        settled_claim_rows=1,
        quote_snapshot_rows=1,
        created_by="tester",
        created_at=datetime.datetime.now(datetime.timezone.utc),
    ))
    session.commit()
    from offline import register_candidate_model as rcm
    monkeypatch.setattr(rcm, "SessionLocal", Session)
    yield session, tmp_path
    session.close()


def test_register_candidate_rejects_missing_dataset(tmp_path: Path):
    engine = create_engine(f"sqlite:///{tmp_path / 'missing.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    from offline import register_candidate_model as rcm
    rcm.SessionLocal = Session
    artifact = tmp_path / "car__lgb_tw.joblib"
    artifact.write_bytes(b"artifact")
    comparison = tmp_path / "comparison.json"
    comparison.write_text(json.dumps({"passed": True, "candidate": {"algorithm": "lgb", "family": "tw", "metrics": {"gini": 0.8, "rmse": 1.0, "mae": 0.5, "deviance": 1.1}}}), encoding="utf-8")
    validation = tmp_path / "validation.json"
    validation.write_text(json.dumps({"feature_columns": ["a"]}), encoding="utf-8")
    with pytest.raises(ValueError):
        register_candidate(
            line="car",
            dataset_version_id="missing",
            artifact_uri=str(artifact),
            comparison_report_uri=str(comparison),
            validation_report_uri=str(validation),
            fairness_report_uri=None,
            monotonic_passed=True,
            smoothness_passed=True,
            registered_by="tester",
        )


def test_register_candidate_rejects_missing_artifact(lifecycle_db):
    _session, tmp_path = lifecycle_db
    comparison = tmp_path / "comparison.json"
    comparison.write_text(json.dumps({"passed": True, "candidate": {"algorithm": "lgb", "family": "tw", "metrics": {"gini": 0.8, "rmse": 1.0, "mae": 0.5, "deviance": 1.1}}}), encoding="utf-8")
    validation = tmp_path / "validation.json"
    validation.write_text(json.dumps({"feature_columns": ["a"]}), encoding="utf-8")
    with pytest.raises(FileNotFoundError):
        register_candidate(
            line="car",
            dataset_version_id="ds-1",
            artifact_uri=str(tmp_path / "missing.joblib"),
            comparison_report_uri=str(comparison),
            validation_report_uri=str(validation),
            fairness_report_uri=None,
            monotonic_passed=True,
            smoothness_passed=True,
            registered_by="tester",
        )


def test_register_candidate_rejects_failed_comparison(lifecycle_db):
    _session, tmp_path = lifecycle_db
    artifact = tmp_path / "car__lgb_tw.joblib"
    artifact.write_bytes(b"artifact")
    comparison = tmp_path / "comparison.json"
    comparison.write_text(json.dumps({"passed": False, "candidate": {"algorithm": "lgb", "family": "tw", "metrics": {"gini": 0.8, "rmse": 1.0, "mae": 0.5, "deviance": 1.1}}}), encoding="utf-8")
    validation = tmp_path / "validation.json"
    validation.write_text(json.dumps({"feature_columns": ["a"]}), encoding="utf-8")
    with pytest.raises(ValueError):
        register_candidate(
            line="car",
            dataset_version_id="ds-1",
            artifact_uri=str(artifact),
            comparison_report_uri=str(comparison),
            validation_report_uri=str(validation),
            fairness_report_uri=None,
            monotonic_passed=True,
            smoothness_passed=True,
            registered_by="tester",
        )


def test_register_candidate_writes_enriched_model(lifecycle_db):
    _session, tmp_path = lifecycle_db
    artifact = tmp_path / "car__lgb_tw.joblib"
    artifact.write_bytes(b"artifact")
    comparison = tmp_path / "comparison.json"
    comparison.write_text(json.dumps({"passed": True, "candidate": {"algorithm": "lgb", "family": "tw", "metrics": {"gini": 0.8, "rmse": 1.0, "mae": 0.5, "deviance": 1.1}}}), encoding="utf-8")
    validation = tmp_path / "validation.json"
    validation.write_text(json.dumps({"feature_columns": ["a", "b"]}), encoding="utf-8")
    result = register_candidate(
        line="car",
        dataset_version_id="ds-1",
        artifact_uri=str(artifact),
        comparison_report_uri=str(comparison),
        validation_report_uri=str(validation),
        fairness_report_uri=None,
        monotonic_passed=True,
        smoothness_passed=True,
        registered_by="tester",
    )
    assert result["status"] == "CANDIDATE"
    from offline import register_candidate_model as rcm
    session = rcm.SessionLocal()
    try:
        row = session.query(ModelVersion).filter(ModelVersion.model_version_id == result["candidate_model_version"]).first()
        assert row is not None
        assert row.status == "CANDIDATE"
        assert row.dataset_version_id == "ds-1"
        assert row.comparison_report_uri == str(comparison)
        assert row.quality_gates["comparison_passed"] is True
    finally:
        session.close()


def test_register_candidate_allows_missing_validation_report(lifecycle_db):
    _session, tmp_path = lifecycle_db
    artifact = tmp_path / "car__lgb_tw.joblib"
    joblib.dump(DummyModel(), artifact)
    comparison = tmp_path / "comparison.json"
    comparison.write_text(json.dumps({"passed": True, "candidate": {"algorithm": "lgb", "family": "tw", "metrics": {"gini": 0.8, "rmse": 1.0, "mae": 0.5, "deviance": 1.1}}}), encoding="utf-8")

    result = register_candidate(
        line="car",
        dataset_version_id="ds-1",
        artifact_uri=str(artifact),
        comparison_report_uri=str(comparison),
        monotonic_passed=True,
        smoothness_passed=True,
        registered_by="tester",
    )

    from offline import register_candidate_model as rcm
    session = rcm.SessionLocal()
    try:
        row = session.query(ModelVersion).filter(ModelVersion.model_version_id == result["candidate_model_version"]).first()
        assert row.validation_report_uri is None
        assert row.quality_gates["validation_summary"]["source"] == "model_artifacts"
        expected_hash = hashlib.sha256(json.dumps(["a", "b"], sort_keys=True).encode("utf-8")).hexdigest()
        assert row.feature_schema_hash == expected_hash
    finally:
        session.close()


def test_register_candidate_is_idempotent_for_same_inputs(lifecycle_db):
    _session, tmp_path = lifecycle_db
    artifact = tmp_path / "car__lgb_tw.joblib"
    artifact.write_bytes(b"artifact")
    comparison = tmp_path / "comparison.json"
    comparison.write_text(json.dumps({"passed": True, "candidate": {"algorithm": "lgb", "family": "tw", "metrics": {"gini": 0.8, "rmse": 1.0, "mae": 0.5, "deviance": 1.1}}}), encoding="utf-8")
    validation = tmp_path / "validation.json"
    validation.write_text(json.dumps({"feature_columns": ["a", "b"]}), encoding="utf-8")
    first = register_candidate(line="car", dataset_version_id="ds-1", artifact_uri=str(artifact), comparison_report_uri=str(comparison), validation_report_uri=str(validation), fairness_report_uri=None, monotonic_passed=True, smoothness_passed=True, registered_by="tester")
    second = register_candidate(line="car", dataset_version_id="ds-1", artifact_uri=str(artifact), comparison_report_uri=str(comparison), validation_report_uri=str(validation), fairness_report_uri=None, monotonic_passed=True, smoothness_passed=True, registered_by="tester")
    assert first["candidate_model_version"] == second["candidate_model_version"]
