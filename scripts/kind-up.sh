#!/usr/bin/env bash
#
# Creates a local kind cluster, builds the application image into it, and applies k8s/.
#
# The Secret is generated here and never written to the repository. There is no committed
# secret.yaml, not even with placeholder values: a placeholder is a default secret waiting to
# reach production, which is the thing Phase 2 of this work removed.
#
# Idempotent. Running it again on an existing cluster rebuilds the image, reloads it and rolls
# the deployment, keeping the Secret that is already there so pods are not invalidated.
#
# Usage:
#   scripts/kind-up.sh [--recreate] [--skip-build]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CLUSTER_NAME="ratelimitx"
NAMESPACE="ratelimitx"
IMAGE="ratelimitx:local"
RECREATE=false
SKIP_BUILD=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --recreate) RECREATE=true; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

log() { printf '\n== %s\n' "$*"; }
kube() { kubectl --context "kind-$CLUSTER_NAME" "$@"; }

for tool in kind kubectl docker openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 1; }
done

# ---------------------------------------------------------------------------
# Cluster
# ---------------------------------------------------------------------------

if [[ "$RECREATE" == true ]] \
   && grep -qx "$CLUSTER_NAME" <<< "$(kind get clusters 2>/dev/null)"; then
  log "Deleting the existing cluster"
  kind delete cluster --name "$CLUSTER_NAME"
fi

if grep -qx "$CLUSTER_NAME" <<< "$(kind get clusters 2>/dev/null)"; then
  log "Cluster $CLUSTER_NAME already exists"
else
  log "Creating the cluster"
  kind create cluster --config k8s/kind-cluster.yaml --wait 120s
fi

# kubectl's current context may point anywhere; every call here is pinned to this cluster, and
# this check fails loudly rather than applying manifests somewhere unexpected.
kube cluster-info >/dev/null

# ---------------------------------------------------------------------------
# Image
# ---------------------------------------------------------------------------

if [[ "$SKIP_BUILD" == true ]]; then
  log "Skipping the image build at request"
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || { echo "$IMAGE is not present locally and --skip-build was given" >&2; exit 1; }
else
  log "Building $IMAGE"
  docker build --tag "$IMAGE" .
fi

log "Loading $IMAGE into the cluster"
# The cluster has no registry to pull from, so the image is side-loaded into the node.
kind load docker-image "$IMAGE" --name "$CLUSTER_NAME"

# ---------------------------------------------------------------------------
# Secret
#
# Generated, never committed. kubectl is given the values directly rather than through a file, so
# no secret is written to disk at any point.
# ---------------------------------------------------------------------------

kube apply -f k8s/00-namespace.yaml

if kube -n "$NAMESPACE" get secret ratelimitx-secrets >/dev/null 2>&1; then
  log "Keeping the existing Secret"
  # Rotating it would invalidate every issued token and, worse, leave the application with a
  # database password Postgres no longer has: the Postgres volume keeps the password it was
  # first initialised with.
else
  log "Generating the Secret"
  # 48 bytes base64-encoded, comfortably over the 32-byte minimum JwtProperties enforces.
  kube -n "$NAMESPACE" create secret generic ratelimitx-secrets \
    --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
    --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"
  echo "  generated; it exists only in the cluster"
fi

# ---------------------------------------------------------------------------
# Manifests
# ---------------------------------------------------------------------------

log "Applying the manifests"
kube apply -f k8s/10-configmap.yaml -f k8s/20-postgres.yaml -f k8s/21-redis.yaml \
  -f k8s/30-app.yaml -f k8s/31-app-nodeport.yaml

log "Waiting for Postgres and Redis"
kube -n "$NAMESPACE" rollout status statefulset/postgres --timeout=180s
kube -n "$NAMESPACE" rollout status statefulset/redis --timeout=180s

# An existing deployment keeps running the previous image under the same tag, so a rebuild needs
# a restart to be picked up. Harmless on a fresh cluster.
log "Rolling the application onto the loaded image"
kube -n "$NAMESPACE" rollout restart deployment/ratelimitx
kube -n "$NAMESPACE" rollout status deployment/ratelimitx --timeout=300s

log "Waiting for the Service to have both endpoints ready"
for _ in $(seq 1 60); do
  ready="$(kube -n "$NAMESPACE" get endpointslice -l kubernetes.io/service-name=ratelimitx \
    -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' \
    2>/dev/null | grep -c true || true)"
  [[ "${ready:-0}" -ge 2 ]] && break
  sleep 2
done
echo "  ready endpoints: ${ready:-0}"

# ---------------------------------------------------------------------------
# Proof it answers
# ---------------------------------------------------------------------------

BASE_URL="http://127.0.0.1:18080"
log "Checking the API answers on $BASE_URL"
for _ in $(seq 1 30); do
  # An unauthenticated request must be refused with 401. That is a working application and a
  # working Service: anything else here means one of them is not.
  status="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/data" || echo 000)"
  [[ "$status" == "401" ]] && break
  sleep 2
done
if [[ "$status" != "401" ]]; then
  echo "unexpected status from $BASE_URL/api/data: $status (expected 401)" >&2
  kube -n "$NAMESPACE" get pods
  exit 1
fi
echo "  GET /api/data -> 401 as expected for an unauthenticated caller"

log "Done"
kube -n "$NAMESPACE" get pods -o wide
cat <<EOF

  API:        $BASE_URL
  Namespace:  $NAMESPACE   (kubectl --context kind-$CLUSTER_NAME -n $NAMESPACE ...)
  Tear down:  kind delete cluster --name $CLUSTER_NAME
EOF
