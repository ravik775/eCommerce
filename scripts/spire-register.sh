#!/usr/bin/env bash
# Registers SPIRE workload entries for services running in the `ecom`
# namespace. Not a K8s Job (see doc/k8-security-test.md and
# doc/adr/ADR-0002-zero-trust-spire-app-level.md's live-verification
# section): the ghcr.io/spiffe/spire-server image is fully distroless —
# no shell, no coreutils, only the single static binary — so a Job
# running shell logic against it can never work at all, regardless of
# RBAC/socket-sharing fixes. This runs on the host instead, reusing the
# fact that Docker Desktop's K8s node shares the same filesystem/Docker
# daemon as plain `docker run` (confirmed live: a host-mounted volume at
# /run/spire/server-admin sees the exact same socket file spire-server's
# pod created via its own hostPath volume).
#
# Safe to re-run: `spire-server entry create` is idempotent for an
# identical SPIFFE ID + selector set (SPIRE returns the existing entry,
# not a duplicate).
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_IMAGE="spire-cli-local"
SOCKET_HOST_DIR="/run/spire/server-admin"
SOCKET_PATH="/run/spire/server-admin/api.sock"

log() { printf '[spire-register] %s\n' "$1"; }

build_cli_image_if_missing() {
  if docker image inspect "$CLI_IMAGE" >/dev/null 2>&1; then
    log "$CLI_IMAGE already built"
    return
  fi
  log "extracting spire-server CLI binary from the server image (docker cp fails on this large binary via a bind mount on Windows — building a small image instead is the reliable path, found live)"
  local build_dir
  build_dir="$(mktemp -d)"
  docker create --name spire-cli-extract-tmp ghcr.io/spiffe/spire-server:1.11.0 >/dev/null
  docker cp spire-cli-extract-tmp:/opt/spire/bin/spire-server "${build_dir}/spire-server"
  docker rm spire-cli-extract-tmp >/dev/null
  cat > "${build_dir}/Dockerfile" <<'EOF'
FROM busybox:1.37
COPY spire-server /usr/local/bin/spire-server
RUN chmod +x /usr/local/bin/spire-server
EOF
  docker build -q -t "$CLI_IMAGE" "$build_dir" >/dev/null
  rm -rf "$build_dir"
}

spire_cli() {
  MSYS_NO_PATHCONV=1 docker run --rm -v "${SOCKET_HOST_DIR}:${SOCKET_HOST_DIR}" \
    "$CLI_IMAGE" /usr/local/bin/spire-server "$@" -socketPath "$SOCKET_PATH"
}

main() {
  build_cli_image_if_missing

  log "looking up the attested agent's SPIFFE ID"
  local agent_id
  agent_id=$(spire_cli agent list | grep -o 'spiffe://ecommerce.local/spire/agent/k8s_psat/docker-desktop/[^ ]*' | head -1)
  if [ -z "$agent_id" ]; then
    log "no attested agent found — is spire-agent running and node-attested? (kubectl get pods -n spire)"
    exit 1
  fi
  log "using parentID: $agent_id"

  # "AlreadyExists" isn't a real failure (that's the whole point of this
  # script being safe to re-run, per the header comment) — but
  # `spire-server entry create` exits non-zero on it anyway, which with
  # `set -e` was silently aborting the rest of registration on every
  # second run. `|| true` on each call is what actually makes this
  # idempotent, not just documented as such.
  for svc in catalog-service api-gateway inventory-service notification-service order-service payment-service user-service; do
    log "registering $svc"
    spire_cli entry create \
      -parentID "$agent_id" \
      -spiffeID "spiffe://ecommerce.local/$svc" \
      -selector "k8s:ns:ecom" \
      -selector "k8s:sa:default" \
      -selector "k8s:pod-label:app:$svc" || true
  done

  log "current entries:"
  spire_cli entry show
}

main "$@"
