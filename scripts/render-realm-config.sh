#!/usr/bin/env bash
# Renders keycloak/ecom-realm.json (gitignored, the file Keycloak
# actually imports) from keycloak/ecom-realm.json.template (committed,
# holds ${VAR} placeholders) via envsubst, reading real values from
# this repo's .env (see .env.example).
#
# Why host-side, not a container entrypoint wrapper: envsubst is not
# available inside the Keycloak image itself (checked directly —
# quay.io/keycloak/keycloak:26.0 has no gettext package), but it is
# available on any normal dev machine and on GitHub Actions'
# ubuntu-latest runners (gettext ships preinstalled there) — so the
# substitution happens before the container ever starts, not inside it.
#
# Called by docker-compose.yml (via a one-shot init step, see its
# keycloak service) and scripts/dev-up.sh, both before Keycloak starts.
# Safe to re-run any time — always regenerates the output file fresh
# from the template.
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT_DIR/keycloak/ecom-realm.json.template"
OUTPUT="$ROOT_DIR/keycloak/ecom-realm.json"

if ! command -v envsubst >/dev/null 2>&1; then
  echo "[render-realm-config] envsubst not found — install gettext (apt: gettext-base, brew: gettext, Windows: available via Git Bash/MSYS2)." >&2
  exit 1
fi

# Load .env if present, so KEYCLOAK_GATEWAY_CLIENT_SECRET is available
# to envsubst without requiring it to already be exported in the shell.
if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi

: "${KEYCLOAK_GATEWAY_CLIENT_SECRET:=gateway-dev-secret-CHANGE-IN-REAL-DEPLOYMENT}"
export KEYCLOAK_GATEWAY_CLIENT_SECRET

envsubst '${KEYCLOAK_GATEWAY_CLIENT_SECRET}' < "$TEMPLATE" > "$OUTPUT"
echo "[render-realm-config] Wrote $OUTPUT from $TEMPLATE"
