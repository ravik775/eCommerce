# ADR-0014: Secrets management — Kubernetes-native Secrets + etcd encryption at rest

**Status**: Accepted
**Date**: 2026-08-13
**Deciders**: Solution/Security Architect

## Context

Phase 7 (`doc/architecture/07-migration-planning.md`) already commits to moving secrets out of plaintext config into Kubernetes Secrets. The open question was whether that's sufficient for a 10/10 design, or whether a dedicated secrets manager (HashiCorp Vault, a cloud Secrets Manager) is warranted.

## Options Considered

| Option | New infrastructure? | Capability |
|---|---|---|
| Kubernetes Secrets + etcd encryption at rest + RBAC | None — etcd encryption is a cluster config flag, not a new service; RBAC is native | Secrets encrypted at rest, access restricted by K8s RBAC; manual rotation |
| HashiCorp Vault (or cloud Secrets Manager) | Yes — a new stateful component requiring HA/unseal management and per-service integration | Dynamic secrets, automatic rotation, fine-grained per-access audit trail |

## Decision

Kubernetes-native Secrets, with the cluster's etcd configured for encryption at rest (`EncryptionConfiguration` with a KMS or local provider) and RBAC restricting which ServiceAccounts can read which Secrets — matching the least-privilege principle already established for NetworkPolicy (ADR-0006) and SPIFFE identity (ADR-0002). No external secrets manager is introduced.

## Consequences

- Positive: zero new infrastructure component; still closes the real gap this project already has (plaintext dev credentials in `config-repo/*.yml`, flagged in the architect review) once Phase 7 lands.
- Negative / accepted trade-off: rotation is manual, not automatic; secret access is audited only at the K8s API-server audit-log level, not with Vault's per-secret access ledger. Acceptable at this project's scale.
- Follow-up required: confirm the cluster's etcd encryption provider as part of Phase 6/7 setup; document RBAC bindings per service's Secret access alongside the NetworkPolicy allow-list.

## Related

- `doc/architecture/07-migration-planning.md`, Phase 7
- ADR-0004 (Postgres credentials — this is what Phase 7 secures)
