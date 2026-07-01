# Offline Model Lifecycle

All scripts in this directory run **offline only** and must never be invoked on
the serving quote path (`/pricing/quote`). They operate on the training dataset
under `data/synthetic_real_1m_history_lift_v2/` and on the champion artifacts under
`reports/modeling/models/`, and they talk to `pricing_db` only for registration
and drift bookkeeping.

## Scripts

| Script | Purpose |
| --- | --- |
| `build_training_dataset_from_pricing_db.py` | Export immutable pricing read-model datasets, write `manifest.json`, checksums, row counts, and optionally register `training_dataset_version`. |
| `train_pricing_models.py` | Re-fit the champion LightGBM artifacts with `monotone_constraints`. `--cv` runs anti-leakage cross-validation only. |
| `register_models.py` | Register the current champion `Model_Version` rows + `champion_assignment` and keep `champion_config.json` in sync. |
| `compare_candidate_to_champion.py` | Compare a candidate artifact against the current champion on the same exported holdout dataset. |
| `register_candidate_model.py` | Register a candidate `Model_Version` only after dataset, artifact, comparison, validation, monotonic, and smoothness gates pass. |
| `model_smoothness_gate.py` | Validate health premium smoothness across deterministic BMI and age sweeps before candidate registration. |
| `retrain_trigger.py` | Decide whether a retrain is due (schedule / data threshold) and chain train -> validate -> monotonic gate -> smoothness gate -> register **candidate**. Never promotes. |
| `drift_monitor.py` | Per-line input-feature drift (PSI) + calibration drift; persists `model_drift_flag` rows. |

## No-A/B model governance lifecycle

Runtime pricing remains champion-only. Candidate models are never served to
customers, shadowed, canaried, or assigned through sticky experiments.

1. Export and register a dataset version:

```bash
python offline/build_training_dataset_from_pricing_db.py \
  --database-url postgresql://platform_user:platform_password_dev_only@localhost:5440/pricing_db \
  --output-dir data/pricing_read_model_export \
  --dataset-version-id ds-2026-q3 \
  --register-registry \
  --created-by offline-operator
```

2. Train candidate artifacts:

```bash
python offline/train_pricing_models.py --line car
```

3. Compare candidate and champion on the same holdout:

```bash
python offline/compare_candidate_to_champion.py \
  --line car \
  --dataset-dir data/pricing_read_model_export \
  --candidate-artifact-dir reports/modeling/models \
  --champion-model-version-id <current-champion-id> \
  --output-file reports/modeling/comparison/car_comparison.json
```

Thresholds live in `offline/comparison_config.json`.

4. Register the candidate only after gates pass:

```bash
python offline/register_candidate_model.py \
  --line car \
  --dataset-version-id ds-2026-q3 \
  --artifact-uri reports/modeling/models/car__lgb_tw.joblib \
  --comparison-report-uri reports/modeling/comparison/car_comparison.json \
  --validation-report-uri reports/modeling/validation/car_validation.json \
  --registered-by offline-operator \
  --monotonic-passed \
  --smoothness-passed
```

5. Administrator reviews `/pricing/models`, then promotes, rejects, or rolls
   back through `/admin/champion/promote`, `/admin/models/reject`, and
   `/admin/champion/rollback`.

Promotion is append-only and audited. Failed promotion does not reject a
candidate; rejection is an explicit Administrator action.

## Anti-leakage cross-validation Ã¢â‚¬â€ where GroupKFold runs (task 20.8a)

Cross-validation uses **`GroupKFold` with `k=5`, grouped by `customer_id`**. A
customer can hold several policies/exposures, so a plain random `KFold` could
leak customer-specific signal across the train/validation boundary and inflate
the score. Grouping by `customer_id` puts each customer in exactly one fold, so
the estimate reflects performance on *unseen customers*.

- Explicit, self-contained step: `groupkfold_cv()` in
  `offline/train_pricing_models.py`. Run it with:

  ```bash
  .venv/Scripts/python.exe offline/train_pricing_models.py --cv
  ```

  This prints per-(line, family) mean Gini and **never overwrites the champion
  artifacts** (artifacts are produced by the default `main()` path).

- The full validation/comparison pipeline in
  `scripts/validate_pricing_models.py` applies the same anti-leakage principle
  via the `time_based_grouped` split (`group_col = customer_id`) declared in
  `data/synthetic_real_1m_history_lift_v2/pricing_modeling_metadata.json`.

## Monotonic exemption Ã¢â‚¬â€ travel (task 20.8b, BR-19)

The travel champion is a GLM (Tweedie) and therefore carries
`monotonic_applied = false` (GLMs do not use LightGBM-style
`monotone_constraints`; the coefficient signs are enforced at fit time). Travel
is marked `"monotonic_exempt": true` in `reports/modeling/models/champion_config.json`
with a documented reason. The promote gate in
`pricing/app/pricing_engine/governance.py` honours this exemption: a **GLM**
champion on a monotonic-exempt line may promote on the Gini criterion alone,
while **tree / LightGBM** candidates always require `monotonic_applied = true`
(BR-19 stays enforced for every non-exempt line). The exempt set lives in
`pricing/app/config.py` (`MONOTONIC_EXEMPT_LINES`) and is mirrored by
`offline/retrain_trigger.py`.

## Retrain trigger (task 23.1, R37.2 / BR-24)

`retrain_trigger.py` supports three independent trigger conditions configured in
`retrain_config.json`:

1. **Scheduled** Ã¢â‚¬â€ quarterly (`quarterly_months`, default Jan/Apr/Jul/Oct).
2. **Data threshold** Ã¢â‚¬â€ when new claims/exposure for a line exceeds
   `line_thresholds[line]`.
3. **Drift-driven** Ã¢â‚¬â€ when the latest `model_drift_flag` for a line has
   `needs_recalibration=true` (gated by `drift_trigger_enabled`). This reads
   the same `model_drift_flag` table that `GET /pricing/drift` exposes to
   admins, so the trigger source of truth matches what administrators see.

When triggered for a line it chains, stopping at the first failure:

```
train  ->  validate  ->  monotonic gate  ->  smoothness gate  ->  register CANDIDATE
```

The monotonic and smoothness gates **must pass before** the candidate is registered. The
trigger only writes a **candidate** `Model_Version` row; it never touches
`champion_assignment`, so it **never auto-promotes**. Promotion stays an
explicit, governed Administrator action (BR-23) via the promote endpoint in
`governance.py`.

### Scheduler hook (cron / GitHub Actions)

The script itself does not daemonize; it just decides whether a trigger
condition is met *today*. Drive it from an external scheduler.

**Important:** `drift_monitor.py` must run **before** `retrain_trigger.py` so
that `model_drift_flag` rows are fresh when the trigger reads them. For example,
run drift_monitor at 01:50 and retrain_trigger at 02:00.

**cron** (run daily at 02:00; the script no-ops unless a condition is met):

```cron
50 1 * * *  cd /opt/dpp && .venv/bin/python offline/drift_monitor.py >> /var/log/dpp/drift.log 2>&1
0 2 * * *   cd /opt/dpp && .venv/bin/python offline/retrain_trigger.py >> /var/log/dpp/retrain.log 2>&1
```

**GitHub Actions** (`.github/workflows/retrain.yml`):

```yaml
name: Offline retrain trigger
on:
  schedule:
    - cron: "0 2 1 1,4,7,10 *"   # 02:00 on the 1st of Jan/Apr/Jul/Oct
  workflow_dispatch: {}
jobs:
  retrain:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: "3.11" }
      - run: pip install -r pricing/requirements.txt
      - run: python offline/retrain_trigger.py
```

## Drift monitor (task 23.2, R37.7)

`drift_monitor.py` computes, per line:

- **Input-feature drift** via Population Stability Index (PSI) vs the training
  distribution.
- **Calibration drift** via the actual-vs-predicted shift by bin.

When a metric exceeds its configured threshold (`drift_threshold_psi`,
`drift_threshold_calibration`) the line's `needs_recalibration` flag is raised
and persisted to the `model_drift_flag` table (columns: `line`, `metric`,
`value`, `threshold`, `needs_recalibration`, `computed_at`). Administrators read
the latest per-line flags via `GET /pricing/drift`.

