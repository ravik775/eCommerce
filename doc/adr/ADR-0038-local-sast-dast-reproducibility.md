# ADR-0038: Local SAST/DAST reproducibility

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0010 correctly decided to run SAST (CodeQL), SCA (OWASP Dependency-Check), secret scanning (gitleaks), and DAST (OWASP ZAP baseline) in CI — and confirmed this session, all four genuinely run in `.github/workflows/ci.yml`, not just documented as intent. But none of them can be reproduced locally: a developer who gets a CI failure has no way to re-run the same check on their own machine to iterate on a fix, and has to push-and-wait for every attempt. That's a real shift-left gap — security feedback should be available before a push, not only after.

## Options Considered

| Option | Local reproducibility | New tooling? |
|---|---|---|
| Do nothing — CI remains the only place these run | None | None |
| Wrap the exact CI tools (gitleaks, Dependency-Check) in a local script; substitute CodeQL with Semgrep locally since CodeQL itself isn't practically runnable ad-hoc | High for 3 of 4 tools; CodeQL specifically stays CI-only | None — reuses the same Docker images/Maven plugin already pinned in CI |
| Install the full CodeQL CLI locally too, for 1:1 parity | Complete | Yes — CodeQL CLI + a local compiled database per run, a much heavier local dev dependency for marginal gain over Semgrep |

## Evidence

- gitleaks and OWASP Dependency-Check are invoked identically to CI (same Docker image tag / same Maven plugin coordinates and flags) — no divergence risk between what a developer sees locally and what CI enforces for those two.
- CodeQL requires either GitHub-hosted analysis or a locally-built CodeQL database via the CodeQL CLI — the latter is a heavyweight, slower local step (whole-codebase compilation + database build) that most engineers wouldn't run before every push anyway. Semgrep's OSS ruleset (`p/java`, `p/owasp-top-ten`) covers the same finding *categories* (injection, insecure deserialization, hardcoded secrets/crypto, common OWASP Top 10 patterns) in seconds via a single Docker run — a practical stand-in, explicitly not a claimed equivalent.
- ZAP baseline DAST is opt-in locally (`--dast` flag) rather than default, since it requires starting `config-server` and waiting for it to become healthy — meaningfully slower than the other three checks, matching why CI itself only runs it after `package`, not eagerly.

## Decision

Add `scripts/run-sast-dast-local.sh`: runs gitleaks + OWASP Dependency-Check + Semgrep by default (fast, same tools/config as CI where the tool itself supports local execution), with `--dast` as an opt-in flag for the slower ZAP baseline scan. CodeQL itself remains CI-only — Semgrep is the acknowledged, imperfect local substitute, not a replacement of CI's actual gate.

## Consequences

- Positive: a developer can now get SAST/SCA/secret-scan feedback in under a minute locally, before pushing, instead of only finding out via a CI failure several minutes later.
- Negative / accepted trade-off: Semgrep's ruleset differs from CodeQL's — a local-clean run is not a guarantee CI's CodeQL job will also pass; CI remains the authoritative gate for that specific check. The DAST option still requires Docker and a config-server boot, so it's meaningfully slower than the other three even when opted into.
- Follow-up required: if CodeQL local parity ever becomes worth the cost (e.g. a recurring pattern of CodeQL-only findings that Semgrep misses), install the CodeQL CLI and add a `--codeql` flag rather than replacing Semgrep — keep the fast default fast.

## Related

- Related: ADR-0010 (CI/CD SAST/DAST pipeline — this ADR is its local-reproducibility companion, not a replacement)
- Implementation: `scripts/run-sast-dast-local.sh`
