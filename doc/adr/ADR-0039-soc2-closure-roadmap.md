# ADR-0039: SOC 2 closure roadmap — how the ADR-0037 gaps actually get closed

**Status**: Accepted
**Date**: 2026-08-16
**Deciders**: Solution/Security Architect

## Context

ADR-0037 mapped current controls against SOC 2's Trust Services Criteria and named real gaps (no edge TLS, no RTO/RPO, no data-subject-rights process, no data classification, DAST/Trivy gaps — some of the latter now closed, see ADR-0019/0038). That mapping answers "where do we stand"; it doesn't answer "what do we actually do to close this, and does it cost anything." This ADR is that closure plan.

## Options Considered

Two independent axes: **audit tooling** (how evidence gets collected) and **audit target** (Type I vs Type II, and whether a report is pursued at all).

### Audit tooling

| Option | Fit for this system | Cost |
|---|---|---|
| No platform — manual evidence collection (screenshots, exported configs, this ADR series itself as narrative evidence) | Fine for a pre-audit internal baseline; what this project already has | $0 |
| Vanta / Drata / Secureframe / Sprinto (compliance automation platforms) | Automate continuous evidence collection once there's something to continuously monitor (real cloud accounts, real user base) | Platform licenses run **$7,500–$30,000/year** for mid-market tiers; **all-in cost of a first SOC 2 Type II audit including the actual auditor (QSA-style) fee is $30,000–$120,000** — Sprinto is positioned as the more affordable entry point for startups specifically |

### Audit target

| Option | What it proves | Fit |
|---|---|---|
| No formal audit — internal control mapping only (current state, via ADR-0037) | Nothing to a third party; useful only as this team's own honest baseline | Correct for a 20-active-user internal tool with no external customer commitments |
| SOC 2 Type I | Controls are *designed* correctly as of a point in time | The minimum a customer contract might ask for; still real audit cost |
| SOC 2 Type II | Controls *operated effectively* over an observation window (typically 3–12 months) | What most enterprise customers actually require; meaningfully more evidence burden (continuous, not point-in-time) |

## Evidence

- Platform pricing and audit-cost figures above are from 2026 market research (see Sources) — Drata and Secureframe both list from $7,500/year on AWS Marketplace; total realistic first-Type-II-audit cost (platform + QSA auditor fees) is commonly cited at $30K–$120K depending on scope and auditor.
- This system has **no external customer commitments today** (confirmed: 100 internal users, no SLA, no customer contract referenced anywhere in the requirements or ADRs) — the trigger condition for actually spending on a platform/audit (per ADR-0037's own framing: "the moment an external commitment appears") has not occurred.

## Decision

**Do not purchase a compliance platform or pursue a formal SOC 2 report now.** Continue closing the *substance* of the gaps ADR-0037 named (edge TLS, backup/RTO-RPO — already addressed by ADR-0034, data classification — ADR-0040, alerting — this session's Alertmanager work) because they're good practice regardless of audit status and cost nothing but engineering time already being spent. Treat ADR-0037 + this roadmap as the artifact that gets handed to a compliance platform's onboarding flow *if and when* an external commitment (a customer contract, an investor/partner requirement) makes a real audit worth the $30K+ cost — at that point, Sprinto or Secureframe (cited as the more startup-friendly, lower-friction entry points in the research) are the recommended starting points over Vanta/Drata, given this system's small scale.

## Consequences

- Positive: zero spend now, on something that has no current business trigger; the actual security/privacy substance improves anyway via the other ADRs in this series, so the gap between "informally good practice" and "auditable SOC 2 evidence" narrows even without paying for the audit apparatus.
- Negative / accepted trade-off: if an external commitment appears suddenly (e.g. a customer signs a contract requiring SOC 2 Type II within 90 days), there is no pre-existing continuous-evidence trail — Type II specifically requires an observation window, so onboarding a platform late doesn't shortcut that; the earliest a Type II report could realistically be produced is bounded by when continuous monitoring starts, not by how fast a platform is purchased.
- Follow-up required: revisit this decision the moment any external SOC 2 commitment is discussed — at that point, start the observation window immediately (even before finishing platform selection) since Type II's clock is the actual constraint, not tooling procurement speed.

## Related

- Related: ADR-0037 (the control mapping this roadmap closes), ADR-0034 (backup/RTO-RPO — one of the named gaps), ADR-0040 (data classification/retention — another named gap)

Sources:
- [11 best SOC 2 compliance software (2026)](https://beaglesecurity.com/blog/article/best-soc2-compliance-software.html)
- [SOC 2 Compliance Software: 10 Platforms Ranked (2026 Guide)](https://www.strac.io/blog/soc-2-compliance-software)
- [Secureframe vs Vanta vs Drata: 2026](https://sprinto.com/blog/secureframe-vs-vanta-vs-drata/)
- [Vanta vs Drata vs Secureframe vs Sprinto 2026](https://cybersecify.com/blog/vanta-vs-drata-vs-manual-soc2/)
