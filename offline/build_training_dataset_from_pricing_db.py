"""Build retraining datasets from pricing-service read models.

The output shape mirrors the existing offline pricing pipeline:
- pricing_freq_<line>.csv: one row per policy exposure segment
- pricing_sev_<line>.csv: one row per settled claim
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import uuid
import json
import math
import sys
from pathlib import Path
from typing import Any

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
PRICING_DIR = ROOT / "pricing"
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(PRICING_DIR) not in sys.path:
    sys.path.insert(0, str(PRICING_DIR))
from app.pricing_engine.feature_buckets import add_health_bucket_features
from sqlalchemy import create_engine, text
from offline.object_storage import upload_directory, upload_file


def _flatten(prefix: str, value: Any) -> dict[str, Any]:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return {}
    if not isinstance(value, dict):
        return {}
    out: dict[str, Any] = {}
    for key, item in value.items():
        column = f"{prefix}{key}" if prefix else str(key)
        if isinstance(item, dict):
            out.update(_flatten(f"{column}_", item))
        else:
            out[column] = item
    return out


def _load_table(engine, table_name: str) -> pd.DataFrame:
    with engine.connect() as conn:
        return pd.read_sql(text(f"SELECT * FROM {table_name}"), conn)


DEFAULT_LINES = ["health", "motorbike", "car", "home", "accident", "travel"]


def _reference_data_dir() -> Path:
    return Path("data") / "synthetic_real_1m_history_lift_v2"


def _reference_metadata() -> dict[str, Any]:
    path = _reference_data_dir() / "pricing_modeling_metadata.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def _schema_columns(kind: str, line: str) -> list[str] | None:
    path = _reference_data_dir() / f"pricing_{kind}_{line}.csv"
    if not path.exists():
        return None
    return list(pd.read_csv(path, nrows=0).columns)


def _known_lines() -> list[str]:
    # The lifecycle image bakes only the small reference files, NOT the big
    # pricing_freq_*.csv training tables, so the glob is empty there. Fall back to
    # the baked metadata's lines list, then to DEFAULT_LINES as a last resort.
    base_dir = _reference_data_dir()
    discovered = sorted(
        path.name.replace("pricing_freq_", "").replace(".csv", "")
        for path in base_dir.glob("pricing_freq_*.csv")
    )
    if discovered:
        return discovered
    metadata = _reference_metadata()
    lines = metadata.get("lines")
    if isinstance(lines, list) and all(isinstance(line, str) for line in lines):
        return lines
    return DEFAULT_LINES


def _date_str(value: Any) -> str | None:
    if value in (None, "") or pd.isna(value):
        return None
    parsed = pd.to_datetime(value, utc=True, errors="coerce")
    if pd.isna(parsed):
        return None
    return parsed.date().isoformat()


def _year(value: Any) -> int | None:
    if value in (None, "") or pd.isna(value):
        return None
    parsed = pd.to_datetime(value, utc=True, errors="coerce")
    if pd.isna(parsed):
        return None
    return int(parsed.year)


def _severity_level(value: Any) -> str | None:
    try:
        incurred = float(value or 0)
    except (TypeError, ValueError):
        return None
    if incurred >= 50_000_000:
        return "high"
    if incurred >= 10_000_000:
        return "medium"
    return "low"


def _add_line_feature_engineering(row: dict[str, Any], line: str) -> dict[str, Any]:
    if line == "health":
        return add_health_bucket_features(row)
    return row

def _is_present(value: Any) -> bool:
    if value is None:
        return False
    try:
        return not pd.isna(value)
    except (TypeError, ValueError):
        return True

def _exposure_feature_row(exposure) -> dict[str, Any]:
    """Return one exposure row with quote-time features as authoritative input.

    Accepts a pandas Series or a plain mapping (``to_dict("records")`` output),
    so callers can iterate with records instead of the much slower ``iterrows``.
    Policy events carry a risk snapshot, but retraining must reproduce exactly
    what the pricing model saw at quote time whenever a quote feature snapshot is
    available. Pandas merge suffixes colliding quote features with ``_quote``;
    those values intentionally override policy/exposure values.
    """
    row = dict(exposure)
    row.update(_flatten("", row.get("risk_snapshot")))
    for key, value in dict(exposure).items():
        if key.startswith("quote_feature__") and _is_present(value):
            row[key.removeprefix("quote_feature__")] = value
        if key.endswith("_quote") and _is_present(value):
            row[key[:-6]] = value
        elif key not in row and _is_present(value):
            row[key] = value
    return row


def _align_to_training_schema(df: pd.DataFrame, kind: str, line: str) -> pd.DataFrame:
    columns = _schema_columns(kind, line)
    if columns is None:
        return df
    aligned = df.copy()
    for column in columns:
        if column not in aligned.columns:
            aligned[column] = pd.NA
    return aligned[columns]


def _write_metadata(output_dir: Path, dataset_version_id: str | None = None) -> Path:
    source_metadata = _reference_metadata()
    lines = _known_lines()
    metadata = {
        **source_metadata,
        "source": "pricing_db_read_models",
        "dataset_version": dataset_version_id,
        "lines": lines,
        "freq_tables": {line: f"pricing_freq_{line}.csv" for line in lines},
        "sev_tables": {line: f"pricing_sev_{line}.csv" for line in lines},
        "grain_freq": "exposure_record",
        "grain_sev": "claim",
        "exposure_col": "earned_exposure_years",
        "frequency_target": "claim_count",
        "frequency_aux_target": "claim_flag",
        "severity_target": "incurred_amount",
        "offset": "offset_log_exposure = log(earned_exposure_years)",
        "benchmark": "final_premium_vnd",
        "grain": {
            "frequency": "one row per policy exposure segment",
            "severity": "one row per settled claim",
        },
        "target_columns": {
            "frequency": "target_frequency",
            "severity": "incurred_amount",
            "tweedie": "loss_per_exposure",
        },
    }
    metadata_path = output_dir / "pricing_modeling_metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return metadata_path

def _window_from_frames(exposures: pd.DataFrame, claims: pd.DataFrame, snapshots: pd.DataFrame) -> dict[str, str | None]:
    values = []
    for frame, columns in (
        (exposures, ("segment_start", "segment_end", "recorded_at")),
        (claims, ("occurrence_date", "reported_at", "settled_at", "recorded_at")),
        (snapshots, ("created_at",)),
    ):
        if frame.empty:
            continue
        for column in columns:
            if column in frame.columns:
                series = pd.to_datetime(frame[column], utc=True, errors="coerce").dropna()
                values.extend(series.tolist())
    if not values:
        return {"window_start": None, "window_end": None}
    return {"window_start": min(values).isoformat(), "window_end": max(values).isoformat()}

def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def _row_count(path: Path) -> int:
    if path.suffix != ".csv":
        return 1
    try:
        return max(0, sum(1 for _ in open(path, encoding="utf-8")) - 1)
    except FileNotFoundError:
        return 0

def _file_manifest(paths: list[Path], output_dir: Path, object_uri_by_path: dict[Path, str] | None = None) -> list[dict[str, Any]]:
    files = []
    for path in sorted(paths, key=lambda p: p.name):
        name = path.name
        parts = name.replace(".csv", "").split("_")
        kind = "metadata" if name.endswith("metadata.json") else parts[1] if len(parts) >= 3 else "manifest"
        line = None if kind in ("metadata", "manifest") else parts[2]
        files.append({
            "line": line,
            "kind": kind,
            "path": str(path.relative_to(output_dir)),
            "artifact_uri": (object_uri_by_path or {}).get(path, str(path)),
            "row_count": _row_count(path),
            "checksum_sha256": _sha256_file(path),
        })
    return files

def _write_manifest(dataset_version_id: str, output_dir: Path, written: list[Path], counts: dict[str, int], started_at: datetime.datetime, completed_at: datetime.datetime, created_by: str, source_window: dict[str, str | None] | None = None, object_storage_uri: str | None = None) -> tuple[Path, dict[str, Any]]:
    object_uri_by_path: dict[Path, str] = {}
    dataset_artifact_uri = str(output_dir)
    if object_storage_uri:
        uploaded = upload_directory(output_dir, object_storage_uri)
        object_uri_by_path = {path: uri for path, uri in uploaded}
        dataset_artifact_uri = object_storage_uri.rstrip("/")
    files = _file_manifest(written, output_dir, object_uri_by_path)
    combined = hashlib.sha256()
    for item in files:
        combined.update(item["checksum_sha256"].encode("utf-8"))
    manifest = {
        "dataset_version_id": dataset_version_id,
        "source_type": "pricing_db_read_models",
        "source_tables": ["policy_exposure", "claim_outcome", "quote_feature_snapshot"],
        "source_window": source_window or {"window_start": None, "window_end": None},
        "created_at": completed_at.isoformat(),
        "created_by": created_by,
        "artifact_uri": dataset_artifact_uri,
        "files": files,
        "counts": counts,
        "data_hash": combined.hexdigest(),
    }
    path = output_dir / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    if object_storage_uri:
        manifest_uri = f"{object_storage_uri.rstrip('/')}/manifest.json"
        upload_file(path, manifest_uri)
        manifest["manifest_uri"] = manifest_uri
        path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        upload_file(path, manifest_uri)
    else:
        manifest["manifest_uri"] = str(path)
    return path, manifest

def _register_dataset(database_url: str, manifest_path: Path, manifest: dict[str, Any], started_at: datetime.datetime, completed_at: datetime.datetime) -> None:
    engine = create_engine(database_url)
    try:
        with engine.begin() as conn:
            conn.execute(text("""
                INSERT INTO training_dataset_version
                (dataset_version_id, source_type, artifact_uri, manifest_uri, data_hash,
                 window_start, window_end, export_started_at, export_completed_at, status, frequency_rows, severity_rows,
                 exposure_rows, settled_claim_rows, quote_snapshot_rows, created_by, created_at)
                VALUES
                (:dataset_version_id, :source_type, :artifact_uri, :manifest_uri, :data_hash,
                 :window_start, :window_end,
                 :export_started_at, :export_completed_at, 'EXPORTED', :frequency_rows, :severity_rows,
                 :exposure_rows, :settled_claim_rows, :quote_snapshot_rows, :created_by, :created_at)
                ON CONFLICT (dataset_version_id) DO UPDATE SET
                  artifact_uri = EXCLUDED.artifact_uri,
                  manifest_uri = EXCLUDED.manifest_uri,
                  data_hash = EXCLUDED.data_hash,
                  window_start = EXCLUDED.window_start,
                  window_end = EXCLUDED.window_end,
                  export_completed_at = EXCLUDED.export_completed_at,
                  frequency_rows = EXCLUDED.frequency_rows,
                  severity_rows = EXCLUDED.severity_rows,
                  exposure_rows = EXCLUDED.exposure_rows,
                  settled_claim_rows = EXCLUDED.settled_claim_rows,
                  quote_snapshot_rows = EXCLUDED.quote_snapshot_rows
            """), {
                "dataset_version_id": manifest["dataset_version_id"],
                "source_type": manifest["source_type"],
                "artifact_uri": manifest["artifact_uri"],
                "manifest_uri": manifest.get("manifest_uri", str(manifest_path)),
                "data_hash": manifest["data_hash"],
                "window_start": pd.to_datetime(manifest["source_window"].get("window_start"), utc=True).to_pydatetime() if manifest["source_window"].get("window_start") else None,
                "window_end": pd.to_datetime(manifest["source_window"].get("window_end"), utc=True).to_pydatetime() if manifest["source_window"].get("window_end") else None,
                "export_started_at": started_at,
                "export_completed_at": completed_at,
                "frequency_rows": manifest["counts"]["frequency_rows"],
                "severity_rows": manifest["counts"]["severity_rows"],
                "exposure_rows": manifest["counts"]["exposure_rows"],
                "settled_claim_rows": manifest["counts"]["settled_claim_rows"],
                "quote_snapshot_rows": manifest["counts"]["quote_snapshot_rows"],
                "created_by": manifest["created_by"],
                "created_at": completed_at,
            })
            conn.execute(text("DELETE FROM training_dataset_file WHERE dataset_version_id = :id"), {"id": manifest["dataset_version_id"]})
            for item in manifest["files"] + [{"line": None, "kind": "manifest", "artifact_uri": manifest.get("manifest_uri", str(manifest_path)), "row_count": 1, "checksum_sha256": _sha256_file(manifest_path)}]:
                conn.execute(text("""
                    INSERT INTO training_dataset_file
                    (file_id, dataset_version_id, line, kind, artifact_uri, row_count, checksum_sha256, created_at)
                    VALUES (:file_id, :dataset_version_id, :line, :kind, :artifact_uri, :row_count, :checksum_sha256, :created_at)
                """), {
                    "file_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{manifest['dataset_version_id']}:{item['artifact_uri']}")),
                    "dataset_version_id": manifest["dataset_version_id"],
                    "line": item["line"],
                    "kind": item["kind"],
                    "artifact_uri": item["artifact_uri"],
                    "row_count": item["row_count"],
                    "checksum_sha256": item["checksum_sha256"],
                    "created_at": completed_at,
                })
    finally:
        engine.dispose()


def build_datasets(database_url: str, output_dir: Path, dataset_version_id: str | None = None) -> list[Path]:
    engine = create_engine(database_url)
    output_dir.mkdir(parents=True, exist_ok=True)

    try:
        exposures = _load_table(engine, "policy_exposure")
        claims = _load_table(engine, "claim_outcome")
        snapshots = _load_table(engine, "quote_feature_snapshot")

        written: list[Path] = []
        if exposures.empty:
            for line in _known_lines():
                for kind in ("freq", "sev"):
                    columns = _schema_columns(kind, line)
                    if columns is None:
                        continue
                    path = output_dir / f"pricing_{kind}_{line}.csv"
                    pd.DataFrame(columns=columns).to_csv(path, index=False)
                    written.append(path)
            written.append(_write_metadata(output_dir, dataset_version_id))
            return written

        if not snapshots.empty:
            snapshot_features = pd.DataFrame([
                {"quote_id": row["quote_id"], **_flatten("quote_feature__", row.get("feature_set"))}
                for _, row in snapshots.iterrows()
            ])
            exposures = exposures.merge(snapshot_features, on="quote_id", how="left", suffixes=("", "_quote"))

        settled_claims = claims[claims["settled_at"].notna()].copy() if not claims.empty else claims
        for line, line_exposures in exposures.groupby("line"):
            line_claims = settled_claims[settled_claims["line"] == line].copy() if not settled_claims.empty else pd.DataFrame()

            # Iterate exposures as records (far cheaper than iterrows). First
            # occurrence per (policy_id, seq) wins, matching the old
            # matching_exposure.iloc[0] used to build severity rows.
            exposure_records = line_exposures.to_dict("records")
            expo_by_key: dict[tuple, dict[str, Any]] = {}
            for exposure in exposure_records:
                key = (exposure.get("policy_id"), exposure.get("exposure_segment_seq"))
                expo_by_key.setdefault(key, exposure)

            # Aggregate claims -> exposure ONCE via a keyed join + window filter,
            # instead of re-scanning all claims for every exposure (was O(n*m)).
            claim_count_by_expo: dict[Any, int] = {}
            total_loss_by_expo: dict[Any, int] = {}
            if not line_claims.empty:
                lc = line_claims
                loss = lc["incurred_amount_vnd"].fillna(lc.get("actual_loss_vnd", 0)) \
                    if "incurred_amount_vnd" in lc.columns else lc.get("actual_loss_vnd", pd.Series(0, index=lc.index))
                keyed = pd.DataFrame({
                    "policy_id": lc["policy_id"],
                    "exposure_segment_seq": lc["exposure_segment_seq"],
                    "_occ": pd.to_datetime(lc["occurrence_date"], utc=True, errors="coerce"),
                    "_loss": pd.to_numeric(loss, errors="coerce").fillna(0.0),
                })
                windows = pd.DataFrame({
                    "exposure_id": line_exposures["exposure_id"],
                    "policy_id": line_exposures["policy_id"],
                    "exposure_segment_seq": line_exposures["exposure_segment_seq"],
                    "_start": pd.to_datetime(line_exposures["segment_start"], utc=True, errors="coerce"),
                    "_end": pd.to_datetime(line_exposures["segment_end"], utc=True, errors="coerce"),
                })
                merged = keyed.merge(windows, on=["policy_id", "exposure_segment_seq"], how="inner")
                merged = merged[(merged["_occ"] >= merged["_start"]) & (merged["_occ"] < merged["_end"])]
                if not merged.empty:
                    grouped = merged.groupby("exposure_id")["_loss"].agg(["size", "sum"])
                    claim_count_by_expo = grouped["size"].astype(int).to_dict()
                    total_loss_by_expo = grouped["sum"].astype("int64").to_dict()

            rows = []
            feature_row_by_key: dict[tuple, dict[str, Any]] = {}
            for exposure in exposure_records:
                row = _exposure_feature_row(exposure)
                row = _add_line_feature_engineering(row, line)
                row["policy_effective_date"] = _date_str(exposure.get("segment_start"))
                row["policy_expiration_date"] = _date_str(exposure.get("segment_end"))
                row["policy_year"] = _year(exposure.get("segment_start"))
                eid = exposure.get("exposure_id")
                claim_count = int(claim_count_by_expo.get(eid, 0))
                total_loss = int(total_loss_by_expo.get(eid, 0))
                earned = float(exposure.get("earned_exposure_years") or 0.0)
                row["claim_count"] = claim_count
                row["claim_flag"] = 1 if claim_count > 0 else 0
                row["total_incurred_amount_vnd"] = total_loss
                row["target_frequency"] = claim_count / earned if earned > 0 else 0.0
                row["offset_log_exposure"] = 0.0 if earned <= 0 else math.log(earned)
                row["loss_per_exposure"] = total_loss / earned if earned > 0 else 0.0
                rows.append(row)

            freq_df = _align_to_training_schema(pd.DataFrame(rows), "freq", line)
            freq_path = output_dir / f"pricing_freq_{line}.csv"
            freq_df.to_csv(freq_path, index=False)
            written.append(freq_path)

            sev_rows = []
            for claim in (line_claims.to_dict("records") if not line_claims.empty else []):
                key = (claim.get("policy_id"), claim.get("exposure_segment_seq"))
                if key not in feature_row_by_key:
                    match = expo_by_key.get(key)
                    base = _exposure_feature_row(match) if match is not None else {}
                    base["policy_year"] = _year(base.get("segment_start"))
                    feature_row_by_key[key] = base
                row = {**feature_row_by_key[key], **claim}
                row = _add_line_feature_engineering(row, line)
                row["report_date"] = _date_str(claim.get("reported_at") or claim.get("report_date"))
                row["occurrence_date"] = _date_str(claim.get("occurrence_date"))
                row["incurred_amount"] = claim.get("incurred_amount_vnd") or claim.get("actual_loss_vnd") or 0
                row["paid_amount"] = claim.get("paid_amount_vnd") or claim.get("actual_loss_vnd") or 0
                row["severity_level"] = _severity_level(row["incurred_amount"])
                sev_rows.append(row)
            sev_path = output_dir / f"pricing_sev_{line}.csv"
            _align_to_training_schema(pd.DataFrame(sev_rows), "sev", line).to_csv(sev_path, index=False)
            written.append(sev_path)

        exported_lines = set(exposures["line"].dropna().unique())
        for line in [line for line in _known_lines() if line not in exported_lines]:
            for kind in ("freq", "sev"):
                columns = _schema_columns(kind, line)
                if columns is None:
                    continue
                path = output_dir / f"pricing_{kind}_{line}.csv"
                pd.DataFrame(columns=columns).to_csv(path, index=False)
                written.append(path)

        written.append(_write_metadata(output_dir, dataset_version_id))
        return written
    finally:
        engine.dispose()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output-dir", default="data/pricing_read_model_export")
    parser.add_argument("--dataset-version-id", default=None)
    parser.add_argument("--register-registry", action="store_true")
    parser.add_argument("--created-by", default="offline-exporter")
    parser.add_argument("--object-storage-uri", default=None, help="Optional s3://bucket/prefix URI for immutable dataset artifacts.")
    args = parser.parse_args()
    started_at = datetime.datetime.now(datetime.timezone.utc)
    dataset_version_id = args.dataset_version_id or str(uuid.uuid4())
    written = build_datasets(args.database_url, Path(args.output_dir), dataset_version_id)
    completed_at = datetime.datetime.now(datetime.timezone.utc)
    counts = {
        "frequency_rows": sum(_row_count(path) for path in written if path.name.startswith("pricing_freq_")),
        "severity_rows": sum(_row_count(path) for path in written if path.name.startswith("pricing_sev_")),
        "exposure_rows": 0,
        "settled_claim_rows": 0,
        "quote_snapshot_rows": 0,
    }
    source_window = {"window_start": None, "window_end": None}
    try:
        engine = create_engine(args.database_url)
        with engine.connect() as conn:
            counts["exposure_rows"] = conn.execute(text("SELECT COUNT(*) FROM policy_exposure")).scalar_one()
            counts["settled_claim_rows"] = conn.execute(text("SELECT COUNT(*) FROM claim_outcome WHERE settled_at IS NOT NULL")).scalar_one()
            counts["quote_snapshot_rows"] = conn.execute(text("SELECT COUNT(*) FROM quote_feature_snapshot")).scalar_one()
            exposures = pd.read_sql(text("SELECT segment_start, segment_end, recorded_at FROM policy_exposure"), conn)
            claims = pd.read_sql(text("SELECT occurrence_date, reported_at, settled_at, recorded_at FROM claim_outcome"), conn)
            snapshots = pd.read_sql(text("SELECT created_at FROM quote_feature_snapshot"), conn)
            source_window = _window_from_frames(exposures, claims, snapshots)
    finally:
        engine.dispose()
    manifest_path, manifest = _write_manifest(dataset_version_id, Path(args.output_dir), written, counts, started_at, completed_at, args.created_by, source_window, args.object_storage_uri)
    written.append(manifest_path)
    if args.register_registry:
        _register_dataset(args.database_url, manifest_path, manifest, started_at, completed_at)
    for path in written:
        print(path)


if __name__ == "__main__":
    main()





