# 09 — Architecture Change Management (TOGAF ADM Phase H)

## Principle

Architecture decisions recorded in this repo are living, not fixed at the moment of writing — but changes are made through the same evidence-based discipline that produced them, not by quietly editing history.

## Process for Changing a Decision

1. Identify the ADR being reconsidered.
2. Write a new ADR (next sequential number) with its own Context, Options Considered, Evidence, Decision, and Consequences sections.
3. Mark the old ADR's status as `Superseded by ADR-XXXX` — do not delete or silently rewrite it.
4. Update any architecture doc (`00`–`10`) and the migration-planning DoD that referenced the old decision.

This exact process was already exercised once during this engagement: the zero-trust mechanism moved from an initial Istio instinct, to a researched Linkerd+SPIRE revision, to the final plain-SPIRE decision in ADR-0002 — each step is visible in that ADR's Evidence and Options Considered sections rather than hidden.

## Triggers for Revisiting a Decision

- New evidence contradicts the original research (e.g., a future benchmark shows app-level SPIRE mTLS underperforming expectations at this project's actual scale).
- The project's scale changes materially (e.g., service count grows enough that a service mesh's L7 traffic-shaping features become genuinely needed — explicitly flagged as a possible future trigger in ADR-0002's Consequences).
- A dependency (e.g., `java-spiffe`) becomes unmaintained or is superseded by a better-supported alternative.

## Related

- `doc/adr/` — the full decision log
- `doc/architecture/08-implementation-governance.md` — how decisions are gated during active delivery
