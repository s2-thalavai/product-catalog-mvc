#!/usr/bin/env bash
# Creates a local k3s cluster inside Docker using k3d (works on Linux, macOS and Windows/WSL2).
# Also writes a kubeconfig that the Jenkins container can use (infra/jenkins/secrets/kubeconfig-k3s).
#
#   ./infra/k3d/create-cluster.sh            # cluster "catalog", ingress on localhost:8081
#   CLUSTER=demo HTTP_PORT=9080 ./infra/k3d/create-cluster.sh
set -euo pipefail

CLUSTER="${CLUSTER:-catalog}"
HTTP_PORT="${HTTP_PORT:-8081}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SECRETS_DIR="$ROOT/infra/jenkins/secrets"

for tool in docker k3d kubectl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: '$tool' is not installed (see HOW_TO_RUN.md)"; exit 1; }
done

if k3d cluster get "$CLUSTER" >/dev/null 2>&1; then
  echo "Cluster '$CLUSTER' already exists - reusing it."
else
  echo "Creating k3d cluster '$CLUSTER' (1 server, 1 agent, ingress on localhost:$HTTP_PORT)..."
  k3d cluster create "$CLUSTER" \
    --servers 1 \
    --agents 1 \
    --port "${HTTP_PORT}:80@loadbalancer" \
    --k3s-arg "--tls-san=k3d-${CLUSTER}-serverlb@server:0" \
    --wait
fi

kubectl config use-context "k3d-${CLUSTER}" >/dev/null
echo "Waiting for nodes to be Ready..."
kubectl wait --for=condition=Ready nodes --all --timeout=120s
kubectl get nodes -o wide

# Kubeconfig for Jenkins: Jenkins runs in a container attached to the k3d Docker network,
# so it reaches the API server by the load balancer's container name, not 0.0.0.0.
mkdir -p "$SECRETS_DIR"
k3d kubeconfig get "$CLUSTER" \
  | sed -E "s#server: https://[^[:space:]]+#server: https://k3d-${CLUSTER}-serverlb:6443#" \
  > "$SECRETS_DIR/kubeconfig-k3s"
chmod 600 "$SECRETS_DIR/kubeconfig-k3s"

cat <<EOF

Cluster ready.
  kubectl context : k3d-${CLUSTER}
  Ingress         : http://localhost:${HTTP_PORT}  (send Host: catalog-mvc.localhost)
  Jenkins config  : $SECRETS_DIR/kubeconfig-k3s

Next: ./infra/k3d/deploy-local.sh   (build image, import, deploy)
EOF
