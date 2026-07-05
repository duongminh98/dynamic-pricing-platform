#!/usr/bin/env bash
# Deploy the OFFLINE model-lifecycle tier to GCP (coarse 2-job shape):
#   * Cloud Run Job  pricing-drift-monitor  (offline/drift_monitor.py)
#   * Cloud Run Job  pricing-lifecycle      (offline/retrain_trigger.py, chains
#                                            export▸train▸compare▸gates▸register)
#   * Cloud Workflow pricing-lifecycle      (drift ▸ lifecycle; deploy/workflows/)
#   * Cloud Scheduler pricing-lifecycle-daily (02:00 daily ▸ triggers the Workflow)
#
# Assumes provision.sh already ran (APIs enabled, pricing-lifecycle-sa created
# with cloudsql.client/secretAccessor/run.invoker/workflows.invoker + bucket
# access, secrets pricing-db-url + db-password present, Cloud SQL up). Idempotent:
# re-running updates in place. Reads deploy/config.env. Run from repo root.
#
# The lifecycle IMAGE is built manually (offline/Dockerfile COPYs gitignored ref
# files, so CD skips it) and pushed as :latest — see deploy/build_images.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . deploy/config.env; set +a
log() { echo -e "\n\033[1;36m== $* ==\033[0m"; }

SA="pricing-lifecycle-sa@$PROJECT_ID.iam.gserviceaccount.com"
IMAGE="$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO/lifecycle:latest"
SQL_INST_CONN="$PROJECT_ID:$REGION:$SQL_INSTANCE"
# Cloud SQL is PRIVATE-IP only (provision.sh --no-assign-ip), so the jobs must
# reach it over the VPC via Direct VPC egress. --set-cloudsql-instances then
# routes the proxy through that VPC path (no separate VPC connector needed).
# Names match deploy/provision.sh (dpp-vpc / dpp-subnet).
NETWORK="${NETWORK:-dpp-vpc}"
SUBNET="${SUBNET:-dpp-subnet}"

# Shared env for both Jobs. Offline scripts reach Cloud SQL two ways:
#   * psycopg2.connect(host=...) — retrain_trigger/drift_monitor read
#     PRICING_DB_HOST/POSTGRES_PASSWORD; point host at the connector unix socket.
#   * create_engine(DATABASE_URL) — build_training_dataset/app.database read the
#     full URL from the pricing-db-url secret (host=/cloudsql/<inst> form).
COMMON_ENV="PRICING_DB_HOST=/cloudsql/$SQL_INST_CONN"
COMMON_ENV="$COMMON_ENV,PRICING_DB_PORT=5432"
COMMON_ENV="$COMMON_ENV,PRICING_DB_NAME=pricing_db"
COMMON_ENV="$COMMON_ENV,POSTGRES_USER=$DB_USER"
COMMON_ENV="$COMMON_ENV,OBJECT_STORAGE_PROVIDER=gcs"
COMMON_SECRETS="POSTGRES_PASSWORD=db-password:latest"
COMMON_SECRETS="$COMMON_SECRETS,DATABASE_URL=pricing-db-url:latest"
COMMON_SECRETS="$COMMON_SECRETS,PRICING_DATABASE_URL=pricing-db-url:latest"

# gcloud has no idempotent "apply" for Run Jobs: `create` fails if it exists,
# `update` fails if it doesn't. Probe with `describe`, then create or update.
job_upsert() {  # job_name  extra_env  extra_flags...
  local name="$1" extra_env="$2"; shift 2
  local env="$COMMON_ENV"
  [ -n "$extra_env" ] && env="$env,$extra_env"
  local verb=update
  gcloud run jobs describe "$name" --region "$REGION" --project "$PROJECT_ID" \
    >/dev/null 2>&1 || verb=create
  log "Cloud Run Job $name ($verb)"
  gcloud run jobs "$verb" "$name" \
    --image "$IMAGE" \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --service-account "$SA" \
    --set-cloudsql-instances "$SQL_INST_CONN" \
    --network "$NETWORK" \
    --subnet "$SUBNET" \
    --vpc-egress private-ranges-only \
    --set-env-vars "$env" \
    --set-secrets "$COMMON_SECRETS" \
    "$@"
}

# drift-monitor: computes PSI + calibration drift, persists model_drift_flag.
job_upsert pricing-drift-monitor "" \
  --command python \
  --args offline/drift_monitor.py \
  --memory 1Gi \
  --task-timeout 900

# lifecycle: retrain_trigger.py decides + chains the in-process pipeline
# (export▸train▸compare▸monotonic▸smoothness▸register CANDIDATE). Needs the GCS
# object-storage prefix + reference-data mount, and headroom for LightGBM train.
job_upsert pricing-lifecycle \
  "LIFECYCLE_OBJECT_STORAGE_URI=gs://$BUCKET_MODELS,REFERENCE_DATA_URI=gs://$BUCKET_REFERENCE,PRICING_REFERENCE_DIR=/app/data/synthetic_real_1m_history_lift_v2,PRICING_MODELS_DIR=/app/reports/modeling/models" \
  --command python \
  --args offline/retrain_trigger.py \
  --memory 2Gi \
  --cpu 2 \
  --task-timeout 3600

log "Deploy Cloud Workflow: pricing-lifecycle"
gcloud workflows deploy pricing-lifecycle \
  --source=deploy/workflows/pricing-lifecycle.yaml \
  --location="$REGION" \
  --project "$PROJECT_ID" \
  --service-account="$SA"

log "Cloud Scheduler: pricing-lifecycle-daily (02:00 daily -> Workflow)"
# drift_monitor runs first INSIDE the workflow, so a single daily trigger keeps
# the README's drift-before-retrain ordering without two scheduler entries.
WF_EXEC_URI="https://workflowexecutions.googleapis.com/v1/projects/$PROJECT_ID/locations/$REGION/workflows/pricing-lifecycle/executions"
SCHED_VERB=update
gcloud scheduler jobs describe pricing-lifecycle-daily \
  --location "$REGION" --project "$PROJECT_ID" >/dev/null 2>&1 || SCHED_VERB=create
gcloud scheduler jobs "$SCHED_VERB" http pricing-lifecycle-daily \
  --location="$REGION" \
  --project "$PROJECT_ID" \
  --schedule="0 2 * * *" \
  --time-zone="Etc/UTC" \
  --uri="$WF_EXEC_URI" \
  --http-method=POST \
  --oauth-service-account-email="$SA" \
  --message-body="{}"

log "DONE. Smoke: gcloud run jobs execute pricing-lifecycle --region $REGION \\"
echo "        --args offline/retrain_trigger.py,--line,car --wait"
