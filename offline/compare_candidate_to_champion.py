"""Compare a candidate artifact to the current champion on the same holdout dataset.

Writes ``comparison_report.json`` with metrics, premium delta guardrails, and
an overall pass/fail according to ``offline/comparison_config.json``.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys
from typing import Any

import joblib
import numpy as np
import pandas as pd

ROOT = pathlib.Path(__file__).resolve().parent.parent
PRICING_DIR = ROOT / "pricing"
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
CONFIG_PATH = ROOT / "offline" / "comparison_config.json"

if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(PRICING_DIR) not in sys.path:
    sys.path.insert(0, str(PRICING_DIR))

from offline.object_storage import materialize
from offline.train_pricing_models import MIN_EXPOSURE, prepare_features, normalized_gini
from app.database import SessionLocal, ModelVersion


def load_config() -> dict[str, Any]:
    with open(CONFIG_PATH, encoding="utf-8") as handle:
        return json.load(handle)


def _artifact_path(base_dir: pathlib.Path, line: str, algorithm: str, family: str) -> pathlib.Path:
    return base_dir / f"{line}__{algorithm}_{family}.joblib"


def _infer_candidate_family(base_dir: pathlib.Path, line: str) -> tuple[str, str]:
    if _artifact_path(base_dir, line, "lgb", "tw").exists():
        return "lgb", "tw"
    if _artifact_path(base_dir, line, "lgb", "freq").exists() and _artifact_path(base_dir, line, "lgb", "sev").exists():
        return "lgb", "freqsev"
    if _artifact_path(base_dir, line, "glm", "tw").exists():
        return "glm", "tw"
    if _artifact_path(base_dir, line, "glm", "freq").exists() and _artifact_path(base_dir, line, "glm", "sev").exists():
        return "glm", "freqsev"
    raise FileNotFoundError(f"No candidate artifacts found for {line} in {base_dir}")


def _load_model(path_or_uri: pathlib.Path | str):
    path = materialize(path_or_uri)
    if not path.exists():
        raise FileNotFoundError(f"Missing artifact: {path_or_uri}")
    return joblib.load(path)


def _load_dataset(dataset_dir: pathlib.Path, line: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    freq_path = dataset_dir / f"pricing_freq_{line}.csv"
    sev_path = dataset_dir / f"pricing_sev_{line}.csv"
    if not freq_path.exists():
        raise FileNotFoundError(f"Missing frequency dataset: {freq_path}")
    freq_df = pd.read_csv(freq_path, low_memory=False)
    sev_df = pd.read_csv(sev_path, low_memory=False) if sev_path.exists() else pd.DataFrame()
    if not sev_df.empty and {"exposure_id", "incurred_amount"}.issubset(sev_df.columns) and "exposure_id" in freq_df.columns:
        loss_by_expo = sev_df.groupby("exposure_id")["incurred_amount"].sum()
        freq_df["_loss_per_exposure"] = freq_df["exposure_id"].map(loss_by_expo).fillna(0.0)
    return freq_df, sev_df


def _predict_family(model, df: pd.DataFrame) -> np.ndarray:
    feature_df = prepare_features(df, list(model.feature_name_))
    return np.asarray(model.predict(feature_df), dtype=float)


def _predict_pure_premium(base_dir: pathlib.Path, line: str, algorithm: str, family: str, freq_df: pd.DataFrame) -> np.ndarray:
    if family in ("freqsev", "freq_sev"):
        freq_model = _load_model(_artifact_path(base_dir, line, algorithm, "freq"))
        sev_model = _load_model(_artifact_path(base_dir, line, algorithm, "sev"))
        frequency = np.maximum(0.0, _predict_family(freq_model, freq_df))
        severity = np.maximum(0.0, _predict_family(sev_model, freq_df))
        return frequency * severity
    model = _load_model(_artifact_path(base_dir, line, algorithm, family))
    return np.maximum(0.0, _predict_family(model, freq_df))


def _predict_pure_premium_from_uris(artifact_uri: str, line: str, algorithm: str, family: str, freq_df: pd.DataFrame) -> np.ndarray:
    uris = [item.strip() for item in str(artifact_uri or "").split(",") if item.strip()]
    if family in ("freqsev", "freq_sev"):
        freq_uri = next((uri for uri in uris if pathlib.Path(uri).name.endswith("_freq.joblib")), None)
        sev_uri = next((uri for uri in uris if pathlib.Path(uri).name.endswith("_sev.joblib")), None)
        if not freq_uri or not sev_uri:
            raise FileNotFoundError(f"Missing freq/sev artifact URIs for {line}: {artifact_uri}")
        freq_model = _load_model(freq_uri)
        sev_model = _load_model(sev_uri)
        frequency = np.maximum(0.0, _predict_family(freq_model, freq_df))
        severity = np.maximum(0.0, _predict_family(sev_model, freq_df))
        return frequency * severity
    if not uris:
        raise FileNotFoundError(f"Missing artifact URI for {line}")
    model = _load_model(uris[0])
    return np.maximum(0.0, _predict_family(model, freq_df))


def _targets(freq_df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    exposure = freq_df["earned_exposure_years"].clip(lower=MIN_EXPOSURE).to_numpy(dtype=float)
    actual = np.asarray(freq_df.get("_loss_per_exposure", 0.0), dtype=float) / exposure
    return actual, exposure


def _mae(actual: np.ndarray, pred: np.ndarray) -> float:
    return float(np.mean(np.abs(actual - pred))) if len(actual) else 0.0


def _rmse(actual: np.ndarray, pred: np.ndarray) -> float:
    return float(math.sqrt(np.mean(np.square(actual - pred)))) if len(actual) else 0.0


def _deviance(actual: np.ndarray, pred: np.ndarray) -> float:
    pred_safe = np.maximum(pred, 1e-9)
    actual_safe = np.maximum(actual, 0.0)
    return float(2.0 * np.mean(actual_safe * np.log((actual_safe + 1e-9) / pred_safe) - (actual_safe - pred_safe)))


def _calibration_error(actual: np.ndarray, pred: np.ndarray, bins: int = 10) -> float:
    if len(actual) == 0:
        return 0.0
    quantiles = np.quantile(pred, np.linspace(0.0, 1.0, bins + 1))
    total = 0.0
    evaluated = 0
    for index in range(bins):
        lo = quantiles[index]
        hi = quantiles[index + 1]
        mask = (pred >= lo) & (pred <= hi if index == bins - 1 else pred < hi)
        if not np.any(mask):
            continue
        total += abs(float(np.mean(actual[mask])) - float(np.mean(pred[mask])))
        evaluated += 1
    return total / evaluated if evaluated else 0.0


def _lift_by_decile(actual: np.ndarray, pred: np.ndarray) -> list[dict[str, float]]:
    if len(actual) == 0:
        return []
    frame = pd.DataFrame({"actual": actual, "pred": pred}).sort_values("pred", ascending=False).reset_index(drop=True)
    frame["decile"] = pd.qcut(frame.index, q=min(10, len(frame)), labels=False, duplicates="drop")
    baseline = float(frame["actual"].mean()) if len(frame) else 0.0
    out = []
    for decile, group in frame.groupby("decile"):
        mean_actual = float(group["actual"].mean())
        out.append({
            "decile": int(decile),
            "actual_mean": mean_actual,
            "lift": (mean_actual / baseline) if baseline > 0 else 0.0,
        })
    return out


def _premium_delta(candidate: np.ndarray, champion: np.ndarray) -> dict[str, float]:
    champion_safe = np.where(np.abs(champion) < 1e-9, 1e-9, champion)
    delta_pct = ((candidate - champion) / champion_safe) * 100.0
    abs_delta_pct = np.abs(delta_pct)
    return {
        "average_premium_delta_pct": float(np.mean(delta_pct)) if len(delta_pct) else 0.0,
        "p50_premium_delta_pct": float(np.percentile(delta_pct, 50)) if len(delta_pct) else 0.0,
        "p95_abs_premium_delta_pct": float(np.percentile(abs_delta_pct, 95)) if len(abs_delta_pct) else 0.0,
        "over_30_percent_delta_rate_pct": float(np.mean(abs_delta_pct > 30.0) * 100.0) if len(abs_delta_pct) else 0.0,
    }


def _champion_metadata(model_version_id: str) -> tuple[str, str, dict[str, Any], str | None]:
    session = SessionLocal()
    try:
        row = session.query(ModelVersion).filter(ModelVersion.model_version_id == model_version_id).first()
        if row is None:
            raise ValueError(f"Champion model_version not found: {model_version_id}")
        quality_gates = row.quality_gates if isinstance(row.quality_gates, dict) else {}
        family = row.family or quality_gates.get("family") or "tw"
        algorithm = quality_gates.get("algorithm") or ("lgb" if str(row.algorithm).lower().startswith("light") else "glm")
        return algorithm, family, quality_gates, row.artifact_uri
    finally:
        session.close()


def compare(line: str, dataset_dir: pathlib.Path, candidate_artifact_dir: pathlib.Path, champion_model_version_id: str, output_file: pathlib.Path) -> dict[str, Any]:
    config = load_config()
    freq_df, _sev_df = _load_dataset(dataset_dir, line)
    actual, exposure = _targets(freq_df)
    candidate_algorithm, candidate_family = _infer_candidate_family(candidate_artifact_dir, line)
    candidate_pred = _predict_pure_premium(candidate_artifact_dir, line, candidate_algorithm, candidate_family, freq_df)
    champion_algorithm, champion_family, champion_quality, champion_artifact_uri = _champion_metadata(champion_model_version_id)
    if champion_artifact_uri:
        champion_pred = _predict_pure_premium_from_uris(champion_artifact_uri, line, champion_algorithm, champion_family, freq_df)
    else:
        champion_pred = _predict_pure_premium(MODELS_DIR, line, champion_algorithm, champion_family, freq_df)

    candidate_metrics = {
        "gini": normalized_gini(actual, candidate_pred, sample_weight=exposure),
        "mae": _mae(actual, candidate_pred),
        "rmse": _rmse(actual, candidate_pred),
        "deviance": _deviance(actual, candidate_pred),
        "calibration_error": _calibration_error(actual, candidate_pred),
        "lift_by_decile": _lift_by_decile(actual, candidate_pred),
    }
    champion_metrics = {
        "gini": normalized_gini(actual, champion_pred, sample_weight=exposure),
        "mae": _mae(actual, champion_pred),
        "rmse": _rmse(actual, champion_pred),
        "deviance": _deviance(actual, champion_pred),
        "calibration_error": _calibration_error(actual, champion_pred),
        "lift_by_decile": _lift_by_decile(actual, champion_pred),
    }
    premium_delta = _premium_delta(candidate_pred, champion_pred)
    passed = (
        candidate_metrics["gini"] >= champion_metrics["gini"] + config["gini_improvement_threshold"]
        and candidate_metrics["deviance"] <= champion_metrics["deviance"] * config["max_deviance_ratio"]
        and candidate_metrics["calibration_error"] <= max(champion_metrics["calibration_error"], 1e-9) * config["max_calibration_error_ratio"]
        and premium_delta["p95_abs_premium_delta_pct"] <= config["max_p95_abs_premium_delta_pct"]
        and premium_delta["over_30_percent_delta_rate_pct"] <= config["max_over30_premium_delta_rate_pct"]
    )
    report = {
        "line": line,
        "champion_model_version_id": champion_model_version_id,
        "champion": {"algorithm": champion_algorithm, "family": champion_family, "quality_gates": champion_quality, "metrics": champion_metrics},
        "candidate": {"algorithm": candidate_algorithm, "family": candidate_family, "metrics": candidate_metrics},
        "premium_delta": premium_delta,
        "thresholds": config,
        "passed": bool(passed),
        "rows_evaluated": int(len(freq_df)),
    }
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--line", required=True)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--candidate-artifact-dir", required=True)
    parser.add_argument("--champion-model-version-id", required=True)
    parser.add_argument("--output-file", required=True)
    args = parser.parse_args()
    report = compare(
        line=args.line,
        dataset_dir=pathlib.Path(args.dataset_dir),
        candidate_artifact_dir=pathlib.Path(args.candidate_artifact_dir),
        champion_model_version_id=args.champion_model_version_id,
        output_file=pathlib.Path(args.output_file),
    )
    print(json.dumps({"passed": report["passed"], "output_file": args.output_file}, indent=2))


if __name__ == "__main__":
    main()
