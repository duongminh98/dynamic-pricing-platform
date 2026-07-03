# Offline model-lifecycle image (Cloud Run Jobs: export ▸ train ▸ validate ▸
# compare ▸ register ▸ drift). Unlike the pricing serving image, this one
# DELIBERATELY bundles offline/ + scripts/ + the reference data/ tree, since
# Cloud Run Jobs have no host volumes and each job step is stateless.
#
# Build context = repo root:
#   docker build -f offline/Dockerfile -t <registry>/lifecycle:<tag> .
FROM python:3.11-slim AS runtime
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

# Reuse the pricing dependency set (scikit-learn/LightGBM/joblib/SQLAlchemy/
# google-cloud-storage/…). validate_pricing_models.py additionally needs
# matplotlib for fairness/smoothness plots.
COPY pricing/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt matplotlib>=3.8.0

# The offline scripts import `from app.*` (pricing engine + DB models) and
# `from offline.*` / run `scripts/*.py`, so all three trees live under /app and
# resolve via PYTHONPATH=/app. `pricing/` is copied so that `app` is /app/app.
COPY pricing/ /app/
COPY offline/ /app/offline/
COPY scripts/ /app/scripts/

# Only the SMALL reference files are baked in (metadata + geo/cost/products +
# champion_config fallback). The large training tables (pricing_freq_*,
# policy_exposure_*, policies/claims/customers, *_profiles) are NOT baked — the
# export job writes datasets to GCS and train/compare read them back via the
# gs:// object-storage adapter. A repo-root .gcloudignore keeps the big CSVs out
# of the Cloud Build upload context.
COPY data/synthetic_real_1m_history_lift_v2/pricing_modeling_metadata.json /app/data/synthetic_real_1m_history_lift_v2/pricing_modeling_metadata.json
COPY data/synthetic_real_1m_history_lift_v2/geo_risk.csv                   /app/data/synthetic_real_1m_history_lift_v2/geo_risk.csv
COPY data/synthetic_real_1m_history_lift_v2/cost_indices.csv              /app/data/synthetic_real_1m_history_lift_v2/cost_indices.csv
COPY data/synthetic_real_1m_history_lift_v2/products.csv                  /app/data/synthetic_real_1m_history_lift_v2/products.csv
COPY reports/modeling/models/champion_config.json /app/reports/modeling/models/champion_config.json

ENV PYTHONPATH=/app \
    OBJECT_STORAGE_PROVIDER=gcs

# No ENTRYPOINT: each Cloud Run Job overrides `command`/`args` to run a specific
# step, e.g. `python offline/train_pricing_models.py --line car --output-uri gs://…`.
