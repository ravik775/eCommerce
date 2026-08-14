package org.bgm.common.idempotency;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * ADR-0024 (doc/adr/ADR-0024-idempotency-hybrid-key-hash.md): deterministic
 * SHA-256 hash of a request payload, used as the idempotency-key fallback
 * when the client doesn't supply an Idempotency-Key header.
 *
 * "Sanitized" per ADR-0024 means: hash the request DTO as-is. Request DTOs
 * in this codebase carry only business fields (no timestamps/nonces), so
 * no separate field-exclusion step is needed — if a future DTO ever gains
 * a volatile field, exclude it before calling this class, don't add
 * exclusion logic here.
 *
 * Deterministic regardless of field declaration order: keys are sorted
 * alphabetically before serialization, so semantically-identical payloads
 * always hash identically.
 */
public final class PayloadHasher {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PayloadHasher() {
    }

    public static String sha256Hex(Object payload) {
        try {
            byte[] canonicalJson = CANONICAL_MAPPER.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm — this can't actually happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not serializable for hashing", e);
        }
    }
}
