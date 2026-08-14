# 12 — CI/CD Pipeline (GitHub Actions)

Full rationale and evidence: ADR-0010 (platform/tool choices), ADR-0011 (artifact packaging).

## Pipeline Stages

```
PR / push to main or feature branch
   |
   v
1. Build            mvn clean install (all 10 modules)
   |
   v
2. Test             mvn verify — unit tests + Testcontainers integration
   |                tests (Postgres/Kafka/RabbitMQ per doc 10)
   v
3. SAST             CodeQL analysis (github/codeql-action), Java query
   |                suite, results in GitHub code-scanning tab
   v
4. SCA              OWASP Dependency-Check Maven plugin — fails build on
   |                high/critical CVEs in dependencies
   v
5. Package          mvn package -> release ZIP per service (jar + config
   |                template + start script) — ADR-0011. No artifact push.
   v
6. DAST             OWASP ZAP baseline scan against the packaged service
                     run locally in the CI runner. Advisory-only until
                     Phase 4 (auth) lands, then blocking on high-severity
                     findings once real endpoints exist to scan meaningfully.
```

No stage pushes anywhere — no container registry, no artifact repository, no deployment target. The ZIP is retained only as a GitHub Actions workflow-run artifact. Pushing artifacts is explicitly deferred to a future decision.

## Where This Lives

`.github/workflows/ci.yml` at the repo root, plus (once DAST becomes meaningful) a ZAP rules file under `.github/zap/`.

## Gating Behavior

| Stage | Blocking? |
|---|---|
| Build | Yes — pipeline stops on compile failure |
| Test | Yes — any red unit/integration test blocks the pipeline (per `doc/architecture/08-implementation-governance.md`'s DoD discipline) |
| SAST (CodeQL) | Advisory at first (findings surfaced in code-scanning tab); tightened to blocking-on-high-severity once the codebase has real logic to analyze (Phase 2 onward) |
| SCA (Dependency-Check) | Blocking on high/critical CVEs from the start — dependency versions are known at Phase 1 already, no reason to defer this check |
| Package | Yes — pipeline stops if packaging fails |
| DAST (ZAP) | Advisory until Phase 4 (real auth boundary exists to test meaningfully); blocking on high-severity findings from Phase 4 onward |

## Related

- ADR-0010, ADR-0011
- `doc/architecture/10-development-testing-deployment.md` for how this pipeline fits the broader SDLC
