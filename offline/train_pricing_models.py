"""
Offline training script: re-fit Champion LightGBM models with monotone_constraints.

Reads data from data/synthetic_real_1m_history_lift_v2/ (the authoritative dataset).
For each of the 6 lines, fits freq (Poisson), sev (Gamma), and tw (Tweedie) models
with monotone_constraints enforcing:
  - coverage_amount_vnd     -> +1 (higher coverage -> higher premium)
  - deductible_vnd          -> -1 (higher deductible -> lower premium)
  - annual_mileage_km       -> +1 (more mileage -> higher premium) [car/motorbike only]
  - claim_count_36m_prior   -> +1 (more prior claims -> higher premium)

Actuarial targets (MUST match the established pipeline in scripts/train_pricing_models.py
and pricing_modeling_metadata.json â€” final_premium_vnd is a benchmark, NEVER a target):
  - freq : claim_count / earned_exposure_years  (claims per exposure-year), weight = exposure
  - sev  : incurred_amount per claim (claims with loss > 0 only), no weight
  - tw   : loss per exposure-year = (sum incurred per exposure_id) / earned_exposure_years,
           weight = exposure  (direct pure-premium, NOT final_premium_vnd)

These models are re-fit on the FULL dataset to produce the champion artifacts.
Held-out / GroupKFold-by-customer evaluation and the registry metrics
(gini/rmse/mae/deviance) are produced separately by scripts/validate_pricing_models.py
and recorded by offline/register_models.py (task 6.2). The feature column order is
preserved from the existing artifacts to ensure monotone_constraints alignment between
training and serving (HIGH RISK per design).

Anti-leakage cross-validation (task 20.8a)
------------------------------------------
Cross-validation here uses **GroupKFold with k=5, grouped by ``customer_id``**.
Because a single customer can hold several policies/exposures, a plain (random)
KFold could place rows from the same customer in both the train and the
validation fold, leaking customer-specific signal and inflating the score.
Grouping by ``customer_id`` guarantees that every customer appears in exactly
one fold, so the CV estimate reflects performance on *unseen customers*.

WHERE GroupKFold runs:
  * ``groupkfold_cv()`` in THIS module is the explicit, self-contained k=5
    grouped CV step (call ``python offline/train_pricing_models.py --cv``).
  * The full validation/comparison pipeline in
    ``scripts/validate_pricing_models.py`` uses the same anti-leakage principle
    via the ``time_based_grouped`` split (group_col=customer_id) declared in
    ``data/synthetic_real_1m_history_lift_v2/pricing_modeling_metadata.json``.
  * See ``offline/README.md`` for the operational note.
Running ``--cv`` only prints CV metrics; it NEVER overwrites the champion
artifacts (artifact outputs are produced by the default ``main()`` path).

Requirements: R12.7, R4.7, R29.5, R30.5, R31.3, R31.4 (design section 6.3, BR-19/C-8)
"""

import json
import os
import pathlib
import sys
import warnings

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd

_IMPORT_ROOT = pathlib.Path(__file__).resolve().parent.parent
if str(_IMPORT_ROOT) not in sys.path:
    sys.path.insert(0, str(_IMPORT_ROOT))


warnings.filterwarnings("ignore")

# Minimum exposure floor to avoid division-by-zero when computing per-year rates
# (matches scripts/train_pricing_models.py: earned_exposure_years.clip(lower=1e-6)).
MIN_EXPOSURE = 1e-6

# -- Paths ------------------------------------------------------------------
ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = pathlib.Path(os.environ.get("PRICING_TRAIN_DATA_DIR", ROOT / "data" / "synthetic_real_1m_history_lift_v2"))
if not DATA_DIR.is_absolute():
    DATA_DIR = ROOT / DATA_DIR
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
METADATA_PATH = DATA_DIR / "pricing_modeling_metadata.json"
BASELINES_DIR = ROOT / "reports" / "modeling" / "baselines"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]

# -- Monotone variable definitions ------------------------------------------
# Key: feature name -> constraint direction (+1 increasing, -1 decreasing, 0 none)
# annual_mileage_km only applies to car/motorbike (driving exposure lines)
MONOTONE_COMMON = {
    "coverage_amount_vnd": 1,
    "deductible_vnd": -1,
    "claim_count_12m_prior": 1,
    "claim_count_36m_prior": 1,
    "claim_count_lifetime_prior": 1,
    "total_incurred_36m_prior": 1,
    "avg_incurred_36m_prior": 1,
    "max_incurred_36m_prior": 1,
    "large_claim_count_36m_prior": 1,
    "severe_claim_count_36m_prior": 1,
    "avg_incurred_score_prior": 1,
    "days_since_last_claim_prior": -1,
    "claim_severity_score_prior": 1,
}

MONOTONE_BY_LINE = {
    "health": {
        **MONOTONE_COMMON,
        "age": 1,
        "bmi": 1,
        "smoker": 1,
        "chronic_disease": 1,
        "diabetes": 1,
        "blood_pressure_problem": 1,
        "hospitalized_last_12m": 1,
        "major_surgeries_count": 1,
        "medical_visit_count_12m": 1,
    },
    "motorbike": {
        **MONOTONE_COMMON,
        "vehicle_age": 1,
        "vehicle_value_vnd": 1,
        "engine_capacity_cc": 1,
        "driving_experience_years": -1,
        "annual_mileage_km": 1,
        "traffic_violation_count_12m": 1,
        "anti_theft_device": -1,
    },
    "car": {
        **MONOTONE_COMMON,
        "vehicle_age": 1,
        "vehicle_value_vnd": 1,
        "engine_capacity_cc": 1,
        "driving_experience_years": -1,
        "annual_mileage_km": 1,
        "traffic_violation_count_12m": 1,
        "anti_theft_device": -1,
        "driver_count": 1,
        "loan_or_leasing_flag": 1,
    },
    "home": {
        **MONOTONE_COMMON,
        "floor_area_m2": 1,
        "number_of_floors": 1,
        "building_age": 1,
        "declared_property_value_vnd": 1,
        "fire_protection": -1,
        "has_fire_alarm": -1,
        "has_sprinkler": -1,
        "security_system": -1,
    },
    "accident": {
        **MONOTONE_COMMON,
        "commute_distance_km": 1,
        "sport_activity_flag": 1,
    },
    "travel": {
        **MONOTONE_COMMON,
        "trip_duration_days": 1,
        "traveler_count": 1,
        "trip_cost_vnd": 1,
        "has_baggage_cover": 1,
        "has_trip_cancellation_cover": 1,
    },
}

MONOTONE_VEHICLE = {
    **MONOTONE_COMMON,
    "annual_mileage_km": 1,
}

# -- Exclusion sets ---------------------------------------------------------
with open(METADATA_PATH) as f:
    METADATA = json.load(f)

EXCLUDE_COLS = set(
    METADATA["id_columns"]
    + METADATA["time_columns"]
    + METADATA["do_not_use_as_features"]
)
EXCLUDE_COLS.update([
    "earned_exposure_years",
    "offset_log_exposure",
    "policy_year",
    "incurred_amount",
    "paid_amount",
    "claim_status",
    "severity_level",
    "freq_rate",
    "sev_base",
    "claim_count",
    "claim_flag",
    "final_premium_vnd",
])






EXTRA_FEATURES_BY_LINE = {
    "health": [
        "avg_incurred_36m_prior",
        "claim_severity_score_prior",
        "large_claim_count_36m_prior",
        "severe_claim_count_36m_prior",
        "avg_incurred_score_prior",
        "age_disease_bucket",
        "bmi_disease_bucket",
    ],
}

def feature_order_for_training(line: str, df: pd.DataFrame, existing_features: list[str]) -> list[str]:
    feature_names = list(existing_features)
    for feature in EXTRA_FEATURES_BY_LINE.get(line, []):
        if feature in df.columns and feature not in feature_names and feature not in EXCLUDE_COLS:
            feature_names.append(feature)
    return feature_names

def build_monotone_constraints(feature_names: list[str], line: str) -> list[int]:
    """Build monotone_constraints list aligned with feature column order.

    The constraint list position MUST match the column order used during
    training (same order as feature_names). This is critical â€” misalignment
    causes the constraint to apply to the wrong variable (HIGH RISK per design).
    """
    monotone_map = MONOTONE_BY_LINE.get(line, MONOTONE_COMMON)
    return [monotone_map.get(fn, 0) for fn in feature_names]


def prepare_features(df: pd.DataFrame, existing_features: list[str]) -> pd.DataFrame:
    """Drop non-feature columns, align to existing model feature order.

    Reorders columns to match the existing model's feature_name_ order.
    This ensures monotone_constraints indices align correctly at serving time.
    """
    drop_cols = [c for c in df.columns if c in EXCLUDE_COLS]
    feat_df = df.drop(columns=drop_cols, errors="ignore")
    # Add any missing columns with default 0
    for c in existing_features:
        if c not in feat_df.columns:
            feat_df[c] = 0
    # Convert object columns to category for LightGBM
    for c in feat_df.select_dtypes(include=["object"]).columns:
        feat_df[c] = feat_df[c].astype("category")
    # Reorder to match existing model (CRITICAL for monotone alignment)
    return feat_df[existing_features]



PRIOR_HISTORY_WEIGHT_MULTIPLIER = {"freq": 8.0, "sev": 30.0, "tw": 8.0}

def apply_prior_history_weight(df_work: pd.DataFrame, sample_weight, family: str):
    if "claim_count_36m_prior" not in df_work.columns:
        return sample_weight
    weights = (
        np.ones(len(df_work), dtype=float)
        if sample_weight is None and family == "sev"
        else None if sample_weight is None
        else np.asarray(sample_weight, dtype=float).copy()
    )
    if weights is None:
        return sample_weight
    prior_mask = df_work["claim_count_36m_prior"].fillna(0).to_numpy() > 0
    weights[prior_mask] *= PRIOR_HISTORY_WEIGHT_MULTIPLIER.get(family, 1.0)
    return weights

def retrain_model(line: str, family: str, df: pd.DataFrame) -> lgb.LGBMRegressor | None:
    """Re-train a single LightGBM model with monotone_constraints.

    Targets (per pricing_modeling_metadata.json / scripts/train_pricing_models.py):
      - freq : claim_count / earned_exposure_years (rate per year), sample_weight = exposure
      - sev  : incurred_amount per claim (loss > 0 only), no weight
      - tw   : loss per exposure-year (pure premium), sample_weight = exposure
               where loss per exposure-year = "_loss_per_exposure" / earned_exposure_years
               (the caller must have populated "_loss_per_exposure" from the severity table).
    final_premium_vnd is NEVER used as a target (it is a benchmark only).
    """
    existing_path = MODELS_DIR / f"{line}__lgb_{family}.joblib"
    if not existing_path.exists():
        print(f"    SKIP {line}__lgb_{family}: no existing artifact to base on")
        return None

    existing = joblib.load(existing_path)
    existing_features = feature_order_for_training(line, df, list(existing.feature_name_))
    monotone_constraints = build_monotone_constraints(existing_features, line)

    # Subset data for severity (claims > 0 only)
    if family == "sev":
        if "incurred_amount" not in df.columns:
            return None
        df_work = df[df["incurred_amount"] > 0].copy()
        if len(df_work) < 50:
            print(f"    SKIP {line}__lgb_sev: only {len(df_work)} positive claims")
            return None
    else:
        df_work = df

    feat_df = prepare_features(df_work, existing_features)

    # Build target and sample weight per family. Exposure is floored to avoid
    # division-by-zero when forming per-exposure-year rates.
    if family == "freq":
        if "earned_exposure_years" not in df_work.columns:
            print(f"    SKIP {line}__lgb_freq: missing earned_exposure_years")
            return None
        exposure = df_work["earned_exposure_years"].clip(lower=MIN_EXPOSURE).to_numpy()
        target = (df_work["claim_count"] / exposure)  # claims per exposure-year
        sample_weight = exposure
    elif family == "sev":
        target = df_work["incurred_amount"]
        sample_weight = None
    elif family == "tw":
        if "_loss_per_exposure" not in df_work.columns or "earned_exposure_years" not in df_work.columns:
            print(f"    SKIP {line}__lgb_tw: missing loss/exposure columns")
            return None
        exposure = df_work["earned_exposure_years"].clip(lower=MIN_EXPOSURE).to_numpy()
        target = (df_work["_loss_per_exposure"] / exposure)  # loss per exposure-year (pure premium)
        sample_weight = exposure
    else:
        raise ValueError(f"Unknown family: {family}")

    sample_weight = apply_prior_history_weight(df_work, sample_weight, family)

    # Copy existing hyperparameters, add monotone_constraints
    params = existing.get_params()
    params["monotone_constraints"] = monotone_constraints
    params["verbose"] = -1
    if line == "health":
        params.update({
            "max_depth": 4,
            "min_child_samples": 500,
            "min_split_gain": 0.0,
            "num_leaves": 16,
        })

    model = lgb.LGBMRegressor(**params)
    model.fit(feat_df, target, sample_weight=sample_weight)
    return model


# -- Anti-leakage cross-validation (task 20.8a) ------------------------------
# Number of folds and the grouping column for GroupKFold. Grouping by
# customer_id prevents the same customer's rows landing in both train and
# validation folds (entity leakage).
CV_FOLDS = 5
GROUP_COL = "customer_id"


def normalized_gini(y_true, y_pred, sample_weight=None) -> float:
    """Normalized (Somers' D-style) Gini coefficient used to rank risk ordering.

    Equals 2*AUC-1 for a binary target and generalizes to continuous targets as
    the ratio of the model's Gini to the Gini of a perfect ordering. Range
    roughly [-1, 1]; higher means better risk discrimination. Returns 0.0 for a
    degenerate (single-row or zero-variance) fold.
    """
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    w = np.ones_like(y_true) if sample_weight is None else np.asarray(sample_weight, dtype=float)
    if len(y_true) < 2:
        return 0.0

    def _gini(order_key):
        order = np.argsort(order_key, kind="mergesort")[::-1]
        yt = y_true[order]
        ww = w[order]
        cum_w = np.cumsum(ww)
        total_w = cum_w[-1]
        if total_w <= 0:
            return 0.0
        cum_pos = np.cumsum(yt * ww)
        total_pos = cum_pos[-1]
        if total_pos == 0:
            return 0.0
        # Area between the Lorenz curve and the diagonal (weighted).
        lorenz = cum_pos / total_pos
        pop = cum_w / total_w
        # Trapezoidal area under the Lorenz curve minus 0.5 (the diagonal).
        area = np.sum((pop[1:] - pop[:-1]) * (lorenz[1:] + lorenz[:-1]) / 2.0)
        return area - 0.5

    pred_gini = _gini(y_pred)
    perfect_gini = _gini(y_true)
    if perfect_gini == 0:
        return 0.0
    return float(pred_gini / perfect_gini)


def _build_target_and_weight(df_work: pd.DataFrame, family: str):
    """Build (target, sample_weight) for a family, mirroring retrain_model().

    Returns (None, None) when the required columns are missing.
    """
    if family == "freq":
        if "earned_exposure_years" not in df_work.columns or "claim_count" not in df_work.columns:
            return None, None
        exposure = df_work["earned_exposure_years"].clip(lower=MIN_EXPOSURE).to_numpy()
        return (df_work["claim_count"].to_numpy() / exposure), exposure
    if family == "sev":
        if "incurred_amount" not in df_work.columns:
            return None, None
        return df_work["incurred_amount"].to_numpy(), None
    if family == "tw":
        if "_loss_per_exposure" not in df_work.columns or "earned_exposure_years" not in df_work.columns:
            return None, None
        exposure = df_work["earned_exposure_years"].clip(lower=MIN_EXPOSURE).to_numpy()
        return (df_work["_loss_per_exposure"].to_numpy() / exposure), exposure
    raise ValueError(f"Unknown family: {family}")


def groupkfold_cv(line: str, family: str, df: pd.DataFrame,
                  k: int = CV_FOLDS, group_col: str = GROUP_COL) -> dict:
    """Run k=5 GroupKFold CV (grouped by ``customer_id``) for one (line, family).

    Anti-leakage: every customer (group) appears in exactly one validation fold,
    so the score estimates performance on *unseen customers*. Each fold fits a
    LightGBM with the SAME monotone_constraints as the champion artifact and
    scores the held-out fold with the normalized Gini.

    Returns a dict: {line, family, k, fold_gini: [...], mean_gini, n_groups, n_rows}.
    Raises ValueError if the grouping column is absent (we refuse to silently
    fall back to a leaky random split).
    """
    from sklearn.model_selection import GroupKFold

    if group_col not in df.columns:
        raise ValueError(
            f"{line}/{family}: cannot run GroupKFold, missing group column "
            f"'{group_col}' (required for anti-leakage CV)"
        )

    existing_path = MODELS_DIR / f"{line}__lgb_{family}.joblib"
    if not existing_path.exists():
        return {"line": line, "family": family, "k": k, "fold_gini": [],
                "mean_gini": None, "n_groups": 0, "n_rows": 0,
                "skipped": "no existing artifact"}

    df_work = df
    if family == "sev":
        if "incurred_amount" not in df.columns:
            return {"line": line, "family": family, "k": k, "fold_gini": [],
                    "mean_gini": None, "n_groups": 0, "n_rows": 0,
                    "skipped": "no incurred_amount"}
        df_work = df[df["incurred_amount"] > 0].copy()

    target, sample_weight = _build_target_and_weight(df_work, family)
    if target is None:
        return {"line": line, "family": family, "k": k, "fold_gini": [],
                "mean_gini": None, "n_groups": 0, "n_rows": 0,
                "skipped": "missing target columns"}

    existing = joblib.load(existing_path)
    existing_features = list(existing.feature_name_)
    monotone_constraints = build_monotone_constraints(existing_features, line)
    feat_df = prepare_features(df_work, existing_features).reset_index(drop=True)
    target = np.asarray(target, dtype=float)
    groups = df_work[group_col].to_numpy()
    n_groups = len(np.unique(groups))

    # GroupKFold needs at least k distinct groups.
    n_splits = min(k, n_groups)
    if n_splits < 2:
        return {"line": line, "family": family, "k": k, "fold_gini": [],
                "mean_gini": None, "n_groups": int(n_groups), "n_rows": len(df_work),
                "skipped": f"only {n_groups} group(s); need >= 2"}

    base_params = existing.get_params()
    base_params["monotone_constraints"] = monotone_constraints
    base_params["verbose"] = -1

    gkf = GroupKFold(n_splits=n_splits)
    fold_gini = []
    for train_idx, val_idx in gkf.split(feat_df, target, groups=groups):
        x_tr, x_val = feat_df.iloc[train_idx], feat_df.iloc[val_idx]
        y_tr, y_val = target[train_idx], target[val_idx]
        w_tr = sample_weight[train_idx] if sample_weight is not None else None
        w_val = sample_weight[val_idx] if sample_weight is not None else None
        fold_model = lgb.LGBMRegressor(**base_params)
        fold_model.fit(x_tr, y_tr, sample_weight=w_tr)
        preds = fold_model.predict(x_val)
        fold_gini.append(normalized_gini(y_val, preds, sample_weight=w_val))

    mean_gini = float(np.mean(fold_gini)) if fold_gini else None
    return {"line": line, "family": family, "k": n_splits,
            "fold_gini": [float(g) for g in fold_gini], "mean_gini": mean_gini,
            "n_groups": int(n_groups), "n_rows": len(df_work)}


def run_cv(lines: list[str] | None = None, families: list[str] | None = None) -> list[dict]:
    """Run GroupKFold CV across lines/families and return the per-(line,family) results.

    This is an evaluation-only path: it does NOT write or overwrite any champion
    artifact. Used by ``python offline/train_pricing_models.py --cv``.
    """
    lines = lines or LINES
    families = families or ["freq", "sev", "tw"]
    results = []
    for line in lines:
        freq_path = DATA_DIR / METADATA["freq_tables"][line]
        df_freq = pd.read_csv(freq_path, low_memory=False)
        sev_path = DATA_DIR / METADATA["sev_tables"][line]
        df_sev = pd.read_csv(sev_path, low_memory=False) if sev_path.exists() else df_freq
        if "exposure_id" in df_freq.columns and {"exposure_id", "incurred_amount"}.issubset(df_sev.columns):
            loss_by_expo = df_sev.groupby("exposure_id")["incurred_amount"].sum()
            df_freq["_loss_per_exposure"] = df_freq["exposure_id"].map(loss_by_expo).fillna(0.0)
        for family in families:
            df = df_sev if family == "sev" else df_freq
            try:
                res = groupkfold_cv(line, family, df)
            except ValueError as e:
                res = {"line": line, "family": family, "error": str(e)}
            results.append(res)
            mg = res.get("mean_gini")
            mg_s = f"{mg:.4f}" if isinstance(mg, float) else str(mg)
            note = res.get("skipped") or res.get("error") or ""
            print(f"  CV {line}__{family}: mean_gini={mg_s} "
                  f"(k={res.get('k')}, groups={res.get('n_groups')}) {note}")
    return results


def dump_baseline_artifact(line: str, df_freq: pd.DataFrame, df_sev: pd.DataFrame) -> None:
    """Dump baseline drift artifact for a line.

    Produces a JSON file at reports/modeling/baselines/{line}_baseline.json with:
    - feature_bins: histogram edges + counts for key numeric features
    - prediction_bins: histogram edges + counts for model pure_premium predictions
    - calibration_reference: actual claim rate per prediction bin
    """
    BASELINES_DIR.mkdir(parents=True, exist_ok=True)

    existing_path = MODELS_DIR / f"{line}__lgb_tw.joblib"
    if not existing_path.exists():
        print(f"  SKIP baseline for {line}: no tw model artifact")
        return

    model = joblib.load(existing_path)
    existing_features = list(model.feature_name_)

    feat_df = prepare_features(df_freq, existing_features)
    predictions = model.predict(feat_df)

    # Feature bins: use key numeric features present in the dataset
    feature_cols = ["age", "coverage_amount_vnd", "monthly_income_vnd"]
    feature_bins = {}
    for col in feature_cols:
        if col not in df_freq.columns:
            continue
        vals = df_freq[col].dropna().astype(float).values
        if len(vals) < 2:
            continue
        lo, hi = float(vals.min()), float(vals.max())
        if lo == hi:
            continue
        edges = [lo + i * (hi - lo) / 10 for i in range(11)]
        counts = [0] * 10
        for v in vals:
            idx = min(int((v - lo) / (hi - lo) * 10), 9)
            counts[idx] += 1
        total = sum(counts)
        feature_bins[col] = {
            "edges": edges,
            "counts": counts,
            "proportions": [c / total if total > 0 else 0.0 for c in counts],
        }

    # Prediction bins: histogram of pure_premium predictions
    pred_lo, pred_hi = float(predictions.min()), float(predictions.max())
    if pred_lo == pred_hi:
        pred_edges = [pred_lo] * 11
        pred_counts = [len(predictions)] + [0] * 9
    else:
        pred_edges = [pred_lo + i * (pred_hi - pred_lo) / 10 for i in range(11)]
        pred_counts = [0] * 10
        for v in predictions:
            idx = min(int((v - pred_lo) / (pred_hi - pred_lo) * 10), 9)
            pred_counts[idx] += 1
    pred_total = sum(pred_counts)
    prediction_bins = {
        "edges": pred_edges,
        "counts": pred_counts,
        "proportions": [c / pred_total if pred_total > 0 else 0.0 for c in pred_counts],
    }

    # Calibration reference: actual claim rate per prediction bin
    if "claim_count" in df_freq.columns and "earned_exposure_years" in df_freq.columns:
        exposure = df_freq["earned_exposure_years"].clip(lower=MIN_EXPOSURE).to_numpy()
        claim_count = df_freq["claim_count"].to_numpy()
        calibration_bins = []
        for i in range(10):
            if pred_lo == pred_hi:
                mask = np.ones(len(predictions), dtype=bool)
            else:
                lo_b = pred_edges[i]
                hi_b = pred_edges[i + 1] if i < 9 else pred_hi + 1
                mask = (predictions >= lo_b) & (predictions < hi_b)
            n = int(mask.sum())
            if n > 0:
                actual_rate = float(claim_count[mask].sum() / max(exposure[mask].sum(), MIN_EXPOSURE))
            else:
                actual_rate = 0.0
            calibration_bins.append({
                "bin_index": i,
                "count": n,
                "actual_rate": actual_rate,
            })
    else:
        calibration_bins = []

    baseline = {
        "line": line,
        "feature_bins": feature_bins,
        "prediction_bins": prediction_bins,
        "calibration_reference": calibration_bins,
        "trained_at": pd.Timestamp.now().isoformat(),
    }

    out_path = BASELINES_DIR / f"{line}_baseline.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(baseline, f, indent=2)
    print(f"    Baseline artifact saved: {out_path.name}")


def main():
    """Re-fit all 18 LightGBM champion models with monotone_constraints.

    With ``--cv`` it instead runs the k=5 GroupKFold (grouped by customer_id)
    anti-leakage cross-validation (task 20.8a) and prints the metrics WITHOUT
    touching any champion artifact.
    """
    import argparse
    parser = argparse.ArgumentParser(description="Offline champion training / CV")
    parser.add_argument("--cv", action="store_true",
                        help="Run k=5 GroupKFold (by customer_id) CV only; do not write artifacts")
    parser.add_argument("--line", choices=LINES, help="Re-fit only one product line")
    parser.add_argument("--output-uri", default=os.environ.get("TRAIN_OUTPUT_URI"),
                        help="Object-storage URI (gs://… or s3://…) to upload the trained "
                             "model/baseline artifacts to after local write. Decomposed Cloud "
                             "Run Jobs are stateless, so this hands artifacts off to GCS for "
                             "the downstream compare/register steps.")
    args, _ = parser.parse_known_args()

    selected_lines = [args.line] if args.line else LINES

    if args.cv:
        print(f"Running GroupKFold k={CV_FOLDS} (grouped by {GROUP_COL}) anti-leakage CV...")
        run_cv(lines=selected_lines)
        return

    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    results = []
    for line in selected_lines:
        print(f"\n{'='*60}")
        print(f"Re-fitting {line} champion models with monotone_constraints")
        print(f"Data source: {DATA_DIR}")
        print(f"{'='*60}")

        # Load frequency data (shared across freq/tw/sev)
        freq_path = DATA_DIR / METADATA["freq_tables"][line]
        df_freq = pd.read_csv(freq_path, low_memory=False)
        print(f"  Frequency data: {len(df_freq)} rows")

        # Load severity data
        sev_path = DATA_DIR / METADATA["sev_tables"][line]
        if sev_path.exists():
            df_sev = pd.read_csv(sev_path, low_memory=False)
            sev_pos = len(df_sev[df_sev["incurred_amount"] > 0]) if "incurred_amount" in df_sev.columns else 0
            print(f"  Severity data: {len(df_sev)} rows ({sev_pos} positive claims)")
        else:
            df_sev = df_freq
            print(f"  Severity: using freq data (no separate sev file)")

        # Build loss-per-exposure (total incurred per exposure record) for the
        # Tweedie pure-premium target. This mirrors scripts/train_pricing_models.py
        # (loss_by_expo = sev.groupby(exposure_id).incurred_amount.sum()). Exposure
        # records with no claim get 0.0. The Tweedie target is later divided by
        # earned_exposure_years to express loss per exposure-year.
        if "exposure_id" in df_freq.columns and {"exposure_id", "incurred_amount"}.issubset(df_sev.columns):
            loss_by_expo = df_sev.groupby("exposure_id")["incurred_amount"].sum()
            df_freq["_loss_per_exposure"] = df_freq["exposure_id"].map(loss_by_expo).fillna(0.0)
        else:
            print(f"  WARNING: cannot build loss-per-exposure for {line} (missing exposure_id/incurred_amount); tw will be skipped")

        for family in ["freq", "sev", "tw"]:
            model_name = f"{line}__lgb_{family}"
            print(f"  Training {model_name}...")

            try:
                # For sev model, use severity data; for freq/tw, use frequency data
                if family == "sev":
                    model = retrain_model(line, family, df_sev)
                else:
                    model = retrain_model(line, family, df_freq)

                if model is not None:
                    out_path = MODELS_DIR / f"{model_name}.joblib"
                    joblib.dump(model, out_path)
                    mc = model.get_params().get("monotone_constraints")
                    nz = sum(1 for c in mc if c != 0)
                    total = len(mc)
                    print(f"    Saved {out_path.name}. monotone: {nz}/{total} non-zero")
                    results.append(f"{model_name}: OK ({nz}/{total} monotone)")
                else:
                    results.append(f"{model_name}: SKIPPED (insufficient data)")
            except Exception as e:
                print(f"    ERROR: {e}")
                results.append(f"{model_name}: ERROR {e}")

        # Dump baseline drift artifact for this line
        try:
            dump_baseline_artifact(line, df_freq, df_sev)
        except Exception as e:
            print(f"    Baseline dump ERROR: {e}")
            results.append(f"{line}_baseline: ERROR {e}")

    print(f"\n{'='*60}")
    print("Summary:")
    for r in results:
        print(f"  {r}")
    print(f"\nSelected champion LightGBM models re-fitted with monotone_constraints.")
    print(f"{'='*60}")

    if args.output_uri:
        _upload_artifacts(args.output_uri, selected_lines)


def _upload_artifacts(output_uri: str, selected_lines: list[str]) -> None:
    """Upload the freshly-written model + baseline artifacts to object storage.

    Cloud Run Jobs are stateless, so the downstream compare/register jobs read
    the models back from this URI. Only the artifacts for the trained lines are
    pushed; each model keeps its ``<line>__lgb_<family>.joblib`` name under the
    URI so ``register_candidate_model.py --artifact-uri`` can address it directly.
    """
    from offline.object_storage import is_object_uri, upload_file

    if not is_object_uri(output_uri):
        print(f"  --output-uri '{output_uri}' is not an object-storage URI; skipping upload")
        return

    prefix = output_uri.rstrip("/")
    families = ["freq", "sev", "tw"]
    uploaded = 0
    for line in selected_lines:
        for family in families:
            local = MODELS_DIR / f"{line}__lgb_{family}.joblib"
            if local.is_file():
                upload_file(local, f"{prefix}/models/{local.name}")
                uploaded += 1
        baseline = BASELINES_DIR / f"{line}_baseline.json"
        if baseline.is_file():
            upload_file(baseline, f"{prefix}/baselines/{baseline.name}")
            uploaded += 1
    print(f"  Uploaded {uploaded} artifact(s) to {prefix}")


if __name__ == "__main__":
    main()
