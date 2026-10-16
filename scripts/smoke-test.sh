#!/usr/bin/env bash
# End-to-end smoke test against a running deployment, using curl (and openssl for the dev token).
#
#   scripts/smoke-test.sh [BASE_URL] [--read-only] [--host HOSTNAME] [--token JWT]
#
#   scripts/smoke-test.sh http://localhost:8080                                 # local / compose
#   scripts/smoke-test.sh http://localhost:8081 --host catalog-mvc.localhost    # k3d ingress
#   scripts/smoke-test.sh https://catalog.example.com --read-only               # production
#
# Writes need a bearer token with scope catalog.write: --token, $SMOKE_TOKEN, or one minted with
# scripts/dev-token.sh from $JWT_HMAC_SECRET (local default). Read-only mode never writes data.
set -euo pipefail

BASE_URL="http://localhost:8080"
READ_ONLY=false
HOST_HEADER=()
TOKEN="${SMOKE_TOKEN:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --read-only) READ_ONLY=true; shift ;;
    --host) HOST_HEADER=(-H "Host: $2"); shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) BASE_URL="$1"; shift ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_URL="${BASE_URL%/}"
API="$ROOT_URL/api/v1/products"
BODY="$(mktemp)"; HEADERS="$(mktemp)"
trap 'rm -f "$BODY" "$HEADERS"' EXIT
FAILURES=0

pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; head -c 1500 "$BODY"; echo; FAILURES=$((FAILURES + 1)); }

# call METHOD URL ACCEPT [CONTENT_TYPE BODY] [extra curl args...] -> prints status; body in $BODY, headers in $HEADERS
call() {
  local method="$1" url="$2" accept="$3"; shift 3
  local data=()
  if [[ $# -ge 2 && "$1" != -* ]]; then data=(-H "Content-Type: $1" --data-binary "$2"); shift 2; fi
  curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' --max-time 15 -X "$method" \
    ${HOST_HEADER[@]+"${HOST_HEADER[@]}"} -H "Accept: $accept" -H "X-Correlation-Id: smoke-$$" \
    ${data[@]+"${data[@]}"} "$@" "$url" || true   # connection errors print 000 and are reported
}

expect() { # expect <description> <expected-status> <actual-status> [text-in-body]
  if [[ "$3" == "$2" ]] && { [[ -z "${4:-}" ]] || grep -q -- "$4" "$BODY"; }; then pass "$1"
  else fail "$1 (HTTP $3, expected $2${4:+ + '$4'})"; fi
}
header() { grep -i "^$1:" "$HEADERS" | head -n 1 | cut -d' ' -f2- | tr -d '\r'; }

echo "Smoke testing $API (read-only: $READ_ONLY)"

status=$(call GET "$ROOT_URL/actuator/health/readiness" application/json)
expect "readiness probe is UP" 200 "$status" '"UP"'

status=$(call GET "$API?size=5" application/json)
expect "list as JSON" 200 "$status" '"totalElements"'
[[ -n "$(header X-Total-Count)" ]] && pass "X-Total-Count header" || fail "X-Total-Count header missing"

status=$(call GET "$API?size=2&sort=price,desc" application/xml)
expect "list as XML" 200 "$status" '<productPage'
status=$(call GET "$API?size=2" text/html)
expect "list as HTML" 200 "$status" '<!DOCTYPE html>'
status=$(call GET "$API?size=2&format=text" application/json)
expect "list as text via ?format=text" 200 "$status" 'product(s)'

status=$(call GET "$API?minPrice=cheap" application/json)
expect "bad query parameter -> 400 problem" 400 "$status" 'VALIDATION_FAILED'
status=$(call GET "$API/987654321" application/json)
expect "unknown product -> 404 problem" 404 "$status" 'PRODUCT_NOT_FOUND'

NEW_ITEM='{"sku":"SMOKE-NOAUTH","name":"No auth","price":1.00,"stock":1}'
status=$(call POST "$API" application/json application/json "$NEW_ITEM")
expect "write without a token -> 401 problem" 401 "$status" 'UNAUTHORIZED'

if [[ "$READ_ONLY" == false ]]; then
  status=$(call GET "$ROOT_URL/v3/api-docs/v1" application/json)
  expect "OpenAPI document (group v1) is published" 200 "$status" '/api/v1/products'

  if [[ -z "$TOKEN" ]]; then
    TOKEN="$("$SCRIPT_DIR/dev-token.sh")" || { echo "could not mint a dev token (set JWT_HMAC_SECRET or --token)"; exit 1; }
  fi
  AUTH=(-H "Authorization: Bearer $TOKEN")

  if READER="$("$SCRIPT_DIR/dev-token.sh" --scope "" 2>/dev/null)"; then
    status=$(call POST "$API" application/json application/json "$NEW_ITEM" -H "Authorization: Bearer $READER")
    expect "write without catalog.write scope -> 403 problem" 403 "$status" 'FORBIDDEN'
  fi

  SKU="SMOKE-$(date +%s)-$$"
  status=$(call POST "$API" application/json application/json \
    "{\"sku\":\"$SKU\",\"name\":\"Smoke Test Item\",\"category\":\"Smoke\",\"price\":10.00,\"stock\":1}" "${AUTH[@]}")
  expect "create -> 201" 201 "$status" "$SKU"
  LOCATION="$(header Location)"
  ID="${LOCATION##*/}"

  if [[ "$ID" =~ ^[0-9]+$ ]]; then
    ITEM="$API/$ID"
    status=$(call GET "$ITEM" application/json)
    expect "read product $ID" 200 "$status" "$SKU"
    ETAG="$(header ETag)"

    status=$(call GET "$ITEM" application/json -H "If-None-Match: $ETAG")
    expect "conditional GET -> 304" 304 "$status"

    status=$(call PUT "$ITEM" application/json application/json \
      "{\"sku\":\"$SKU\",\"name\":\"Smoke Test Item v2\",\"category\":\"Smoke\",\"price\":12.50,\"stock\":2}" \
      "${AUTH[@]}" -H "If-Match: $ETAG")
    expect "replace (PUT) with current If-Match" 200 "$status" '"price":12.50'

    status=$(call PATCH "$ITEM" application/json application/merge-patch+json '{"stock":0}' "${AUTH[@]}" -H "If-Match: $ETAG")
    expect "old ETag after the update -> 412" 412 "$status" 'PRECONDITION_FAILED'
    status=$(call PATCH "$ITEM" application/json application/merge-patch+json '{"stock":0}' "${AUTH[@]}")
    expect "partial update (PATCH)" 200 "$status" '"stock":0'

    status=$(call POST "$API" application/json application/json \
      "{\"sku\":\"$SKU\",\"name\":\"Dup\",\"price\":1,\"stock\":1}" "${AUTH[@]}")
    expect "duplicate SKU -> 409" 409 "$status" 'DUPLICATE_SKU'

    status=$(call DELETE "$ITEM" application/json "${AUTH[@]}")
    expect "delete -> 204" 204 "$status"
    status=$(call GET "$ITEM" application/json)
    expect "deleted product -> 404" 404 "$status" 'PRODUCT_NOT_FOUND'
  else
    fail "could not read the new product id from Location: '$LOCATION'"
  fi
fi

if [[ "$FAILURES" -gt 0 ]]; then
  echo "Smoke test FAILED ($FAILURES check(s))"
  exit 1
fi
echo "Smoke test passed"
