#!/usr/bin/env bash
# Build + push all 9 images to Artifact Registry via Cloud Build (no local
# Docker needed). Tags each with the current git SHA + latest, then pins
# config.env IMAGE_TAG to the SHA. Reads deploy/config.env. Run from repo root.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . deploy/config.env; set +a

SHA="$(git rev-parse --short HEAD)"
REPO="$REGION-docker.pkg.dev/$PROJECT_ID/$AR_REPO"
log() { echo -e "\n\033[1;36m== $* ==\033[0m"; }

# Build one image with an inline cloudbuild config so we can pass --build-arg
# and a custom Dockerfile path. $3.. = build args (k=v), written as docker args.
build_one() {
  local img="$1" file="$2"; shift 2
  local cb="/tmp/cb-${img}.yaml"
  {
    echo "steps:"
    echo "  - name: gcr.io/cloud-builders/docker"
    echo "    args:"
    echo "      - build"
    echo "      - -f"
    echo "      - $file"
    echo "      - -t"
    echo "      - $REPO/$img:$SHA"
    echo "      - -t"
    echo "      - $REPO/$img:latest"
    for kv in "$@"; do
      echo "      - --build-arg"
      echo "      - $kv"
    done
    echo "      - ."
    echo "images:"
    echo "  - $REPO/$img:$SHA"
    echo "  - $REPO/$img:latest"
    echo "options:"
    echo "  machineType: E2_HIGHCPU_8"
  } > "$cb"
  log "Build $img ($file)"
  gcloud builds submit --config "$cb" --project "$PROJECT_ID" .
}

for s in customer-service product-service order-service claims-service billing-service notification-service; do
  build_one "$s" services/Dockerfile "SERVICE=$s"
done
build_one pricing-service pricing/Dockerfile
build_one lifecycle offline/Dockerfile
build_one frontend frontend/Dockerfile \
  "VITE_API_BASE=https://$API_HOST" \
  "VITE_KEYCLOAK_URL=https://$AUTH_HOST" \
  "VITE_KEYCLOAK_REALM=dynamic-pricing" \
  "VITE_KEYCLOAK_CLIENT_ID=mini-app"

sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$SHA/" deploy/config.env
log "Built + pushed 9 images at tag $SHA; config.env IMAGE_TAG updated."
