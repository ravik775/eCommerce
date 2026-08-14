#!/bin/sh
# ADR-0011: generic start script bundled into every service's release zip.
# Config comes from Spring Cloud Config Server (or Kubernetes ConfigMaps/
# Secrets from Phase 6 onward, ADR-0008) — not from a file baked into this
# zip. Override SPRING_CONFIG_IMPORT / SPRING_PROFILES_ACTIVE as needed.
set -eu
cd "$(dirname "$0")"
exec java -jar "${project.artifactId}.jar" "$@"
