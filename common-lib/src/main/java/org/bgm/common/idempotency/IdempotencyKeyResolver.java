package org.bgm.common.idempotency;

/**
 * ADR-0024: resolves the effective idempotency identity — the client's
 * Idempotency-Key header if supplied, otherwise the sanitized-payload
 * hash. Callers still store the payload hash alongside the resolved key
 * regardless of which path was taken, so a client-key reused with a
 * different payload can be detected (Stripe model, see the ADR).
 */
public final class IdempotencyKeyResolver {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private IdempotencyKeyResolver() {
    }

    public static String resolve(String clientSuppliedKey, Object payload) {
        if (clientSuppliedKey != null && !clientSuppliedKey.isBlank()) {
            return clientSuppliedKey;
        }
        return PayloadHasher.sha256Hex(payload);
    }
}
