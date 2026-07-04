#!/usr/bin/env bash
# One-time setup for keyless GitHub Actions -> GCP auth (Workload Identity
# Federation). Creates a WIF pool + provider bound to this GitHub repo, a
# deploy service account with the roles cd.yml needs, and prints the two
# GitHub secret values to paste into the repo settings.
#
# Run ONCE from repo root:  bash deploy/setup_wif.sh
# Idempotent: re-running is safe (create steps tolerate "already exists").
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . deploy/config.env; set +a

# --- GitHub repo this WIF provider will trust (owner/repo) ------------------
GH_REPO="${GH_REPO:-duongminh98/dynamic-pricing-platform}"

POOL="github-pool"
PROVIDER="github-provider"
DEPLOY_SA="cd-deployer"
SA_EMAIL="$DEPLOY_SA@$PROJECT_ID.iam.gserviceaccount.com"
log() { echo -e "\n\033[1;36m== $* ==\033[0m"; }

PROJECT_NUM="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"

log "Enable IAM Credentials + STS APIs"
gcloud services enable iamcredentials.googleapis.com sts.googleapis.com \
  --project "$PROJECT_ID"

log "Workload Identity pool + provider (trusts $GH_REPO)"
gcloud iam workload-identity-pools create "$POOL" \
  --location=global --display-name="GitHub Actions" \
  --project "$PROJECT_ID" 2>/dev/null || true
# Provider restricts token exchange to this repo (assertion.repository) so no
# other repo can impersonate the deploy SA.
gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" \
  --location=global --workload-identity-pool="$POOL" \
  --display-name="GitHub OIDC" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository=='$GH_REPO'" \
  --project "$PROJECT_ID" 2>/dev/null || true

log "Deploy service account + roles"
gcloud iam service-accounts create "$DEPLOY_SA" \
  --display-name="CD deployer (GitHub Actions)" \
  --project "$PROJECT_ID" 2>/dev/null || true
# Roles the pipeline needs: submit Cloud Builds, push images, deploy to GKE,
# read secrets for the k8s Secret sync, manage DNS A-records, act as the
# per-service runtime SAs (Cloud Build/GKE workload identity).
for role in \
  roles/cloudbuild.builds.editor \
  roles/serviceusage.serviceUsageConsumer \
  roles/artifactregistry.writer \
  roles/container.developer \
  roles/secretmanager.secretAccessor \
  roles/dns.admin \
  roles/storage.admin \
  roles/iam.serviceAccountUser ; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$SA_EMAIL" --role="$role" --condition=None >/dev/null
done

log "Let GitHub Actions (this repo) impersonate the deploy SA"
gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUM/locations/global/workloadIdentityPools/$POOL/attribute.repository/$GH_REPO" \
  --project "$PROJECT_ID" >/dev/null

PROVIDER_RESOURCE="projects/$PROJECT_NUM/locations/global/workloadIdentityPools/$POOL/providers/$PROVIDER"

cat <<EOF

============================================================
WIF setup complete. Add these TWO secrets to the GitHub repo
(Settings -> Secrets and variables -> Actions -> New secret):

  GCP_WIF_PROVIDER = $PROVIDER_RESOURCE
  GCP_DEPLOY_SA    = $SA_EMAIL

After that, cd.yml runs automatically on every successful CI on master.
============================================================
EOF
