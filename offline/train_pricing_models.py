"""
Offline training script: re-fit Champion LightGBM models with monotone_constraints.

Reads data from data/synthetic_real/ (the authoritative dataset).
For each of the 6 lines, fits freq (Poisson), sev (Gamma), and tw (Tweedie) models
with monotone_constraints enforcing:
  - coverage_amount_vnd     -> +1 (higher coverage -> higher premium)
  - deductible_vnd          -> -1 (higher deductible -> lower premium)
  - annual_mileage_km       -> +1 (more mileage -> higher premium) [car/motorbike only]
  - claim_count_36m_prior   -> +1 (more prior claims -> higher premium)

Actuarial targets (MUST match the established pipeline in scripts/train_pricing_models.py
and pricing_modeling_metadata.json — final_premium_vnd is a benchmark, NEVER a target):
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

Requirements: R12.7, R4.7, R29.5, R30.5, R31.3, R31.4 (design section 6.3, BR-19/C-8)
"""

import json
import pathlib
import warnings

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd

warnings.filterwarnings("ignore")

# Minimum exposure floor to avoid division-by-zero when computing per-year rates
# (matches scripts/train_pricing_models.py: earned_exposure_years.clip(lower=1e-6)).
MIN_EXPOSURE = 1e-6

# ── Paths ──────────────────────────────────────────────────────────────────
ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data" / "synthetic_real"
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
METADATA_PATH = DATA_DIR / "pricing_modeling_metadata.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]

# ── Monotone variable definitions ──────────────────────────────────────────
# Key: feature name -> constraint direction (+1 increasing, -1 decreasing, 0 none)
# annual_mileage_km only applies to car/motorbike (driving exposure lines)
MONOTONE_COMMON = {
    "coverage_amount_vnd": 1,
    "deductible_vnd": -1,
    "claim_count_36m_prior": 1,
}
MONOTONE_VEHICLE = {
    **MONOTONE_COMMON,
    "annual_mileage_km": 1,
}

# ── Exclusion sets ─────────────────────────────────────────────────────────
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


def build_monotone_constraints(feature_names: list[str], line: str) -> list[int]:
    """Build monotone_constraints list aligned with feature column order.

    The constraint list position MUST match the column order used during
    training (same order as feature_names). This is critical — misalignment
    causes the constraint to apply to the wrong variable (HIGH RISK per design).
    """
    monotone_map = MONOTONE_VEHICLE if line in ("car", "motorbike") else MONOTONE_COMMON
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
    existing_features = list(existing.feature_name_)
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

    # Copy existing hyperparameters, add monotone_constraints
    params = existing.get_params()
    params["monotone_constraints"] = monotone_constraints
    params["verbose"] = -1

    model = lgb.LGBMRegressor(**params)
    model.fit(feat_df, target, sample_weight=sample_weight)
    return model


def main():
    """Re-fit all 18 LightGBM champion models with monotone_constraints."""
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    results = []
    for line in LINES:
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

    print(f"\n{'='*60}")
    print("Summary:")
    for r in results:
        print(f"  {r}")
    print(f"\nAll 18 champion LightGBM models re-fitted with monotone_constraints.")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
