#!/usr/bin/env bash
# Build a standalone kubeconfig for the GKE cluster that authenticates with a
# static bearer token from `gcloud auth print-access-token` — this AVOIDS the
# gke-gcloud-auth-plugin (which needs admin rights to install into the SDK dir).
#
# The access token expires ~1h; re-run this script to refresh it. Exports
# KUBECONFIG for the caller when sourced:  . deploy/kubeconfig.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a; . "$HERE/deploy/config.env"; set +a

mkdir -p "$HERE/.deploy"
KCFG="$HERE/.deploy/kubeconfig.yaml"
EP="$(gcloud container clusters describe "$GKE_CLUSTER" --region "$REGION" --project "$PROJECT_ID" --format='value(endpoint)')"
CA="$(gcloud container clusters describe "$GKE_CLUSTER" --region "$REGION" --project "$PROJECT_ID" --format='value(masterAuth.clusterCaCertificate)')"
TOKEN="$(gcloud auth print-access-token)"

cat > "$KCFG" <<EOF
apiVersion: v1
kind: Config
clusters:
- name: dpp
  cluster:
    server: https://$EP
    certificate-authority-data: $CA
contexts:
- name: dpp
  context:
    cluster: dpp
    user: dpp
current-context: dpp
users:
- name: dpp
  user:
    token: $TOKEN
EOF
export KUBECONFIG="$KCFG"
echo "KUBECONFIG=$KCFG (token valid ~1h)"
