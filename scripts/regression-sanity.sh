#!/usr/bin/env bash
# Post-deploy regression sanity check for the live K8s deployment (ADR-0052
# and prior incidents this repo has hit live: session-max-age staleness,
# user-service role-sync staleness, gateway Retry/CircuitBreaker conflict,
# missing orderCircuit TimeLimiter, MDC traceId/Micrometer key collision).
# Each scenario is independent — one failure doesn't stop the rest, so a
# single run gives the full list of what's actually broken.
#
# Usage:
#   ./scripts/regression-sanity.sh
#
# Env vars (all optional, sane defaults for this dev cluster):
#   GATEWAY_URL   default http://localhost:8080
#                 (run: kubectl port-forward -n ecom svc/api-gateway 8080:8080)
#   KEYCLOAK_URL  default http://localhost:8090
#   NAMESPACE     default ecom
#   KEYCLOAK_GATEWAY_CLIENT_SECRET
#                 same var scripts/render-realm-config.sh reads from .env.
#                 Auth-dependent scenarios (5+) are skipped with WARN, not
#                 FAIL, if this is unset — lets pod/health-only checks run
#                 with zero config.
#
# Exit code: 0 if no FAILs (WARNs are fine), 1 if any scenario FAILed.
set -uo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
NAMESPACE="${NAMESPACE:-ecom}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi
: "${KEYCLOAK_GATEWAY_CLIENT_SECRET:=}"

TOKEN_CUSTOMER=""
TOKEN_ADMIN=""
CHECKOUT_ORDER_ID=""
CHECKOUT_TRACE_ID=""

FAILURES=()
WARNINGS=()
PASS_COUNT=0

log() { printf '[regression] %s\n' "$1"; }

run_check() {
  # run_check "<description>" check_fn
  # Deliberately NOT `detail="$("$fn")"` — that forks a subshell, which
  # would silently discard TOKEN_CUSTOMER/TOKEN_ADMIN/CHECKOUT_ORDER_ID
  # assignments that later scenarios depend on. Route stdout+stderr
  # through a temp file instead so the check function runs in *this*
  # shell and its global assignments actually stick.
  local desc="$1" fn="$2"
  local detail tmp
  tmp="$(mktemp)"
  "$fn" >"$tmp" 2>&1
  local rc=$?
  detail="$(cat "$tmp")"
  rm -f "$tmp"
  if [ "$rc" -eq 0 ]; then
    printf '[PASS] %s\n' "$desc"
    PASS_COUNT=$((PASS_COUNT + 1))
  elif [ "$rc" -eq 2 ]; then
    printf '[WARN] %s — %s\n' "$desc" "$detail"
    WARNINGS+=("$desc — $detail")
  else
    printf '[FAIL] %s — %s\n' "$desc" "$detail"
    FAILURES+=("$desc — $detail")
  fi
}

# No jq/python guaranteed on this dev machine (Git Bash on Windows) — flat
# grep/sed extraction is sufficient for the single-level fields this script
# needs (access_token, id, status), avoids adding a dependency.
json_str_field() {
  # json_str_field <json> <field-name> — extracts a "field":"value" string
  echo "$1" | grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
}
json_num_field() {
  # json_num_field <json> <field-name> — extracts a "field":123 numeric value
  echo "$1" | grep -o "\"$2\"[[:space:]]*:[[:space:]]*[0-9][0-9]*" | head -1 | sed -E 's/.*:[[:space:]]*([0-9]+)/\1/'
}

# ---------------------------------------------------------------------------
# 1. Pod health
# ---------------------------------------------------------------------------
check_pods_ready() {
  local not_ready
  not_ready="$(kubectl get pods -n "$NAMESPACE" --no-headers 2>&1 | awk '{split($2,a,"/"); if (a[1]!=a[2]) print $1" ("$2")"}')"
  if [ -n "$not_ready" ]; then
    echo "not Ready: $not_ready"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 2. Per-service actuator health, via a transient kubectl port-forward.
# `kubectl exec` was tried first (avoids juggling ports) but on this
# machine it fails consistently with a CRI shim TLS handshake error
# ("server gave HTTP response to HTTPS client") — a Docker Desktop
# containerd/CRI issue unrelated to the app, reproduced even against a
# single manual retry outside this script. Port-forward is the same
# mechanism already proven reliable for Grafana/gateway access.
# ---------------------------------------------------------------------------
check_actuator_health() {
  # check_actuator_health <label-selector> <mgmt-port>
  local selector="$1" port="$2"
  local pod
  pod="$(kubectl get pods -n "$NAMESPACE" -l "app=$selector" --no-headers -o custom-columns=":metadata.name" 2>&1 | head -1)"
  if [ -z "$pod" ]; then
    # fall back to name-prefix match since labels vary across manifests
    pod="$(kubectl get pods -n "$NAMESPACE" --no-headers 2>&1 | awk -v s="$selector" '$1 ~ "^"s"-" {print $1; exit}')"
  fi
  if [ -z "$pod" ]; then
    echo "no pod found for $selector"
    return 1
  fi
  local local_port=$((20000 + RANDOM % 10000))
  kubectl port-forward -n "$NAMESPACE" "pod/$pod" "${local_port}:${port}" >/dev/null 2>&1 &
  local pf_pid=$!
  local waited=0
  while [ "$waited" -lt 5 ] && ! (exec 3<>"/dev/tcp/127.0.0.1/${local_port}") 2>/dev/null; do
    sleep 0.5
    waited=$((waited + 1))
  done
  exec 3>&- 2>/dev/null || true
  local body
  body="$(curl -s -m 5 "http://127.0.0.1:${local_port}/actuator/health" 2>&1)"
  kill "$pf_pid" >/dev/null 2>&1
  wait "$pf_pid" 2>/dev/null
  if [[ "$body" != *'"status":"UP"'* ]]; then
    echo "pod=$pod port=$port response=$body"
    return 1
  fi
  return 0
}
check_health_user()         { check_actuator_health user-service 9081; }
check_health_order()        { check_actuator_health order-service 9082; }
check_health_catalog()      { check_actuator_health catalog-service 9083; }
check_health_inventory()    { check_actuator_health inventory-service 9084; }
check_health_payment()      { check_actuator_health payment-service 9085; }
check_health_notification() { check_actuator_health notification-service 9087; }

# ---------------------------------------------------------------------------
# 3. Keycloak reachable
# ---------------------------------------------------------------------------
check_keycloak_up() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$KEYCLOAK_URL/realms/ecom")"
  if [ "$code" != "200" ]; then
    echo "GET $KEYCLOAK_URL/realms/ecom -> HTTP $code"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 4. Auth: session login. The gateway is a session-cookie BFF
#    (.oauth2Login(...) + PKCE, SecurityConfig.java) — it is NOT an OAuth2
#    resource server, so a bearer access token in an Authorization header
#    is simply ignored and every route 302s to login. A first version of
#    this script used the resource-owner-password grant against Keycloak
#    directly and passed that as a Bearer token — confirmed live that this
#    gets a 302 from every gateway route. The only way to authenticate
#    against the gateway is to actually drive its real login flow (GET
#    /oauth2/authorization/keycloak -> Keycloak login form -> POST
#    credentials -> follow the redirect chain back to the gateway) and
#    keep the resulting session cookie, exactly like a browser. Verified
#    manually end-to-end before wiring into this script.
# ---------------------------------------------------------------------------
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
COOKIE_JAR_CUSTOMER="$TMP_DIR/cookies-customer.txt"
COOKIE_JAR_ADMIN="$TMP_DIR/cookies-admin.txt"

gateway_login() {
  # gateway_login <cookie-jar> <username> <password>
  local jar="$1" username="$2" password="$3"
  local login_html action
  login_html="$(curl -s -m 15 -c "$jar" -b "$jar" -L "$GATEWAY_URL/oauth2/authorization/keycloak")"
  action="$(echo "$login_html" | grep -o 'action="[^"]*"' | head -1 | sed -E 's/action="([^"]*)"/\1/' | sed 's/&amp;/\&/g')"
  if [ -z "$action" ]; then
    echo "could not find Keycloak login form action (gateway/Keycloak login flow may have changed)"
    return 1
  fi
  local final_url
  final_url="$(curl -s -m 15 -c "$jar" -b "$jar" -L "$action" \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$password" \
    --data-urlencode "credentialId=" \
    -o /dev/null -w '%{url_effective}')"
  if [[ "$final_url" != "$GATEWAY_URL"* ]]; then
    echo "login did not land back on the gateway (ended at $final_url) — check credentials/realm config"
    return 1
  fi
  return 0
}

check_token_issuance() {
  if [ -z "$KEYCLOAK_GATEWAY_CLIENT_SECRET" ]; then
    echo "KEYCLOAK_GATEWAY_CLIENT_SECRET not set (check .env) — skipping all auth-dependent scenarios"
    return 2
  fi
  if ! gateway_login "$COOKIE_JAR_CUSTOMER" customer1 customer1-pass; then
    echo "failed to establish gateway session for customer1"
    return 1
  fi
  if ! gateway_login "$COOKIE_JAR_ADMIN" admin1 admin1-pass; then
    echo "failed to establish gateway session for admin1"
    return 1
  fi
  TOKEN_CUSTOMER="$COOKIE_JAR_CUSTOMER"
  TOKEN_ADMIN="$COOKIE_JAR_ADMIN"
  return 0
}

# ---------------------------------------------------------------------------
# 5. Auth: role visibility (regression guard for the user-service role-
#    staleness bug — roles only synced on account create, not every login)
# ---------------------------------------------------------------------------
check_roles_customer() {
  [ -z "$TOKEN_CUSTOMER" ] && { echo "no customer token (see token-issuance check)"; return 2; }
  local resp
  resp="$(curl -s -m 10 "$GATEWAY_URL/user/me" -b "$TOKEN_CUSTOMER")"
  if [[ "$resp" != *'"CUSTOMER"'* ]]; then
    echo "GET /user/me for customer1 missing CUSTOMER role: $resp"
    return 1
  fi
  return 0
}

check_roles_admin() {
  [ -z "$TOKEN_ADMIN" ] && { echo "no admin token (see token-issuance check)"; return 2; }
  local resp
  resp="$(curl -s -m 10 "$GATEWAY_URL/user/me" -b "$TOKEN_ADMIN")"
  if [[ "$resp" != *'"CAN_TRACE"'* ]] || [[ "$resp" != *'"PLATFORM_ADMIN"'* ]]; then
    echo "GET /user/me for admin1 missing CAN_TRACE/PLATFORM_ADMIN: $resp"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 6. Catalog listing
# ---------------------------------------------------------------------------
check_catalog_search() {
  [ -z "$TOKEN_CUSTOMER" ] && { echo "no customer token (see token-issuance check)"; return 2; }
  local code body
  body="$(curl -s -m 10 -w '\n%{http_code}' "$GATEWAY_URL/catalog/products/search?query=a" -b "$TOKEN_CUSTOMER")"
  code="$(echo "$body" | tail -1)"
  body="$(echo "$body" | sed '$d')"
  if [ "$code" != "200" ]; then
    echo "GET /catalog/search -> HTTP $code, body=$body"
    return 1
  fi
  if [[ "$body" != *'"items"'* ]]; then
    echo "unexpected /catalog/products/search response shape: $body"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 7. Checkout (full choreography saga: order -> inventory -> payment ->
#    notification). Product id 2 ("Compact Lamp 101", LISTED, 37 in stock —
#    confirmed live) used as a known-good seeded product.
# ---------------------------------------------------------------------------
check_checkout_saga() {
  [ -z "$TOKEN_CUSTOMER" ] && { echo "no customer token (see token-issuance check)"; return 2; }
  local idem body code order_id status waited=0
  idem="regression-$(date +%s%N)-$$"
  body="$(curl -s -m 10 -w '\n%{http_code}' -X POST "$GATEWAY_URL/order" \
    -b "$TOKEN_CUSTOMER" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $idem" \
    -d '{"customerId":1,"items":[{"productId":2,"quantity":1,"unitPrice":6.33}]}')"
  code="$(echo "$body" | tail -1)"
  body="$(echo "$body" | sed '$d')"
  if [ "$code" != "201" ]; then
    echo "POST /order -> HTTP $code, body=$body"
    return 1
  fi
  order_id="$(json_num_field "$body" "id")"
  if [ -z "$order_id" ]; then
    echo "no order id in response: $body"
    return 1
  fi
  CHECKOUT_ORDER_ID="$order_id"

  while [ "$waited" -lt 30 ]; do
    body="$(curl -s -m 10 "$GATEWAY_URL/order/$order_id" -b "$TOKEN_CUSTOMER")"
    status="$(json_str_field "$body" "orderStatus")"
    case "$status" in
      PAYMENT_COMPLETED|PROCESSING|SHIPPED|DELIVERED)
        return 0
        ;;
      CANCELLED)
        echo "order $order_id ended CANCELLED — saga ran but declined: $body"
        return 1
        ;;
    esac
    sleep 3
    waited=$((waited + 3))
  done
  echo "order $order_id still '$status' after 30s — saga did not reach a terminal state"
  return 1
}

# ---------------------------------------------------------------------------
# 8. Trace propagation across the async saga (ADR-0052 / MDC-collision
#    regression guard, fixed in commit ae4c44c). Requires Loki reachable —
#    port-forward svc/loki -n ecom 3100:3100 if not already exposed.
# ---------------------------------------------------------------------------
check_trace_propagation() {
  [ -z "$CHECKOUT_ORDER_ID" ] && { echo "no order from checkout-saga check to trace"; return 2; }
  local loki_url="${LOKI_URL:-http://localhost:3100}"
  local start_ns end_ns query resp
  start_ns=$(( $(date +%s) * 1000000000 - 300000000000 ))
  end_ns=$(( $(date +%s) * 1000000000 + 60000000000 ))
  local missing=()
  for svc in order-service inventory-service payment-service notification-service; do
    query="{app=\"$svc\"} |= \"orderId=$CHECKOUT_ORDER_ID\" |= \"appTraceId=\""
    resp="$(curl -s -m 10 -G "$loki_url/loki/api/v1/query_range" \
      --data-urlencode "query=$query" \
      --data-urlencode "start=$start_ns" \
      --data-urlencode "end=$end_ns" \
      --data-urlencode "limit=5")"
    if [[ "$resp" != *'"result":['*'{'* ]]; then
      missing+=("$svc")
    elif [[ "$resp" == *'appTraceId=,'* ]] || [[ "$resp" == *'appTraceId=]'* ]]; then
      missing+=("$svc(blank-appTraceId)")
    fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "no non-blank appTraceId log lines in Loki for orderId=$CHECKOUT_ORDER_ID from: ${missing[*]} (is Loki reachable at $loki_url, and are services running the ae4c44c+ image?)"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 9. Gateway never trusts a client-supplied X-Trace-Id (ADR-0052 security
#    guard — gateway must always overwrite it, never honor the inbound one)
# ---------------------------------------------------------------------------
check_gateway_overwrites_trace_id() {
  local forged="attacker-supplied-value-$(date +%s)"
  local headers
  headers="$(curl -s -m 10 -D - -o /dev/null "$GATEWAY_URL/actuator/health" -H "X-Trace-Id: $forged")"
  local echoed
  echoed="$(echo "$headers" | grep -i '^x-trace-id:' | tr -d '\r' | awk '{print $2}')"
  if [ "$echoed" = "$forged" ]; then
    echo "gateway echoed back the client-supplied X-Trace-Id unchanged: $echoed"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Run everything
# ---------------------------------------------------------------------------
log "target: gateway=$GATEWAY_URL keycloak=$KEYCLOAK_URL namespace=$NAMESPACE"
echo

run_check "Pod health (all pods Ready)"                              check_pods_ready
run_check "user-service actuator health"                             check_health_user
run_check "order-service actuator health"                            check_health_order
run_check "catalog-service actuator health"                          check_health_catalog
run_check "inventory-service actuator health"                        check_health_inventory
run_check "payment-service actuator health"                          check_health_payment
run_check "notification-service actuator health"                     check_health_notification
run_check "Keycloak reachable"                                       check_keycloak_up
run_check "Token issuance (customer1, admin1)"                       check_token_issuance
run_check "Role visibility: customer1 has CUSTOMER"                  check_roles_customer
run_check "Role visibility: admin1 has CAN_TRACE + PLATFORM_ADMIN"   check_roles_admin
run_check "Catalog search"                                           check_catalog_search
run_check "Checkout saga completes (order->inventory->payment->notif)" check_checkout_saga
run_check "Trace propagation: appTraceId visible in Loki across saga" check_trace_propagation
run_check "Gateway overwrites client-supplied X-Trace-Id"            check_gateway_overwrites_trace_id

echo
echo "=== Summary ==="
echo "PASS: $PASS_COUNT   WARN: ${#WARNINGS[@]}   FAIL: ${#FAILURES[@]}"

if [ "${#WARNINGS[@]}" -gt 0 ]; then
  echo
  echo "Warnings (skipped, not failures):"
  for w in "${WARNINGS[@]}"; do
    printf '  - %s\n' "$w"
  done
fi

if [ "${#FAILURES[@]}" -gt 0 ]; then
  echo
  echo "Failed scenarios to work on:"
  for f in "${FAILURES[@]}"; do
    printf '  - %s\n' "$f"
  done
  exit 1
fi

echo
echo "All checks passed."
exit 0
