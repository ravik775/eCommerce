#!/usr/bin/env bash
# Restores a backed-up Sealed Secrets master key into a freshly installed
# (or reinstalled) cluster, so every SealedSecret already committed in
# k8s/base/secrets.yaml keeps decrypting correctly — without this, a new
# controller install generates a brand-new random keypair and every
# existing SealedSecret becomes permanently undecryptable, even though
# their namespace/name (the strict-scope binding) hasn't changed.
#
# Run this BEFORE applying k8s/base/secrets.yaml on the new cluster, and
# BEFORE (or right after, then restart) installing the
# sealed-secrets-controller itself — the controller only generates a new
# key if it doesn't find an existing one labeled
# sealedsecrets.bitnami.com/sealed-secrets-key=active in its own
# namespace (kube-system) at startup.
#
# Usage: scripts/sealed-secrets-restore-key.sh path/to/sealed-secrets-master-key-backup.yaml
set -eu

BACKUP_FILE="${1:?Usage: $0 <path-to-backup-yaml>}"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "[sealed-secrets-restore] Backup file not found: $BACKUP_FILE" >&2
  exit 1
fi

echo "[sealed-secrets-restore] Applying master key into kube-system..."
kubectl apply -f "$BACKUP_FILE"

echo "[sealed-secrets-restore] Restarting sealed-secrets-controller (if already running) so it picks up the restored key..."
kubectl -n kube-system rollout restart deployment/sealed-secrets-controller 2>/dev/null || \
  echo "[sealed-secrets-restore] Controller not deployed yet — install it now (see doc/architecture/12-secrets-management.md), it will find this key on first startup."

echo "[sealed-secrets-restore] Done. Verify with:"
echo "  kubectl get secret -n kube-system -l sealedsecrets.bitnami.com/sealed-secrets-key"
echo "  kubectl apply -f k8s/base/secrets.yaml   # should decrypt cleanly, no changes needed to that file"
