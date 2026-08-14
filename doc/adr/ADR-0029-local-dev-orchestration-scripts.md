# ADR-0029: Local dev orchestration — plain shell scripts, not Make/Overmind/Compose-early

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

No `scripts/` folder exists in this repo. `packaging/start.sh` (ADR-0011) is a per-service launcher bundled into release ZIPs — it says nothing about which Docker containers must exist first or in what order the 7 Java services should start. Every local dev session this engagement (including live Phase 4 verification) has bootstrapped the environment via ad-hoc Bash commands invented fresh each time: `docker run` for Postgres/Kafka/RabbitMQ/Keycloak/Redis, then `java -jar` for each service. This is not reproducible — it lives only in conversation history, not in the repo.

Requirement: something simple, low-complexity, that makes bringing the environment up after Phase 4 consistent — without prematurely committing to full containerization (Phase 5, not yet built: no Dockerfiles exist for any of the 9 services yet).

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| Plain Bash scripts (`scripts/dev-up.sh` / `dev-down.sh`) | Zero new tool dependency — reuses exactly what's already on this machine (Git Bash, Docker CLI, `java`); matches the existing `packaging/start.sh` convention (ADR-0011) | No per-process log multiplexing or individual-service restart UX |
| Makefile wrapping the same commands | Memorable `make dev-up` entrypoint; industry-common pairing with Docker | `make` is not bundled with Git for Windows by default — adds an install step this environment doesn't already satisfy |
| Procfile + Overmind/Foreman/Hivemind | Purpose-built process manager; same Procfile works locally and (for some tools) in deploy | Overmind needs `tmux` (not native on Windows, needs WSL); Foreman needs Ruby; all three are a new external binary for a problem Bash already solves at this scale (7 processes, one dev machine) |
| Pull Docker Compose forward from Phase 5 | The industry-standard answer for exactly this problem — "the obvious choice... good balance between simplicity and capability" for multi-service local dev ([DEV Community — Docker + Make](https://dev.to/tacoda/why-i-prefer-docker-make-3a8n)) | Requires writing and validating 9 Dockerfiles first — real scope, not a quick fix; that work is Phase 5 itself, not a shortcut to it |

## Decision

**Plain Bash scripts**, `scripts/dev-up.sh` and `scripts/dev-down.sh`, explicitly scoped as an **interim measure superseded by Phase 5's `docker-compose.yml`** once that exists — not a parallel long-term solution. `dev-up.sh` is idempotent (checks each container/port before acting) and starts things in dependency order (infra containers with health waits, then config-server/service-discovery, then the 7 app services). Updated after every phase that changes what needs to run.

## Live verification (2026-08-14)

Ran the clean-state test this ADR's Consequences section implicitly promised but hadn't yet exercised: `dev-down.sh --full`, then removed every stopped container (`docker rm`), confirmed zero containers and zero Java processes existed, then ran `dev-up.sh` from nothing. It brought up all 9 services with no manual steps — **except** notification-service, which failed every RabbitMQ connection with `ACCESS_REFUSED`. Root cause: the RabbitMQ container `dev-up.sh` creates has no `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS` env vars, so a genuinely fresh container only provisions the default `guest` account — which RabbitMQ restricts to true-localhost connections by policy, rejecting every service's configured `ecommerce_dev` login. This bug was invisible in every session before this one because the container had been created once, ad-hoc, before this script existed, and its data volume (with `ecommerce_dev` already provisioned) was never removed — `ensure_container`'s reuse-if-exists check kept silently succeeding against that stale, undocumented state. Fixed by adding the credentials as explicit container env vars; re-verified clean (zero `ACCESS_REFUSED`, RabbitMQ connection established, notification-service healthy).

This is exactly the class of bug a "works because I never actually tested from zero" script accumulates, and the reason this ADR's own decision (interim scripts, not skip straight to Compose) still needed a real clean-state pass before Phase 5 rather than assuming Phase 4's spot-checks were sufficient.

## Consequences

- Positive: local environment bring-up is now a single committed command instead of tribal knowledge re-derived each session (this gap was found the hard way — mid Phase-4-verification, this session's own ad-hoc startup hit a transient Keycloak-discovery timeout during the API gateway's boot that a documented, health-checked startup order would have made visibly correct or incorrect, instead of a one-off retry).
- Negative / accepted trade-off: no per-process log multiplexing/individual restart (Overmind's strength) — acceptable at 7 processes on one dev machine; revisit only if that friction becomes real.
- Follow-up required: retire `scripts/dev-up.sh`'s direct-`java`-process model once Phase 5's `docker-compose.yml` exists; keep `dev-down.sh` cleanup semantics but point at `docker compose down`.

## Related

- Phase 4c (interim local dev orchestration) in `doc/architecture/07-migration-planning.md` and the master plan file.
- ADR-0011 (release ZIP `packaging/start.sh` — the per-service launcher this composes, not replaces).
- Phase 5 (Docker Compose) — the eventual superseding solution.
