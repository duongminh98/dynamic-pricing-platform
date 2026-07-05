#!/usr/bin/env bash
# Provision the dpp-staging GCP foundation: APIs, VPC, Artifact Registry,
# Cloud SQL (8 DBs), GCS buckets, Secret Manager, service accounts + IAM,
# GKE Autopilot cluster, reference-data upload. Idempotent where practical.
#
# Reads deploy/config.env. Run from repo root:  bash deploy/provision.sh
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . deploy/config.env; set +a

log() { echo -e "\n\033[1;36m== $* ==\033[0m"; }
require_non_placeholder() {
  local name="$1" value="$2"
  if [[ -z "$value" || "$value" == *PLACEHOLDER* ]]; then
    echo "Missing required secret value: $name. Export $name before running provision.sh." >&2
    exit 1
  fi
}

log "APIs"
gcloud services enable \
  compute.googleapis.com container.googleapis.com sqladmin.googleapis.com \
  secretmanager.googleapis.com artifactregistry.googleapis.com \
  servicenetworking.googleapis.com cloudbuild.googleapis.com \
  storage.googleapis.com iam.googleapis.com dns.googleapis.com \
  --project "$PROJECT_ID"

log "VPC + subnet + private service access"
gcloud compute networks create dpp-vpc --subnet-mode=custom --project "$PROJECT_ID" || true
gcloud compute networks subnets create dpp-subnet \
  --network=dpp-vpc --range=10.20.0.0/20 --region="$REGION" \
  --enable-private-ip-google-access --project "$PROJECT_ID" || true
gcloud compute addresses create dpp-psa-range --global --purpose=VPC_PEERING \
  --prefix-length=16 --network=dpp-vpc --project "$PROJECT_ID" || true
gcloud services vpc-peerings connect --service=servicenetworking.googleapis.com \
  --ranges=dpp-psa-range --network=dpp-vpc --project "$PROJECT_ID" || true
gcloud compute addresses create dpp-ingress-ip --global --project "$PROJECT_ID" || true

log "Cloud DNS managed zone (public) for $BASE_DOMAIN"
# The Cloud Domains registration delegates NS to this zone; deploy.sh writes the
# A records once the ingress LBs have IPs.
gcloud dns managed-zones create "$DNS_ZONE" --dns-name="$BASE_DOMAIN." \
  --description="dpp public zone" --project "$PROJECT_ID" || true

log "Artifact Registry"
gcloud artifacts repositories create "$AR_REPO" --repository-format=docker \
  --location="$REGION" --project "$PROJECT_ID" || true

# `gcloud builds submit` runs as the default compute SA, which on a fresh
# project lacks access to the Cloud Build source bucket + Artifact Registry.
PROJECT_NUM="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
CB_SA="$PROJECT_NUM-compute@developer.gserviceaccount.com"
for role in roles/storage.admin roles/artifactregistry.writer roles/logging.logWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$CB_SA" --role="$role" --condition=None >/dev/null
done

log "GCS buckets"
for b in "$BUCKET_DATASETS" "$BUCKET_MODELS" "$BUCKET_REPORTS" "$BUCKET_REFERENCE" "$BUCKET_FRONTEND"; do
  gcloud storage buckets create "gs://$b" --location="$REGION" \
    --uniform-bucket-level-access --project "$PROJECT_ID" || true
done

log "Cloud SQL (Postgres 16, private IP)"
if ! gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud sql instances create "$SQL_INSTANCE" \
    --database-version=POSTGRES_16 --edition=ENTERPRISE --tier=db-custom-2-7680 --region="$REGION" \
    --network="projects/$PROJECT_ID/global/networks/dpp-vpc" --no-assign-ip \
    --storage-auto-increase --backup --enable-point-in-time-recovery \
    --project "$PROJECT_ID"
fi
DB_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
gcloud sql users set-password "$DB_USER" --instance="$SQL_INSTANCE" \
  --password="$DB_PASS" --project "$PROJECT_ID" 2>/dev/null || \
gcloud sql users create "$DB_USER" --instance="$SQL_INSTANCE" \
  --password="$DB_PASS" --project "$PROJECT_ID"
for db in customer_db product_db order_db claims_db billing_db notification_db pricing_db keycloak_db; do
  gcloud sql databases create "$db" --instance="$SQL_INSTANCE" --project "$PROJECT_ID" || true
done

log "Secret Manager"
RABBIT_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
KC_ADMIN_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
put_secret() { # name value
  printf '%s' "$2" | gcloud secrets create "$1" --data-file=- --project "$PROJECT_ID" 2>/dev/null || \
  printf '%s' "$2" | gcloud secrets versions add "$1" --data-file=- --project "$PROJECT_ID"; }
put_secret db-password "$DB_PASS"
put_secret rabbitmq-password "$RABBIT_PASS"
put_secret keycloak-admin admin
put_secret keycloak-admin-password "$KC_ADMIN_PASS"
require_non_placeholder VNP_TMN_CODE "${VNP_TMN_CODE:-}"
require_non_placeholder VNP_HASH_SECRET "${VNP_HASH_SECRET:-}"
put_secret vnp-tmn-code "$VNP_TMN_CODE"
put_secret vnp-hash-secret "$VNP_HASH_SECRET"

log "Service accounts + IAM (Workload Identity)"
declare -a SVCS=(customer-service product-service order-service claims-service billing-service notification-service)
for s in "${SVCS[@]}"; do
  gcloud iam service-accounts create "svc-$s" --project "$PROJECT_ID" || true
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:svc-$s@$PROJECT_ID.iam.gserviceaccount.com" \
    --role=roles/cloudsql.client --condition=None >/dev/null
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:svc-$s@$PROJECT_ID.iam.gserviceaccount.com" \
    --role=roles/secretmanager.secretAccessor --condition=None >/dev/null
done
# pricing runtime SA (read-only on model/report/reference buckets)
gcloud iam service-accounts create pricing-runtime-sa --project "$PROJECT_ID" || true
gcloud iam service-accounts create pricing-lifecycle-sa --project "$PROJECT_ID" || true
gcloud iam service-accounts create keycloak --project "$PROJECT_ID" || true
for role in roles/cloudsql.client roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:pricing-runtime-sa@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="$role" --condition=None >/dev/null
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:keycloak@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="$role" --condition=None >/dev/null
done
for b in "$BUCKET_MODELS" "$BUCKET_REPORTS" "$BUCKET_REFERENCE"; do
  gcloud storage buckets add-iam-policy-binding "gs://$b" \
    --member="serviceAccount:pricing-runtime-sa@$PROJECT_ID.iam.gserviceaccount.com" \
    --role=roles/storage.objectViewer >/dev/null
done
for b in "$BUCKET_DATASETS" "$BUCKET_MODELS" "$BUCKET_REPORTS"; do
  gcloud storage buckets add-iam-policy-binding "gs://$b" \
    --member="serviceAccount:pricing-lifecycle-sa@$PROJECT_ID.iam.gserviceaccount.com" \
    --role=roles/storage.objectAdmin >/dev/null
done

log "GKE Autopilot cluster"
if ! gcloud container clusters describe "$GKE_CLUSTER" --region "$REGION" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud container clusters create-auto "$GKE_CLUSTER" \
    --region="$REGION" --network=dpp-vpc --subnetwork=dpp-subnet \
    --release-channel=regular --project "$PROJECT_ID"
fi

log "Workload Identity bindings (KSA -> GSA)"
bind_wi() { # ksa gsa
  gcloud iam service-accounts add-iam-policy-binding "$2@$PROJECT_ID.iam.gserviceaccount.com" \
    --role=roles/iam.workloadIdentityUser \
    --member="serviceAccount:$PROJECT_ID.svc.id.goog[$NAMESPACE/$1]" --project "$PROJECT_ID" >/dev/null; }
for s in "${SVCS[@]}"; do bind_wi "$s-sa" "svc-$s"; done
bind_wi pricing-runtime-sa-ksa pricing-runtime-sa
bind_wi keycloak-sa keycloak

log "Upload reference data to GCS"
# The pricing serving loader needs ONLY the small reference files + champion
# models — NOT the 3.6GB training tables (baking those in evicts the pod at its
# 1Gi ephemeral limit). bootstrap_reference_data.py pulls {base}/data -> the
# reference dir and {base}/models -> the models dir, both FLAT, so upload flat.
DATA_DIR=data/synthetic_real_1m_history_lift_v2
for f in pricing_modeling_metadata.json geo_risk.csv cost_indices.csv products.csv summary.json; do
  gcloud storage cp "$DATA_DIR/$f" "gs://$BUCKET_REFERENCE/data/$f" --project "$PROJECT_ID"
done
# Champion registry + the joblib artifacts it references (loader falls back to
# these when the fresh pricing_db has no champion_assignment yet).
gcloud storage cp reports/modeling/models/champion_config.json \
  "gs://$BUCKET_REFERENCE/models/champion_config.json" --project "$PROJECT_ID"
gcloud storage rsync reports/modeling/models "gs://$BUCKET_REFERENCE/models" \
  --project "$PROJECT_ID"

log "Budget alert (\$300 credit guard)"
BILLING="$(gcloud billing projects describe "$PROJECT_ID" --format='value(billingAccountName)' | sed 's#billingAccounts/##')"
gcloud billing budgets create --billing-account="$BILLING" \
  --display-name="dpp-staging-300usd" \
  --budget-amount=300USD \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0 \
  --project "$PROJECT_ID" 2>/dev/null || echo "  (budget may already exist / needs billing.budgets scope)"

# Persist the Cloud SQL private IP back into config.env for the renderer
PRIV_IP="$(gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT_ID" \
  --format='value(ipAddresses[0].ipAddress)')"
echo "Cloud SQL private IP: $PRIV_IP  (Cloud SQL proxy sidecar uses instance name, so no manifest change needed)"
log "DONE. Secrets stored in Secret Manager; DB password NOT echoed."
