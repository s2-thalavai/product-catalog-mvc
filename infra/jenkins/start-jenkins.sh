#!/usr/bin/env bash
# Starts the local Jenkins and connects it to the k3d cluster network.
# Run infra/k3d/create-cluster.sh first (it writes secrets/kubeconfig-k3s).
#   ./infra/jenkins/start-jenkins.sh
set -euo pipefail

CLUSTER="${CLUSTER:-catalog}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

[[ -f .env ]] || { echo "ERROR: create $DIR/.env from .env.example first"; exit 1; }
[[ -f secrets/kubeconfig-k3s ]] || { echo "ERROR: secrets/kubeconfig-k3s missing - run infra/k3d/create-cluster.sh"; exit 1; }

docker compose up -d --build

# Let Jenkins reach the k3d API server (k3d-<cluster>-serverlb:6443) by joining its Docker network.
NETWORK="k3d-${CLUSTER}"
if docker network inspect "$NETWORK" >/dev/null 2>&1; then
  docker network connect "$NETWORK" catalog-mvc-jenkins 2>/dev/null || true
  echo "Jenkins connected to Docker network $NETWORK"
else
  echo "WARNING: Docker network $NETWORK not found - deploy stages will not reach the cluster"
fi

echo "Waiting for Jenkins on http://localhost:8092 ..."
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null http://localhost:8092/login; then
    echo "Jenkins is up: http://localhost:8092  (user from .env). Job: product-catalog-mvc"
    exit 0
  fi
  sleep 5
done
echo "Jenkins did not answer yet - check: docker logs -f catalog-mvc-jenkins"
exit 1
