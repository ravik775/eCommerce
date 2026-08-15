#!/usr/bin/env bash
# ADR-0029: interim local dev orchestration, superseded by Phase 5's
# docker-compose.yml once it exists. Portable across Git Bash (Windows dev
# machine) and Ubuntu (Oracle Cloud) — plain POSIX-ish Bash, no make/tmux/
# Ruby dependency. Idempotent: safe to re-run against an already-running
# environment.
#
# Starts, in dependency order: infra containers (Postgres, Kafka, RabbitMQ,
# Keycloak, Redis) with health waits, then config-server + service-discovery
# (Eureka — Docker/local-dev only, ADR-0008), then the 7 application
# services as background java processes.
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/scripts/.dev-logs"
PID_DIR="${ROOT_DIR}/scripts/.dev-pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

log() { printf '[dev-up] %s\n' "$1"; }

container_running() {
  docker ps --format '{{.Names}}' | grep -qx "$1"
}

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -qx "$1"
}

ensure_container() {
  # ensure_container <name> <run-args...>
  local name="$1"; shift
  if container_running "$name"; then
    log "$name already running"
    return
  fi
  if container_exists "$name"; then
    log "starting existing container $name"
    docker start "$name" >/dev/null
    return
  fi
  log "creating container $name"
  docker run -d --name "$name" "$@" >/dev/null
}

wait_for_port() {
  # wait_for_port <host> <port> <label> [timeout_seconds]
  local host="$1" port="$2" label="$3" timeout="${4:-60}"
  local waited=0
  until (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; do
    exec 3>&- 2>/dev/null || true
    waited=$((waited + 1))
    if [ "$waited" -ge "$timeout" ]; then
      log "TIMEOUT waiting for $label on ${host}:${port}"
      return 1
    fi
    sleep 1
  done
  exec 3>&- 2>/dev/null || true
  log "$label is up (${host}:${port})"
}

wait_for_http() {
  # wait_for_http <url> <label> [timeout_seconds]
  local url="$1" label="$2" timeout="${3:-90}"
  local waited=0
  until curl -s -o /dev/null -m 2 "$url"; do
    waited=$((waited + 2))
    if [ "$waited" -ge "$timeout" ]; then
      log "TIMEOUT waiting for $label at $url"
      return 1
    fi
    sleep 2
  done
  log "$label is up ($url)"
}

start_java_service() {
  # start_java_service <name> <jar-path> <port>
  local name="$1" jar="$2" port="$3"
  if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
    exec 3>&- 2>/dev/null || true
    log "$name already listening on $port"
    return
  fi
  exec 3>&- 2>/dev/null || true
  log "starting $name"
  nohup java -jar "$jar" > "${LOG_DIR}/${name}.log" 2>&1 &
  echo "$!" > "${PID_DIR}/${name}.pid"
  disown "$!" 2>/dev/null || true
}

log "=== infra containers ==="
ensure_container ecommerce-postgres-dev \
  -p 5432:5432 \
  -e POSTGRES_USER=ecommerce_dev \
  -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-changeme-postgres-dev}" \
  -e POSTGRES_DB=ecommerce \
  postgres:16-alpine

ensure_container ecommerce-kafka-dev \
  -p 9092:9092 \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@127.0.0.1:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  apache/kafka:3.9.0

ensure_container ecommerce-rabbitmq-dev \
  -p 5673:5672 -p 15673:15672 \
  -e RABBITMQ_DEFAULT_USER=ecommerce_dev \
  -e RABBITMQ_DEFAULT_PASS="${RABBITMQ_PASSWORD:-changeme-rabbitmq-dev}" \
  rabbitmq:3.13-management-alpine

# Renders keycloak/ecom-realm.json (gitignored) from its committed
# .template before Keycloak reads it — see render-realm-config.sh for
# why this runs host-side rather than inside the Keycloak container.
"${ROOT_DIR}/scripts/render-realm-config.sh"

ensure_container ecommerce-keycloak-dev \
  -p 8090:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}" \
  -v "${ROOT_DIR}/keycloak:/opt/keycloak/data/import" \
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm

ensure_container ecommerce-redis-dev \
  -p 6379:6379 \
  redis:7-alpine

wait_for_port 127.0.0.1 5432 "Postgres"
wait_for_port 127.0.0.1 9092 "Kafka"
wait_for_port 127.0.0.1 5673 "RabbitMQ"
wait_for_port 127.0.0.1 6379 "Redis"
wait_for_http "http://localhost:8090/realms/master" "Keycloak" 120

log "=== config-server + service-discovery ==="
start_java_service config-server "${ROOT_DIR}/config-server/target/config-server-0.0.1-SNAPSHOT.jar" 8888
wait_for_http "http://localhost:8888/actuator/health" "config-server" 90
start_java_service service-discovery "${ROOT_DIR}/service-discovery/target/service-discovery-0.0.1-SNAPSHOT.jar" 8086
wait_for_http "http://localhost:8086/actuator/health" "service-discovery (Eureka)" 90

log "=== application services ==="
start_java_service user-service "${ROOT_DIR}/user-service/target/user-service-0.0.1-SNAPSHOT.jar" 8081
start_java_service order-service "${ROOT_DIR}/order-service/target/order-service-1.0-SNAPSHOT.jar" 8082
start_java_service catalog-service "${ROOT_DIR}/catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar" 8083
start_java_service inventory-service "${ROOT_DIR}/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar" 8084
start_java_service payment-service "${ROOT_DIR}/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar" 8085
start_java_service notification-service "${ROOT_DIR}/notification-service/target/notification-service-0.0.1-SNAPSHOT.jar" 8087

log "waiting for application services to report healthy..."
wait_for_http "http://localhost:8083/actuator/health" "catalog-service" 120 || true
wait_for_http "http://localhost:8082/actuator/health" "order-service" 120 || true

log "=== api-gateway (started last: relays to the above) ==="
start_java_service api-gateway "${ROOT_DIR}/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar" 8080
wait_for_http "http://localhost:8080/actuator/health" "api-gateway" 90

log "environment up. Logs: ${LOG_DIR}/*.log — PIDs: ${PID_DIR}/*.pid"
