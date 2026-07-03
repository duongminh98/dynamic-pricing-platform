#!/usr/bin/env bash
set -euo pipefail

# Startup sequence for the pricing serving container.
#
#   RUN_MIGRATIONS         (default "true")  — apply Alembic migrations before
#       serving. In the cloud, run migrations as a one-shot Job and set this to
#       "false" on serving pods so concurrent cold starts don't race
#       (GCP_DEPLOYMENT.md §6.4).
#   REFERENCE_DATA_URI     (optional)        — gs://… or s3://… prefix holding
#       <uri>/data and <uri>/models. When set, its contents are synced into the
#       paths the loader expects (via the Python object-storage adapter, so no
#       gcloud/aws CLI is required) before uvicorn starts — resolving the "no
#       host volume in the cloud" blocker (GCP_DEPLOYMENT.md §2.6 / §6.3).
#       Skipped when unset (compose mounts the volumes instead).
#   PRICING_REFERENCE_DIR  (default /app/data/synthetic_real_1m_history_lift_v2)
#   PRICING_MODELS_DIR     (default /app/reports/modeling/models)

if [[ -n "${REFERENCE_DATA_URI:-}" ]]; then
  echo "Bootstrapping reference data from ${REFERENCE_DATA_URI} ..."
  python -m app.bootstrap_reference_data
fi

if [[ "${RUN_MIGRATIONS:-true}" == "true" ]]; then
  echo "Running alembic upgrade head..."
  alembic upgrade head || { echo "alembic upgrade failed"; exit 1; }
else
  echo "RUN_MIGRATIONS=false — skipping alembic (migrations run as a separate Job)."
fi

echo "Starting uvicorn on :8000..."
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
