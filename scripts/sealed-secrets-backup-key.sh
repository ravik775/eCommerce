#!/usr/bin/env bash
# Backs up the Sealed Secrets controller's active master key from the
# CURRENT cluster. Run this once right after first installing the
# controller, and again any time you manually rotate the key
# (`kubeseal --controller-namespace=kube-system rotate` /
# https://github.com/bitnami-labs/sealed-secrets#secret-rotation).
#
# The output file is the actual private key material — never commit it.
# Store it in a password manager or secure vault, not just this
# machine's disk (secrets-backup/ is gitignored, but that only protects
# against committing it, not against losing this machine).
#
# Usage: scripts/sealed-secrets-backup-key.sh [output-path]
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT_DIR/secrets-backup/sealed-secrets-master-key-backup.yaml}"

mkdir -p "$(dirname "$OUT")"
kubectl get secret -n kube-system -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > "$OUT"

echo "[sealed-secrets-backup] Wrote $OUT"
echo "[sealed-secrets-backup] Move/copy this to a password manager or secure vault now — do not leave it as the only copy on this machine."
