#!/usr/bin/env bash
# ADR-0038: local SAST/DAST reproducibility. Mirrors the checks
# .github/workflows/ci.yml runs, so a finding can be reproduced and
# iterated on without pushing a commit and waiting on CI. Not a
# replacement for CI — CodeQL specifically still only runs there (see
# ADR-0038's Decision) — but everything else here is the same tool,
# same config, same flags as CI uses.
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_SECRET_SCAN=1
RUN_SCA=1
RUN_SEMGREP=1
RUN_DAST=0   # opt-in: starts config-server and a Docker ZAP container

usage() {
  cat <<'EOF'
Usage: scripts/run-sast-dast-local.sh [--dast] [--skip-secret-scan] [--skip-sca] [--skip-semgrep]

  --dast              Also run the OWASP ZAP baseline scan against config-server
                       (starts a local Spring Boot process + Docker container; slower).
  --skip-secret-scan  Skip gitleaks.
  --skip-sca          Skip OWASP Dependency-Check.
  --skip-semgrep      Skip Semgrep (the local SAST stand-in — see ADR-0038).
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dast) RUN_DAST=1 ;;
    --skip-secret-scan) RUN_SECRET_SCAN=0 ;;
    --skip-sca) RUN_SCA=0 ;;
    --skip-semgrep) RUN_SEMGREP=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage; exit 1 ;;
  esac
done

if [ "$RUN_SECRET_SCAN" = "1" ]; then
  # MSYS_NO_PATHCONV: on Git Bash (Windows), a leading "/repo" in the
  # container path gets auto-translated into a Windows path before
  # Docker ever sees it (e.g. "C:/Program Files/Git/repo") — this
  # disables that translation so the in-container path stays literal.
  # No effect on real POSIX shells.
  echo "==> Secret scan (gitleaks) — same image/config/flags as ci.yml"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:latest \
    detect --source /repo --config /repo/.gitleaks.toml --no-git --redact -v
fi

if [ "$RUN_SEMGREP" = "1" ]; then
  # CodeQL itself (ci.yml's actual SAST tool) needs the GitHub-hosted
  # CodeQL CLI + a compiled database and isn't practical to run
  # ad-hoc locally the same way CI does it. Semgrep's OSS ruleset
  # (`p/java`, `p/owasp-top-ten`) is the practical local stand-in:
  # same category of finding (injection, insecure deserialization,
  # hardcoded crypto, etc.), seconds instead of minutes, no DB build
  # step. Not a 1:1 replacement — CI's CodeQL run is still the
  # authoritative SAST gate.
  echo "==> SAST (Semgrep — local stand-in for CI's CodeQL; see ADR-0038)"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/src" returntocorp/semgrep:latest \
    semgrep scan --config p/java --config p/owasp-top-ten /src
fi

if [ "$RUN_SCA" = "1" ]; then
  echo "==> SCA (OWASP Dependency-Check) — identical invocation to ci.yml"
  ./mvnw -B org.owasp:dependency-check-maven:12.1.0:aggregate \
    -DfailBuildOnCVSS=8 -Dformats=HTML
  echo "Report: target/dependency-check-report.html"
fi

if [ "$RUN_DAST" = "1" ]; then
  echo "==> DAST (OWASP ZAP baseline) — same target as ci.yml (config-server)"
  ./mvnw -q -pl config-server spring-boot:run &
  CONFIG_SERVER_PID=$!
  trap 'kill "$CONFIG_SERVER_PID" 2>/dev/null || true' EXIT

  for i in $(seq 1 30); do
    curl -sf http://localhost:8888/actuator/health && break
    sleep 2
  done

  MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/.github/zap:/zap/wrk:rw" --network host \
    zaproxy/zap-stable zap-baseline.py \
    -t http://localhost:8888 -r zap-report.html -a \
    -c /zap/wrk/rules.tsv || true
  echo "Report: .github/zap/zap-report.html"
fi

echo "==> Done."
