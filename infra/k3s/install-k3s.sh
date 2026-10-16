#!/usr/bin/env bash
# Installs single-node k3s directly on a Linux host or VM (Ubuntu/Debian/RHEL, or WSL2 with systemd).
# For a laptop, infra/k3d/create-cluster.sh (k3s inside Docker) is usually simpler.
#
#   ./infra/k3s/install-k3s.sh
#   K3S_VERSION=v1.31.2+k3s1 ./infra/k3s/install-k3s.sh     # pin a version
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "ERROR: k3s runs on Linux. On macOS/Windows use infra/k3d/create-cluster.sh instead."
  exit 1
fi

INSTALL_ARGS=(--write-kubeconfig-mode 644)
if [[ -n "${K3S_VERSION:-}" ]]; then
  export INSTALL_K3S_VERSION="$K3S_VERSION"
else
  export INSTALL_K3S_CHANNEL=stable
fi

echo "==> Installing k3s (Traefik ingress, local-path storage and metrics-server included)"
curl -sfL https://get.k3s.io | sh -s - "${INSTALL_ARGS[@]}"

mkdir -p "$HOME/.kube"
cp /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config-k3s"
chmod 600 "$HOME/.kube/config-k3s"
export KUBECONFIG="$HOME/.kube/config-k3s"

echo "==> Waiting for the node"
until kubectl get nodes >/dev/null 2>&1; do sleep 2; done
kubectl wait --for=condition=Ready nodes --all --timeout=180s
kubectl get nodes -o wide

cat <<EOF

k3s is running.
  export KUBECONFIG=$HOME/.kube/config-k3s
Next:
  ./infra/k3s/deploy-local.sh       # build, import the image into k3s containerd, deploy
Uninstall:
  /usr/local/bin/k3s-uninstall.sh
EOF
