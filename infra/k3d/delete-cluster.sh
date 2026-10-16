#!/usr/bin/env bash
# Deletes the local k3d cluster and everything in it (including the PostgreSQL volume).
set -euo pipefail
CLUSTER="${CLUSTER:-catalog}"
k3d cluster delete "$CLUSTER"
