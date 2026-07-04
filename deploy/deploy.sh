#!/usr/bin/env bash
# Deploy the platform to the GKE staging cluster. Assumes provision.sh +
# build_images.sh already ran. Idempotent. Reads deploy/config.env.
#
# Steps: creds -> ConfigMaps (kong/rabbitmq/keycloak) -> k8s Secret from Secret
# Manager -> namespace+SAs -> infra (rabbitmq/keycloak) -> migration Jobs (gate)
# -> app services -> edge (kong/frontend/ingress). Serving pods never migrate.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . deploy/config.env; set +a
K="kubectl --namespace $K8S_NAMESPACE"
log() { echo -e "\n\033[1;36m== $* ==\033[0m"; }

log "Fetch cluster credentials (static-token kubeconfig; avoids gke-gcloud-auth-plugin)"
bash deploy/kubeconfig.sh
export KUBECONFIG="$PWD/.deploy/kubeconfig.yaml"

log "Render manifests at current IMAGE_TAG=$IMAGE_TAG"
python deploy/render_k8s.py

log "Namespace + service accounts"
kubectl apply -f deploy/k8s/00-namespace.yaml

log "ConfigMaps from source files"
$K create configmap kong-prod-config      --from-file=kong.yml=infra/kong/kong.prod.yml            --dry-run=client -o yaml | $K apply -f -
$K create configmap rabbitmq-definitions  --from-file=definitions.json=infra/rabbitmq/definitions.json --dry-run=client -o yaml | $K apply -f -
$K create configmap keycloak-realm        --from-file=realm-export.json=infra/keycloak/realm-export.json --dry-run=client -o yaml | $K apply -f -

# The k8s Secret is synced from Secret Manager (provision.sh created the secrets).
log "Sync dpp-secrets from Secret Manager"
sm() { gcloud secrets versions access latest --secret="$1" --project "$PROJECT_ID"; }
$K create secret generic dpp-secrets \
  --from-literal=db-password="$(sm db-password)" \
  --from-literal=rabbitmq-password="$(sm rabbitmq-password)" \
  --from-literal=keycloak-admin="$(sm keycloak-admin)" \
  --from-literal=keycloak-admin-password="$(sm keycloak-admin-password)" \
  --from-literal=vnp-tmn-code="$(sm vnp-tmn-code 2>/dev/null || echo '')" \
  --from-literal=vnp-hash-secret="$(sm vnp-hash-secret 2>/dev/null || echo '')" \
  --dry-run=client -o yaml | $K apply -f -

log "Infra: RabbitMQ + Keycloak"
kubectl apply -f deploy/k8s/20-rabbitmq.yaml -f deploy/k8s/21-keycloak.yaml
$K rollout status statefulset/rabbitmq --timeout=300s
$K rollout status deployment/keycloak --timeout=420s

# definitions.json bakes a fixed password_hash for the platform_user (a dev-only
# value), which overrides RABBITMQ_DEFAULT_PASS. Reset it to the Secret-Manager
# password so the services (which auth with that secret) can connect.
log "Align RabbitMQ platform_user password with the secret"
$K exec statefulset/rabbitmq -c rabbitmq -- \
  rabbitmqctl change_password "$RABBITMQ_USER" "$(sm rabbitmq-password)"

log "Migration gate (Alembic for pricing_db)"
# Java services (Flyway) migrate at serving-pod startup — Flyway's table lock
# makes concurrent starts safe, and the app process never exits so a migrate
# Job would hang. Only pricing needs a dedicated one-shot Alembic Job.
kubectl apply -f deploy/k8s/90-migrations.yaml
echo "waiting for job/migrate-pricing"
$K wait --for=condition=complete job/migrate-pricing --timeout=300s

log "App services"
kubectl apply -f deploy/k8s/10-customer-service.yaml -f deploy/k8s/10-product-service.yaml \
  -f deploy/k8s/10-order-service.yaml -f deploy/k8s/10-claims-service.yaml \
  -f deploy/k8s/10-billing-service.yaml -f deploy/k8s/10-notification-service.yaml \
  -f deploy/k8s/11-pricing-service.yaml
for d in customer-service product-service order-service claims-service billing-service notification-service pricing-service; do
  $K rollout status "deployment/$d" --timeout=300s
done

log "Edge: Kong + frontend + Ingress + NetworkPolicy"
kubectl apply -f deploy/k8s/30-edge.yaml
$K rollout status deployment/kong --timeout=300s
$K rollout status deployment/frontend --timeout=180s

log "Done. Ingress IP (managed cert may take 10-60m to go ACTIVE):"
$K get ingress dpp-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}'; echo
echo "Run deploy/smoke.sh once the cert is ACTIVE and DNS resolves."
