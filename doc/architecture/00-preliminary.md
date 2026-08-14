# 00 — Preliminary (TOGAF ADM: Preliminary Phase)

## Purpose

Establishes the architecture principles and framework tailoring for this engagement before any target-state design is proposed. TOGAF 10's full ADM is tailored down here to match this project's actual scale (a ~10-module Spring Boot platform, not an enterprise-wide multi-domain estate) — every ADM phase is represented, but each artifact is sized to be useful, not to satisfy a template.

## Scope

In scope: the eCommerce platform's application, data, technology, and security architecture, from local development through Kubernetes production deployment. Out of scope: organizational/business-strategy architecture beyond what's needed to justify the technical decisions (this is a technical modernization of an existing skeleton codebase, not a greenfield enterprise transformation program).

## Architecture Principles

1. **Evidence over instinct.** Every non-trivial technology choice is backed by a written ADR with cited research, not adopted because it's the first well-known option (see `doc/adr/`).
2. **Add infrastructure only when needed.** No component (service mesh, extra broker, extra database server) is introduced before a phase's requirements actually demand it — see ADR-0002 for the clearest example of this principle overriding an initial default choice.
3. **Zero trust is two independent layers, not one.** Network-location controls (NetworkPolicy) and cryptographic identity controls (SPIFFE mTLS) are both required; neither substitutes for the other (ADR-0006).
4. **Reuse existing conventions before inventing new ones.** `common-lib` is this repo's established pattern for shared code; new cross-cutting concerns (e.g., SPIFFE mTLS) extend it rather than introducing a parallel mechanism.
5. **Definition of Done means verified, not written.** Every delivery-plan phase closes against an explicit, checked-off DoD list — passing tests and a manually observed behavior, not "the code compiles."
6. **Least privilege and defense in depth for anything security-relevant**, aligned to SOC2 expectations: audit trails for auth/money-movement events, no plaintext secrets in committed config, RBAC at both the platform (Keycloak roles) and infrastructure (K8s RBAC) layers.

## Framework Tailoring

| TOGAF ADM Phase | Artifact in this repo |
|---|---|
| Preliminary | This document |
| A — Architecture Vision | `01-architecture-vision.md` |
| B — Business Architecture | `02-business-architecture.md` |
| C — Information Systems Architecture (Data) | `03-data-architecture.md` |
| C — Information Systems Architecture (Application) | `04-application-architecture.md` |
| D — Technology Architecture | `05-technology-architecture.md` |
| E — Opportunities & Solutions | `06-opportunities-solutions.md` |
| F — Migration Planning | `07-migration-planning.md` (mirrors the delivery-plan phases) |
| G — Implementation Governance | `08-implementation-governance.md` |
| H — Architecture Change Management | `09-architecture-change-management.md` |
| (supplementary — not a numbered ADM phase, added for this project's needs) | `10-development-testing-deployment.md` — full SDLC: local dev, testing pyramid, CI/CD, deployment promotion |
| Requirements Management (center of the ADM cycle) | Tracked as the numbered requirements list in the original engagement request, referenced throughout every doc below and in each ADR's Context section |

## Related

- ADRs: all decisions referenced in these documents are recorded in `doc/adr/`, using `doc/adr/template.md`.
