# 08 — Implementation Governance (TOGAF ADM Phase G)

## Review Gates

Each phase in `07-migration-planning.md` is a gate: a phase is not started until the previous phase's Definition of Done is fully checked, and Phase 1 (implementation) does not start until this documentation set (Phase 0) is reviewed and confirmed by the user.

## Definition of Done Discipline

"Done" means observed/verified behavior, not code existing:
- A DoD item phrased as "verified," "observed," or "demoed" requires an actual run (test execution, manual curl, dashboard screenshot) — not an assumption from reading the code.
- A DoD item is never marked complete if tests are failing, implementation is partial, or unresolved errors exist for that phase's scope.
- If a phase's scope changes mid-implementation (e.g., a decision is reversed, as happened once already with the zero-trust mechanism — ADR-0002), the relevant ADR and migration-planning DoD are updated in the same change, not left to drift from what was actually built.

## Decision Governance

- No architecturally significant decision (new infrastructure component, new external dependency, change to a security boundary) proceeds without an ADR using `doc/adr/template.md`.
- ADRs are never silently edited to remove a reversed decision — they are marked `Superseded by ADR-XXXX`, preserving the trail of *why* the current decision was reached (see ADR-0002 for the worked example: it documents the rejected Linkerd+SPIRE direction rather than erasing it).

## Roles in Governance

| Role | Responsibility |
|---|---|
| Solution/Security Architect | Owns ADRs, architecture docs, and DoD sign-off per phase |
| Implementer (may be the same person/agent) | Executes phase work against the DoD checklist, flags when a DoD item cannot be honestly checked |

## Related

- `doc/architecture/09-architecture-change-management.md` for how future changes to this governance model itself are handled
