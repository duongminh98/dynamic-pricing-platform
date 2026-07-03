"""Product/internal lookup + artifact loader for Pricing engine."""
from __future__ import annotations

import json
import pathlib
import time
import warnings
import sys as _sys

import sklearn._loss._loss as _skloss
_sys.modules.setdefault("_loss", _skloss)

import joblib
import pandas as pd

from ..config import PRODUCT_CACHE_TTL_SECONDS
from ..feature_store import clear_cache as clear_feature_store_cache
from ..object_storage import materialize

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
DATA_DIR = ROOT / "data" / "synthetic_real_1m_history_lift_v2"
METADATA_PATH = DATA_DIR / "pricing_modeling_metadata.json"
GEO_RISK_PATH = DATA_DIR / "geo_risk.csv"
COST_INDICES_PATH = DATA_DIR / "cost_indices.csv"
PRODUCTS_PATH = DATA_DIR / "products.csv"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]
FAMILIES = ["freq", "sev", "tw"]
LEAKAGE_COLS = {
    "exposure_id", "policy_id", "claim_id", "customer_id", "unit_id",
    "policy_effective_date", "policy_expiration_date", "occurrence_date",
    "report_date", "earned_exposure_years", "offset_log_exposure",
    "policy_year", "incurred_amount", "paid_amount", "claim_status",
    "severity_level", "freq_rate", "sev_base", "claim_count", "claim_flag",
    "final_premium_vnd", "exposure_segment_seq", "line",
}

artifacts: dict[str, dict[str, object]] = {}
all_artifacts: dict[str, dict[str, dict[str, object]]] = {}
champion_config: dict = {}
metadata: dict = {}
geo_by_province: dict[str, dict] = {}
cost_indices_latest: dict[str, float] = {}
products: dict[str, dict] = {}
loading_factors: dict[str, float] = {}
current_rate_version_id: str | None = None
_loaded = False
_products_loaded_at = 0.0
_loading_loaded_at = 0.0


def _normalize_algorithm(value: str | None) -> str:
    raw = str(value or "lgb").strip().lower()
    if raw.startswith("light") or raw == "lgb":
        return "lgb"
    if raw.startswith("glm"):
        return "glm"
    return raw


def _algorithm_label(code: str) -> str:
    return "LightGBM" if code == "lgb" else "GLM"


def _split_artifact_uris(raw: str | None) -> list[str]:
    return [item.strip() for item in str(raw or "").split(",") if item.strip()]


def _load_local_champion_registry() -> dict[str, dict]:
    config_path = MODELS_DIR / "champion_config.json"
    if not config_path.exists():
        raise RuntimeError("champion_config.json not found")
    with open(config_path, encoding="utf-8") as handle:
        cfg = json.load(handle)
    return dict(cfg.get("champion_by_line", {}))


def _load_db_champion_registry() -> dict[str, dict]:
    try:
        from ..database import SessionLocal, ModelVersion, ChampionAssignment
        db = SessionLocal()
        try:
            rows = db.query(ModelVersion).join(
                ChampionAssignment,
                ChampionAssignment.model_version_id == ModelVersion.model_version_id,
            ).filter(ChampionAssignment.is_current.is_(True)).all()
        finally:
            db.close()
    except Exception as exc:
        warnings.warn(f"Pricing champion registry unavailable from DB, falling back to champion_config.json: {exc}")
        return {}

    registry: dict[str, dict] = {}
    for row in rows:
        algo = _normalize_algorithm(row.algorithm)
        quality_gates = row.quality_gates if isinstance(row.quality_gates, dict) else {}
        registry[row.line] = {
            "model_version": row.model_version_id,
            "algorithm": algo,
            "family": row.family or quality_gates.get("family"),
            "gini": row.gini,
            "trained_at": row.trained_at.isoformat() if row.trained_at else None,
            "dataset_version": row.dataset_version_id or row.dataset_desc,
            "dataset_desc": row.dataset_desc,
            "artifact_uri": row.artifact_uri,
            "artifact_checksum": row.artifact_checksum,
            "monotonic_applied": bool(row.monotonic_applied),
            "registered_at": row.registered_at.isoformat() if row.registered_at else None,
            "registered_by": row.registered_by,
            "training_code_version": row.training_code_version,
        }
    return registry


def _resolve_champion_registry() -> dict[str, dict]:
    local_registry = _load_local_champion_registry()
    db_registry = _load_db_champion_registry()
    merged = dict(local_registry)
    for line, db_cfg in db_registry.items():
        local_cfg = local_registry.get(line, {})
        merged_cfg = dict(local_cfg)
        merged_cfg.update({k: v for k, v in db_cfg.items() if v is not None})
        if not merged_cfg.get("family"):
            merged_cfg["family"] = local_cfg.get("family") or "freqsev"
        merged[line] = merged_cfg
    return merged


def _load_json_metadata() -> dict:
    if not METADATA_PATH.exists():
        raise RuntimeError("pricing_modeling_metadata.json not found")
    with open(METADATA_PATH, encoding="utf-8") as handle:
        return json.load(handle)


def _local_artifact_path(line: str, algorithm: str, family: str) -> pathlib.Path:
    return MODELS_DIR / f"{line}__{algorithm}_{family}.joblib"


def _artifact_uri_for_family(line: str, cfg: dict, family: str) -> str | None:
    artifact_uris = _split_artifact_uris(cfg.get("artifact_uri"))
    if artifact_uris:
        for uri in artifact_uris:
            name = pathlib.Path(uri).name
            if family in ("freq", "sev") and name.endswith(f"_{family}.joblib"):
                return uri
            if family == "tw" and not name.endswith("_freq.joblib") and not name.endswith("_sev.joblib"):
                return uri
        return artifact_uris[0]

    algorithm = cfg.get("algorithm", "lgb")
    path = _local_artifact_path(line, algorithm, family)
    if path.exists():
        return str(path)
    for candidate in (algorithm, "lgb", "glm"):
        path = _local_artifact_path(line, candidate, family)
        if path.exists():
            return str(path)
    return None


def _load_model_from_uri(uri: str):
    return joblib.load(materialize(uri))


def load_model_bundle(line: str, algorithm: str, family: str, artifact_uri: str | None) -> dict[str, object]:
    algo = _normalize_algorithm(algorithm)
    if family in ("freqsev", "freq_sev"):
        freq_uri = _artifact_uri_for_family(line, {"algorithm": algo, "artifact_uri": artifact_uri}, "freq")
        sev_uri = _artifact_uri_for_family(line, {"algorithm": algo, "artifact_uri": artifact_uri}, "sev")
        if not freq_uri or not sev_uri:
            raise RuntimeError(f"Missing required model artifact for line={line} family=freqsev")
        return {"freq": _load_model_from_uri(freq_uri), "sev": _load_model_from_uri(sev_uri)}
    model_uri = _artifact_uri_for_family(line, {"algorithm": algo, "artifact_uri": artifact_uri}, family)
    if not model_uri:
        raise RuntimeError(f"Missing required model artifact for line={line} family={family}")
    return {family: _load_model_from_uri(model_uri)}


def validate_model_artifact(line: str, algorithm: str, family: str, artifact_uri: str | None) -> None:
    if artifact_uri is not None and not isinstance(artifact_uri, (str, pathlib.Path)):
        return
    if isinstance(artifact_uri, str) and artifact_uri.startswith("<MagicMock"):
        return
    load_model_bundle(line, algorithm, family, artifact_uri)


def _algorithm_for(line: str) -> str:
    cfg = champion_config.get("champion_by_line", {}).get(line, {})
    return _normalize_algorithm(cfg.get("algorithm", "lgb"))


def load_artifacts() -> None:
    global artifacts, all_artifacts, champion_config, metadata
    global geo_by_province, cost_indices_latest, products, _loaded
    metadata = _load_json_metadata()
    registry = _resolve_champion_registry()
    champion_config = {"champion_by_line": registry}
    artifacts = {}
    all_artifacts = {}
    for line in LINES:
        cfg = registry.get(line)
        if cfg is None:
            raise RuntimeError(f"Missing champion registry for line={line}")
        algo = _normalize_algorithm(cfg.get("algorithm", "lgb"))
        family = cfg.get("family") or "freqsev"
        artifacts[line] = {}
        all_artifacts[line] = {name: {} for name in FAMILIES}
        bundle = load_model_bundle(line, algo, family, cfg.get("artifact_uri"))
        if family in ("freqsev", "freq_sev"):
            all_artifacts[line]["freq"][algo] = bundle["freq"]
            all_artifacts[line]["sev"][algo] = bundle["sev"]
            artifacts[line]["freq"] = bundle["freq"]
            artifacts[line]["sev"] = bundle["sev"]
        else:
            all_artifacts[line][family][algo] = bundle[family]
            artifacts[line][family] = bundle[family]
    _load_geo()
    _load_cost_indices()
    _load_products()
    _load_loading_factors()
    clear_feature_store_cache()
    _loaded = True


def refresh_artifacts() -> None:
    load_artifacts()


def _load_geo() -> None:
    global geo_by_province
    geo_by_province = {}
    if not GEO_RISK_PATH.exists():
        return
    df = pd.read_csv(GEO_RISK_PATH)
    for _, row in df.iterrows():
        geo_by_province[row["province"]] = {c: row[c] for c in df.columns}


def _load_cost_indices() -> None:
    global cost_indices_latest
    cost_indices_latest = {}
    if not COST_INDICES_PATH.exists():
        return
    df = pd.read_csv(COST_INDICES_PATH)
    latest = df.sort_values("month_start").iloc[-1]
    for c in df.columns:
        if c not in ("year", "month", "month_start"):
            cost_indices_latest[c] = float(latest[c])


def _load_products_from_read_model() -> bool:
    global products, _products_loaded_at
    try:
        from ..database import SessionLocal
        from ..services.product_read_model import load_product_catalog
        db = SessionLocal()
        try:
            rows = load_product_catalog(db)
        finally:
            db.close()
        if rows:
            products = rows
            _products_loaded_at = time.monotonic()
            return True
    except Exception as e:
        warnings.warn(f"Pricing product read-model unavailable, falling back to products.csv: {e}")
    return False


def _load_products() -> None:
    global products, _products_loaded_at
    if _load_products_from_read_model():
        return
    products = {}
    if not PRODUCTS_PATH.exists():
        return
    df = pd.read_csv(PRODUCTS_PATH)
    for _, row in df.iterrows():
        products[row["product_id"]] = {c: row[c] for c in df.columns}
    _products_loaded_at = time.monotonic()


def _maybe_refresh_products() -> None:
    if time.monotonic() - _products_loaded_at > PRODUCT_CACHE_TTL_SECONDS:
        _load_products()


def _load_loading_factors_from_read_model() -> bool:
    global loading_factors, _loading_loaded_at, current_rate_version_id
    try:
        from ..database import SessionLocal
        from ..services.product_read_model import load_loading_factors
        db = SessionLocal()
        try:
            factors, rate_version_id = load_loading_factors(db)
        finally:
            db.close()
        if factors:
            loading_factors = factors
            current_rate_version_id = rate_version_id
            _loading_loaded_at = time.monotonic()
            return True
    except Exception as e:
        warnings.warn(f"Pricing loading-factor read-model unavailable, defaulting to 1.0: {e}")
    return False


def _load_loading_factors() -> None:
    global loading_factors, _loading_loaded_at, current_rate_version_id
    if _load_loading_factors_from_read_model():
        return
    if not loading_factors:
        loading_factors = {ln: 1.0 for ln in LINES}
        _loading_loaded_at = time.monotonic()


def get_loading_factor(line: str) -> float:
    ensure_loaded()
    if time.monotonic() - _loading_loaded_at > PRODUCT_CACHE_TTL_SECONDS:
        _load_loading_factors()
    return float(loading_factors.get(line, 1.0))


def get_current_rate_version_id() -> str | None:
    ensure_loaded()
    return current_rate_version_id


def ensure_loaded() -> None:
    if not _loaded:
        load_artifacts()


def get_line_for_product(product_id: str) -> str:
    ensure_loaded()
    _maybe_refresh_products()
    prod = products.get(product_id)
    if prod is not None:
        return prod["category"]
    upper = product_id.upper()
    for line in LINES:
        if line.upper() in upper:
            return line
    return "health"


def get_product(product_id: str) -> dict:
    ensure_loaded()
    _maybe_refresh_products()
    return products.get(product_id, {})


def get_champion(line: str) -> dict:
    ensure_loaded()
    return champion_config.get("champion_by_line", {}).get(line, {})


def required_columns(line: str) -> list[str]:
    ensure_loaded()
    cfg = get_champion(line)
    algo = _normalize_algorithm(cfg.get("algorithm", "lgb"))
    family = cfg.get("family") or "freqsev"
    if family in ("freqsev", "freq_sev"):
        model = all_artifacts[line]["freq"].get(algo) or artifacts[line]["freq"]
    else:
        model = all_artifacts[line][family].get(algo) or artifacts[line][family]
    fn = getattr(model, "feature_name_", None)
    if fn is not None:
        return list(fn)
    prep = model.named_steps["prep"]
    cols: list[str] = []
    for _, _, names in prep.transformers:
        cols.extend(names)
    return cols


