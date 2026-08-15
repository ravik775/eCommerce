# 12 — Secrets Management

## Decision

**Kubernetes (`ecom` namespace)**: [Bitnami Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets), chosen over an External Secrets Operator / real vault (AWS Secrets Manager, HashiCorp Vault, GCP Secret Manager) for this project's scale — a single cluster, no multi-cloud vault already in place. Sealed Secrets needs no external service: a `SealedSecret` custom resource, safe to commit to git, is encrypted with the cluster's own controller-held public key and can only be decrypted back into a real `Secret` by that same controller's private key.

**Docker Compose / `scripts/dev-up.sh`**: no equivalent controller-backed mechanism exists for plain Compose. Uses `.env`-file substitution instead (`${VAR:-obviously-fake-default}`) — `.env` is gitignored, `.env.example` is the checked-in template.

## What changed

- `k8s/base/secrets.yaml`: every credential is now a `SealedSecret` (`bitnami.com/v1alpha1`), encrypted — safe to commit, matches what's actually in git today. Previously this file held plaintext values directly (`POSTGRES_PASSWORD`, the Keycloak/Grafana admin passwords), a real, live-confirmed gap closed by this change.
- `docker-compose.yml`, `scripts/dev-up.sh`, `config-server/src/main/resources/config-repo/*.yml`: the same previously-hardcoded Postgres password (duplicated across 9 files) replaced with env-var placeholders and non-production `changeme-*` defaults.
- `.env.example` (checked in) documents the Compose-side variables; `.env` (gitignored) holds the real local values.

## Known residual gap: `keycloak/ecom-realm.json`

A second plaintext secret was found during a broader scan (not just the Postgres password): the gateway's Keycloak OAuth2 client secret (`gateway-dev-secret-CHANGE-IN-REAL-DEPLOYMENT`), duplicated in `config-server/config-repo/api-gateway.yml` (fixed — templated the same way as Postgres) and `keycloak/ecom-realm.json` (**not fixed** — deliberately).

`ecom-realm.json` is Keycloak's own realm-import bootstrap file: plain JSON, read directly by the Keycloak container on first startup, with no environment-variable templating support built in. The already-running Keycloak instance (shared by both the Compose and K8s paths — see Phase 6a's local-dev deviation note) imported this exact value the first time it started; changing the file now without also re-importing would just create a silent mismatch between what's committed and what's actually configured, breaking OAuth2 login for both environments rather than fixing anything.

Given that risk, this pass left `ecom-realm.json`'s value as-is and instead:
- Templated `config-server/config-repo/api-gateway.yml`'s copy (the one path that *is* safely changeable — K8s never reads it, going straight to `k8s/base/secrets.yaml`'s already-sealed copy instead).
- Set `docker-compose.yml`'s `config-server` default to the *real* current value (not a fake placeholder, unlike every other credential here) so Compose keeps working without requiring a `.env` override.
- Documented the value in `.env.example` with an explicit warning not to change it without also updating and re-importing `ecom-realm.json`.

**The actual industry-standard fix**, not done here: an `envsubst`-based (or Keycloak's own `KC_SPI_*` env-var override mechanism) preprocessing step in the Keycloak container's entrypoint, templating `ecom-realm.json` before import the same way `config-server` already templates its own YAML — turning this from "can't be templated" into "isn't templated yet." Left as a flagged follow-up rather than risking breaking live authentication to half-solve it in this pass.

## Scope: strict

Sealed Secrets' encryption is bound to a **scope** — how loosely a sealed value can be moved and still decrypt:

| Scope | Binding | Chosen here? |
|---|---|---|
| `strict` (default) | Exact namespace **and** name | ✅ Yes |
| `namespace-wide` | Namespace only — can be renamed within it | No |
| `cluster-wide` | Neither — decrypts anywhere | No |

`strict` was chosen because this project has a single namespace (`ecom`) and no legitimate reason for a sealed value to be copy-pasted to a different name or namespace and still work — that's the attack this scope exists to prevent, not an inconvenience to route around.

## Access control

Two distinct questions, deliberately kept separate:

**1. Who can decrypt a `SealedSecret` into a real `Secret`?**
Only the `sealed-secrets-controller` — it alone holds the private key. This isn't an RBAC policy choice, it's inherent to the architecture: no human or service account "decrypts" anything directly, ever.

**2. Who can create/update `SealedSecret` objects, and who can read the resulting decrypted `Secret` objects?**
This *is* an RBAC policy choice, and as of this pass it's deliberately **not yet formalized** — the namespace currently has no `Role`/`RoleBinding` granting either beyond cluster-admin (confirmed live: `kubectl auth can-i get secrets -n ecom --as=system:serviceaccount:ecom:default` → `no`). Two models were considered:

- **Separation of duties** (recommended for a SOC2-aligned Phase 7): a `secrets-publisher` Role that can create/update `SealedSecret` resources (rotate a credential) but has no `secrets` verb at all — publishing a new encrypted value doesn't require ever seeing another service's decrypted one. Viewing decrypted values stays cluster-admin-only.
- **Admin-only, no new role**: leave the current de-facto state as the explicit policy. Simpler, but doesn't let a CI pipeline or a non-admin teammate rotate a credential without full cluster-admin access.

Not implemented in this pass pending that decision — revisit before treating this as fully closed.

## Master key backup and disaster recovery

**The critical operational gap this closes**: a fresh `sealed-secrets-controller` install generates a brand-new random keypair by default. Namespace and secret names staying identical (guaranteed by this project's fixed `ecom` namespace and manifest names) does **not** help if the *key* itself is different — every `SealedSecret` already committed to `k8s/base/secrets.yaml` would become permanently undecryptable after any cluster reinstall or a fresh install on a new machine, unless the original private key is restored into the new controller first.

**The key itself must never be committed to git** — that would defeat the entire point of this mechanism (the whole value of Sealed Secrets is that only the cluster can decrypt; a git-committed key makes every sealed value equivalent to plaintext for anyone with repo access).

**What's in place**:
- `scripts/sealed-secrets-backup-key.sh` — extracts the controller's active private key from the current cluster to `secrets-backup/sealed-secrets-master-key-backup.yaml` (gitignored).
- `scripts/sealed-secrets-restore-key.sh <backup-file>` — applies a backed-up key into a new/reinstalled cluster **before** (or immediately after, with a controller restart) installing the controller, so it picks up the existing key instead of generating a new one.
- A backup was taken during this pass and delivered directly to the operator to store in a password manager or secure vault — **not** left as the only copy in this repo's working tree. Store it somewhere that survives losing this machine entirely.

**Disaster-recovery procedure, on a new/reinstalled cluster**:
```bash
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.38.4/controller.yaml
scripts/sealed-secrets-restore-key.sh /path/to/your/stored/sealed-secrets-master-key-backup.yaml
kubectl apply -f k8s/base/secrets.yaml   # decrypts cleanly, no re-sealing needed
```

**Live-verified 2026-08-15**: simulated exactly this failure — deleted the controller's key and restarted it (what a reinstall does), confirmed every `SealedSecret` genuinely broke (`Failed to unseal: no key could decrypt secret (POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER)`, same for all 6), then ran `sealed-secrets-restore-key.sh` against the backup and confirmed all 6 `Secret` objects were correctly recreated with their original decrypted values (`POSTGRES_PASSWORD` byte-for-byte matched pre-failure) — not just that the commands ran without error.

## Regenerating: with the key backup available

This is the procedure above — restore the existing key into the new controller, then `kubectl apply -f k8s/base/secrets.yaml` unchanged. **No re-sealing needed and none should be done**: `kubeseal` isn't involved in recovery at all when the key is available. Only run `kubeseal` again if you're actually changing a credential's value (see below).

```bash
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.38.4/controller.yaml
scripts/sealed-secrets-restore-key.sh /path/to/your/stored/sealed-secrets-master-key-backup.yaml
kubectl apply -f k8s/base/secrets.yaml
```

## Regenerating: without the key backup available

This is the real disaster case — the backup was lost, or this is a genuinely new setup with no prior key to restore. Every `SealedSecret` currently in `k8s/base/secrets.yaml` is permanently undecryptable against a new controller's new key (confirmed live above); there is no way to recover the *old* sealed values without the *old* key. The path forward is re-sealing the same plaintext credential values against the *new* controller's *new* public key, from scratch:

```bash
# 1. Install the controller fresh — it generates a new random keypair
#    since no existing key was restored first.
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.38.4/controller.yaml
kubectl -n kube-system rollout status deployment/sealed-secrets-controller

# 2. Immediately back up this NEW key, before doing anything else —
#    this is now the one that matters going forward.
scripts/sealed-secrets-backup-key.sh
# then move/copy secrets-backup/sealed-secrets-master-key-backup.yaml to a
# password manager or secure vault — the whole point of this section is
# not repeating the loss that got you here.

# 3. Fetch the new controller's public cert (safe to do locally, it's
#    public by design).
kubeseal --controller-namespace=kube-system --fetch-cert > pub-cert.pem

# 4. Re-seal each credential from its ORIGINAL plaintext value — which
#    must come from wherever it's independently tracked (a password
#    manager, not this repo; this repo never holds plaintext secret
#    values by design). For each one:
kubeseal --cert=pub-cert.pem --format=yaml <<'EOF' > sealed-postgres-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
  namespace: ecom
type: Opaque
stringData:
  POSTGRES_USER: ecommerce_dev
  POSTGRES_PASSWORD: <the real value, from your password manager, not from anything in this repo>
  POSTGRES_DB: ecommerce
EOF
# repeat for rabbitmq-secret, app-datasource-secret, gateway-oauth2-secret,
# keycloak-admin-secret, grafana-admin-secret — see k8s/base/secrets.yaml's
# git history (pre-sealing commit) for each Secret's exact key names, NOT
# its values.

# 5. Replace k8s/base/secrets.yaml's contents with the newly-sealed
#    output and commit — these are safe to commit, same as originally.
kubectl apply -f k8s/base/secrets.yaml
```

If any of the original plaintext values are *also* lost (not just the sealing key — the actual credential value itself, e.g. nobody remembers the Postgres password either), that's no longer a Sealed Secrets problem: it's rotating that credential at its source (changing Postgres's actual password, Keycloak's actual admin password, etc.) and re-sealing the new value — which is the normal credential-rotation path, not a recovery path.

## Related
- `k8s/base/secrets.yaml` — the sealed manifests themselves
- `doc/architecture/07-migration-planning.md` — Phase 7's original "secrets migrated out of plaintext config" DoD item, closed by this doc
- `doc/adr/ADR-0002-zero-trust-spire-app-level.md` — the separate cert-based (not secret-based) identity mechanism for service-to-service mTLS; unaffected by this change, covers a different problem
