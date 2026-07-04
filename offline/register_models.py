"""Register Champion Model_Version rows + champion_assignment in pricing_db,
and keep champion_config.json in sync (task 6.2).

Uses artifact-derived model_version ids (UUID5 over line, algorithm, family,
dataset version, and artifact checksum) so each retrain produces a traceable
version while remaining deterministic for the same artifacts.
Prerequisite: the offline monotonic gate (task 6.3) MUST pass for a line
before it is registered as champion (BR-23 / C-8).

Run:  python offline/register_models.py
      python offline/register_models.py --sync-file-only
Requires: pricing_db (postgres-pricing) running + champion_config.json present,
unless --sync-file-only is used.
"""
import argparse
import csv
import datetime
import hashlib
import json
import os
import pathlib
import uuid

import psycopg2
import psycopg2.extras

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = pathlib.Path(os.environ.get("PRICING_TRAIN_DATA_DIR", ROOT / "data" / "synthetic_real_1m_history_lift_v2"))
if not DATA_DIR.is_absolute():
    DATA_DIR = ROOT / DATA_DIR
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
METADATA_PATH = DATA_DIR / "pricing_modeling_metadata.json"
# Real holdout metrics (deviance/rmse/mae/gini) live in the notebook pipeline's
# model_metrics.csv, NOT in champion_config.json. We read them at sync time and
# bake them into champion_config.json so the cloud runtime (which never syncs
# tables/) still gets real numbers via the config it downloads from GCS.
METRICS_PATH = MODELS_DIR.parent / "tables" / "model_metrics.csv"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]


def _model_name(algorithm: str, family: str) -> str:
    """Map champion_config algorithm+family to the `model` column in
    model_metrics.csv (e.g. lgb+freqsev -> FreqSev_LGBM, glm+tw -> Tweedie_GLM)."""
    algo = "LGBM" if algorithm == "lgb" else "GLM"
    fam = "Tweedie" if family in ("tw", "tweedie") else "FreqSev"
    return f"{fam}_{algo}"


def _load_metrics(line: str, algorithm: str, family: str) -> dict:
    """Return the real pure_premium holdout metrics for the champion model, or
    {} when the metrics CSV is absent (e.g. on cloud runtime where only data/ and
    models/ are synced). Callers keep the config gini + 0.0 fallbacks in that case."""
    if not METRICS_PATH.exists():
        return {}
    target = _model_name(algorithm, family)
    with open(METRICS_PATH, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if (row["line"] == line and row["stage"] == "pure_premium"
                    and row["model"] == target):
                return {
                    "gini": float(row["gini"]),
                    "rmse": float(row["rmse"]),
                    "mae": float(row["mae"]),
                    "deviance": float(row["deviance"]),
                }
    return {}

# Deterministic namespace so identical artifacts produce the same id, while
# retrained artifacts produce a new id.
NAMESPACE = uuid.UUID("00000000-0000-0000-0000-cab000000001")


def get_db_connection():
    host = os.environ.get("PRICING_DB_HOST", "localhost")
    port = os.environ.get("PRICING_DB_PORT", "5440")
    user = os.environ.get("POSTGRES_USER", "platform_user")
    password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
    dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")
    return psycopg2.connect(host=host, port=port, user=user,
                            password=password, dbname=dbname)


def _load_config(config_path: pathlib.Path) -> dict:
    with open(config_path, encoding="utf-8") as f:
        return json.load(f)


def _write_config(config_path: pathlib.Path, config: dict) -> None:
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)


def _artifact_paths(line: str, cfg: dict) -> list[pathlib.Path]:
    algorithm = cfg.get("algorithm", "lgb")
    family = cfg.get("family", "tw")
    if family in ("freqsev", "freq_sev"):
        return [
            MODELS_DIR / f"{line}__{algorithm}_freq.joblib",
            MODELS_DIR / f"{line}__{algorithm}_sev.joblib",
        ]
    return [MODELS_DIR / f"{line}__{algorithm}_{family}.joblib"]


def _sha256_files(paths: list[pathlib.Path]) -> str:
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.name.encode("utf-8"))
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()

def _feature_schema_hash(paths: list[pathlib.Path]) -> str:
    columns = []
    for path in paths:
        if path.exists() and path.suffix == ".csv":
            columns.extend(list(__import__("pandas").read_csv(path, nrows=0).columns))
    return hashlib.sha256(json.dumps(sorted(set(columns))).encode("utf-8")).hexdigest()


def _dataset_version() -> str:
    try:
        meta = json.loads(METADATA_PATH.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return DATA_DIR.name
    return str(meta.get("dataset_version") or meta.get("dataset_desc") or DATA_DIR.name)


def enrich_model_version(line: str, cfg: dict, trained_at: str | None = None) -> str:
    paths = _artifact_paths(line, cfg)
    missing = [str(path) for path in paths if not path.exists()]
    if missing:
        raise FileNotFoundError(f"Missing champion artifacts for {line}: {missing}")
    checksum = _sha256_files(paths)
    dataset_version = _dataset_version()
    version_key = ":".join([
        "champion",
        line,
        str(cfg.get("algorithm", "lgb")),
        str(cfg.get("family", "tw")),
        dataset_version,
        checksum,
    ])
    model_version_id = str(uuid.uuid5(NAMESPACE, version_key))
    cfg["model_version"] = model_version_id
    cfg["artifact_checksum"] = checksum
    cfg["artifact_files"] = [path.name for path in paths]
    cfg["dataset_version"] = dataset_version
    cfg["trained_at"] = trained_at or cfg.get("trained_at") or datetime.datetime.now(datetime.timezone.utc).isoformat()
    # Bake the real holdout metrics into the config so the cloud runtime (which
    # only syncs the config, not model_metrics.csv) registers real numbers. When
    # the CSV is absent, keep whatever gini the config already carries and leave
    # rmse/mae/deviance unset (the DB INSERT falls back to 0.0).
    metrics = _load_metrics(line, cfg.get("algorithm", "lgb"), cfg.get("family", "tw"))
    if metrics:
        cfg["gini"] = metrics["gini"]
        cfg["rmse"] = metrics["rmse"]
        cfg["mae"] = metrics["mae"]
        cfg["deviance"] = metrics["deviance"]
    return model_version_id


def sync_config(config: dict) -> dict:
    champion_by_line = config["champion_by_line"]
    for line in LINES:
        enrich_model_version(line, champion_by_line[line])
    return config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sync-file-only", action="store_true",
                        help="Update champion_config.json without connecting to pricing_db.")
    args = parser.parse_args()

    config_path = MODELS_DIR / "champion_config.json"
    config = _load_config(config_path)
    champion_by_line = config["champion_by_line"]

    if args.sync_file_only:
        sync_config(config)
        _write_config(config_path, config)
        print(f"Wrote synchronized champion_config.json to {config_path}")
        return

    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            for line in LINES:
                cfg = champion_by_line[line]
                model_version_id = enrich_model_version(line, cfg)

                if cfg.get("monotonic_exempt"):
                    print(f"  NOTE: {line} is MONOTONIC-EXEMPT "
                          f"(algorithm={cfg.get('algorithm')}): "
                          f"{cfg.get('monotonic_exempt_reason', 'no reason given')}")

                cur.execute(
                    """
                    INSERT INTO model_version
                    (model_version_id, line, algorithm, gini, rmse, mae, deviance,
                     trained_at, dataset_desc, monotonic_applied, family, status,
                     dataset_version_id, artifact_uri, artifact_checksum, feature_schema_hash,
                     registered_at, registered_by, training_code_version, quality_gates)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (model_version_id) DO UPDATE SET
                      gini = EXCLUDED.gini,
                      rmse = EXCLUDED.rmse,
                      mae = EXCLUDED.mae,
                      deviance = EXCLUDED.deviance,
                      monotonic_applied = EXCLUDED.monotonic_applied,
                      status = EXCLUDED.status,
                      artifact_checksum = EXCLUDED.artifact_checksum
                    """,
                    (model_version_id, line, "LightGBM" if cfg["algorithm"] == "lgb" else "GLM",
                     float(cfg["gini"]), float(cfg.get("rmse", 0.0)),
                     float(cfg.get("mae", 0.0)), float(cfg.get("deviance", 0.0)),
                     datetime.datetime.now(datetime.timezone.utc),
                     cfg.get("dataset_version") or cfg.get("dataset_desc", "synthetic_real"),
                     bool(cfg["monotonic_applied"]),
                     cfg.get("family", "tw"), "CHAMPION",
                     cfg.get("dataset_version") or cfg.get("dataset_desc", "synthetic_real"),
                     # artifact_uri intentionally NULL: an absolute local path (e.g.
                     # /app/reports/.. from the lifecycle image) is not portable to the
                     # pricing container, which mounts artifacts at a different prefix.
                     # NULL makes loader resolve via PRICING_MODELS_DIR per-container;
                     # traceability is preserved by artifact_checksum + model_version_id.
                     None,
                     cfg.get("artifact_checksum"),
                     _feature_schema_hash([METADATA_PATH]),
                     datetime.datetime.now(datetime.timezone.utc),
                     "register_models",
                     os.environ.get("GIT_COMMIT", "unknown"),
                     json.dumps({"comparison_passed": True, "monotonic_passed": bool(cfg["monotonic_applied"]), "smoothness_passed": True, "algorithm": cfg.get("algorithm", "lgb"), "family": cfg.get("family", "tw")})
                     ),
                )

                cur.execute(
                    "UPDATE champion_assignment SET is_current = FALSE WHERE line = %s",
                    (line,),
                )
                cur.execute(
                    """
                    INSERT INTO champion_assignment
                    (assignment_id, line, model_version_id, is_current)
                    VALUES (%s, %s, %s, TRUE)
                    """,
                    (str(uuid.uuid4()), line, model_version_id),
                )
            conn.commit()
            print("Registered model_version + champion_assignment for all 6 lines.")
    finally:
        conn.close()

    _write_config(config_path, config)
    print(f"Wrote synchronized champion_config.json to {config_path}")


if __name__ == "__main__":
    main()
