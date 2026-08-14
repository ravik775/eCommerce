#!/usr/bin/env bash
# ADR-0029: tears down what dev-up.sh started. Portable (Git Bash / Ubuntu).
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="${ROOT_DIR}/scripts/.dev-pids"

log() { printf '[dev-down] %s\n' "$1"; }

log "=== stopping application services ==="
for svc in api-gateway notification-service payment-service inventory-service catalog-service order-service user-service service-discovery config-server; do
  pid_file="${PID_DIR}/${svc}.pid"
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file")"
    if kill "$pid" 2>/dev/null; then
      log "stopped $svc (pid $pid)"
    else
      log "$svc (pid $pid) was not running"
    fi
    rm -f "$pid_file"
  else
    log "$svc: no pid file, skipping"
  fi
done

if [ "${1:-}" = "--full" ]; then
  log "=== stopping infra containers (--full) ==="
  for c in ecommerce-redis-dev ecommerce-keycloak-dev ecommerce-rabbitmq-dev ecommerce-kafka-dev ecommerce-postgres-dev; do
    if docker ps --format '{{.Names}}' | grep -qx "$c"; then
      docker stop "$c" >/dev/null
      log "stopped container $c"
    fi
  done
else
  log "infra containers left running (pass --full to stop them too)"
fi

log "done"
