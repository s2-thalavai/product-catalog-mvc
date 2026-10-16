#!/usr/bin/env bash
# Mints an HS256 JWT for LOCAL / k3s use only (production validates tokens from the issuer, JWT_ISSUER_URI).
#
#   scripts/dev-token.sh                         # scope catalog.write, valid 1 hour
#   scripts/dev-token.sh --scope ""              # authenticated, no write scope -> writes get 403
#   scripts/dev-token.sh --ttl 60 --sub alice    # 60 seconds, subject "alice"
#
#   TOKEN=$(scripts/dev-token.sh)
#   curl -X POST localhost:8080/api/v1/products -H "Authorization: Bearer $TOKEN" ...
#
# Secret: $JWT_HMAC_SECRET, or the local default from application.yml. Must match the server's secret.
set -euo pipefail

SECRET="${JWT_HMAC_SECRET:-local-dev-only-secret-change-me-0123456789}"
SCOPE="catalog.write"
TTL=3600
SUB="dev-user"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scope) SCOPE="$2"; shift 2 ;;
    --ttl) TTL="$2"; shift 2 ;;
    --sub) SUB="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ ${#SECRET} -ge 32 ]] || { echo "JWT_HMAC_SECRET must be at least 32 characters (HS256)" >&2; exit 2; }
[[ "$TTL" =~ ^[0-9]+$ ]] || { echo "--ttl must be a number of seconds" >&2; exit 2; }
[[ "$SUB" =~ ^[A-Za-z0-9._@-]+$ ]] || { echo "--sub: letters, digits and . _ @ - only" >&2; exit 2; }
[[ "$SCOPE" =~ ^[A-Za-z0-9._:\ -]*$ ]] || { echo "--scope: space-separated scope names only" >&2; exit 2; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
HEADER='{"alg":"HS256","typ":"JWT"}'
PAYLOAD="{\"sub\":\"$SUB\",\"iat\":$NOW,\"exp\":$((NOW + TTL))"
[[ -n "$SCOPE" ]] && PAYLOAD="$PAYLOAD,\"scope\":\"$SCOPE\""
PAYLOAD="$PAYLOAD}"

UNSIGNED="$(printf '%s' "$HEADER" | b64url).$(printf '%s' "$PAYLOAD" | b64url)"
SIGNATURE="$(printf '%s' "$UNSIGNED" | openssl dgst -binary -sha256 -hmac "$SECRET" | b64url)"
printf '%s.%s\n' "$UNSIGNED" "$SIGNATURE"
