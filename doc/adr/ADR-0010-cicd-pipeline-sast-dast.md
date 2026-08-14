# ADR-0010: CI/CD pipeline — GitHub Actions with CodeQL (SAST), OWASP Dependency-Check (SCA), OWASP ZAP (DAST)

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Notes.md's technology table names "Jenkins / GitHub Actions" without deciding. Requirement (this engagement): the pipeline must include both static and dynamic security analysis, and for now must stop at producing a packaged artifact (zip/war) — no push to an artifact repository or container registry yet.

## Options Considered

| Concern | Options | Decision driver |
|---|---|---|
| CI/CD platform | Jenkins (self-hosted) vs. GitHub Actions | Repo is already hosted on GitHub (`origin` = `github.com/ravik775/eCommerce`); GitHub Actions needs zero additional infrastructure to run, Jenkins needs a server to install/patch/secure — directly serves "add infrastructure only when needed" |
| SAST | SonarQube (self-hosted server) vs. Semgrep vs. GitHub CodeQL | CodeQL is native to GitHub Actions — "runs automatically in Actions workflows with zero external tool configuration," free for this (public) repo, and its Java query suite is actively maintained (491 security queries across 166 CWE as of the 2.25.0 update). SonarQube needs a hosted server (infra we don't need yet); Semgrep is a reasonable lighter alternative but CodeQL's zero-infra, native-to-the-platform fit wins for this project's scale |
| SCA (dependency vulnerability scanning) | Snyk (needs account/token) vs. OWASP Dependency-Check | Dependency-Check is a Maven plugin, runs entirely inside the build (`mvn verify`), no external account needed, and is the OWASP-maintained reference tool for exactly this problem (Java-specific, this project's stack) |
| DAST | Burp Suite (commercial) vs. OWASP ZAP | ZAP is "an open-source web application security scanner maintained by the OWASP community that is widely used for DAST," has an official GitHub Action, and needs no license — fits a project explicitly avoiding infrastructure/cost it doesn't need |
| Artifact destination | Push to a registry/repo now vs. build-and-stop | Explicit instruction: defer pushing artifacts. Pipeline builds, tests, scans, and packages — publish/push is a deliberately separate, later step |

## Evidence

- CodeQL: "Native GitHub integration means CodeQL runs automatically in Actions workflows with zero external tool configuration... CodeQL 2.25.0... raises the Default suite to 491 security queries across 166 CWE" — actively maintained, not a stale tool. ([GitHub CodeQL code scanning docs](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql), [AppSecSanta — CodeQL review](https://appsecsanta.com/github-codeql))
- OWASP Dependency-Check: "a software composition analysis utility that detects publicly disclosed vulnerabilities in application dependencies... by correlating them with CPE identifiers and CVE entries... automatically updates via NVD data feeds," runs at the Maven `verify` phase like any other build step. ([OWASP Dependency-Check GitHub](https://github.com/dependency-check/DependencyCheck), [Baeldung — Dependency-Check](https://www.baeldung.com/java-maven-owasp-dependency-check))
- OWASP ZAP: "widely used for DAST... scans a running application for vulnerabilities," with an official GitHub Action supporting both full scans and PR-scoped differential scans; explicitly noted that "since it is a DAST scan it requires a running application" — meaning it can only run meaningfully once a service is actually up and serving requests, not on day one of an empty skeleton. ([Medium — Automating DAST with OWASP ZAP in GitHub Actions](https://medium.com/@yousaf.k.hamza/automating-security-testing-with-owasp-zap-in-github-actions-dast-for-devsecops-1c5e525d3905), [ZAP Baseline Scan Action](https://www.lunavi.com/blog/using-the-owasp-zap-baseline-scan-github-action))

## Decision

GitHub Actions pipeline (`.github/workflows/ci.yml`), triggered on PR and push to `main`/feature branches:

1. **Build** — `mvn clean install` (all 10 modules).
2. **Unit + integration tests** — `mvn verify` (Testcontainers-backed where applicable, per `doc/architecture/10-development-testing-deployment.md`).
3. **SAST** — CodeQL analysis (`github/codeql-action`), Java query suite, uploaded to GitHub's code-scanning tab.
4. **SCA** — OWASP Dependency-Check Maven plugin, fails the build on high/critical CVEs in dependencies.
5. **Package** — `mvn package`, output collected as a release **zip** per service (jar + config template + start script) per ADR-0011. WAR packaging remains available via a Maven profile if an external servlet container deployment is later required.
6. **DAST** — OWASP ZAP baseline scan action against the packaged service running locally in the CI runner (`java -jar`), once the service under test actually exposes real endpoints (i.e., meaningful from Phase 2 onward, not against the current skeleton). Treated as advisory (non-blocking) until Phase 4's auth is in place, then blocking on high-severity findings.
7. **Stop.** No push to a container registry, artifact repository, or deployment target — explicitly deferred per instruction. The zip is retained only as a GitHub Actions build artifact (workflow-run-scoped), not published anywhere durable.

## Consequences

- Positive: zero additional CI/CD infrastructure to operate (no Jenkins server, no SonarQube server); every security-relevant scan type (static code, dependency CVEs, dynamic/runtime) is represented; nothing is pushed anywhere until that's explicitly decided later.
- Negative / accepted trade-off: CodeQL's most complete offering (GHAS) is a paid feature on private repos — acceptable here since the repo is public and default CodeQL code scanning is free for public repos; revisit if the repo goes private. ZAP's DAST stage is low-value until real endpoints exist — explicitly marked advisory-only in early phases rather than pretending it provides coverage it can't yet.
- Follow-up required: decide the artifact-push destination (GitHub Packages, a container registry, Nexus/Artifactory) in a future ADR when that requirement becomes real — not before.

## Related

- ADR-0011 (artifact packaging: zip over WAR)
- Related architecture doc: `doc/architecture/12-ci-cd-pipeline.md`
