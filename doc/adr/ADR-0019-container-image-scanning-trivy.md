# ADR-0019: Container image scanning — Trivy in CI (extends ADR-0010)

**Status**: Accepted — implemented 2026-08-16
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

**2026-08-16 update**: the follow-up below ("wire in Trivy once Dockerfiles land") had gone stale — Dockerfiles existed for all 10 services but the CI stage was never actually added, found during an architecture review. Closed via a new `image-scan` job in `.github/workflows/ci.yml` (matrix over all 10 services' Dockerfiles), gated on HIGH/CRITICAL with `ignore-unfixed: true` to match ADR-0010's SCA policy. Pinned to `aquasecurity/trivy-action`'s full commit SHA rather than a version tag — the action itself suffered a real supply-chain compromise in March 2026 (credential-stealer injected into every tag 0.0.1–0.34.2), so a floating tag here specifically would have been actively dangerous, not just theoretically less safe.

## Context

ADR-0010's SAST (CodeQL) and SCA (OWASP Dependency-Check) cover source code and declared Maven dependencies — neither covers OS-package vulnerabilities baked into the built container image (base image CVEs, e.g. an outdated `eclipse-temurin` base). This gap only becomes real once Phase 5 introduces Dockerfiles, but the tooling decision belongs alongside the rest of the pipeline's security tooling.

## Options Considered

| Option | Fit |
|---|---|
| Trivy (open source, Aqua Security) | Free, no account needed, official GitHub Action, scans both OS packages and application dependencies in the built image — consistent with this project's pattern of free, zero-account-needed security tools (CodeQL, Dependency-Check, ZAP) |
| Snyk Container | Requires an account/API token; more features but a paid tier for meaningful usage — inconsistent with the zero-infrastructure-cost pattern established for every other CI security tool |

## Decision

Add a Trivy image scan stage to the CI pipeline (`.github/workflows/ci.yml`), scoped to run once Phase 5 produces Docker images — scanning each built image for OS and application-layer CVEs, blocking on high/critical findings, consistent with the SCA gating policy in ADR-0010.

## Consequences

- Positive: closes the one layer of the supply chain (base image OS packages) that SAST/SCA don't reach; zero account/cost, matches every other tool choice in ADR-0010.
- Negative / accepted trade-off: not actionable until Phase 5 (no images exist yet) — the pipeline stage is documented now but only wired in when Phase 5 lands.
- Follow-up required: add the Trivy stage to `.github/workflows/ci.yml` when Phase 5's Dockerfiles are implemented.

## Related

- ADR-0010 (CI/CD pipeline), `doc/architecture/12-ci-cd-pipeline.md`
