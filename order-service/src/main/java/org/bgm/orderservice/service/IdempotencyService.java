package org.bgm.orderservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.bgm.common.idempotency.IdempotencyKeyResolver;
import org.bgm.common.idempotency.PayloadHasher;
import org.bgm.orderservice.exception.IdempotencyConflictException;
import org.bgm.orderservice.model.IdempotencyRecord;
import org.bgm.orderservice.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ADR-0024 (doc/adr/ADR-0024-idempotency-hybrid-key-hash.md): applies the
 * hybrid idempotency model to a single write operation. Storage is
 * per-service (IdempotencyRecord, order_service schema); the key
 * resolution and hashing algorithm are shared via common-lib.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository repository;
    // Spring's auto-configured ObjectMapper bean (has JavaTimeModule etc.
    // registered) — not `new ObjectMapper()`, which lacks those modules and
    // fails to serialize Instant fields (caught by live testing, not a
    // hypothetical).
    private final ObjectMapper objectMapper;

    /**
     * Executes {@code operation} exactly once per resolved idempotency key.
     * A repeat call with the same key and the same request body replays the
     * first call's response instead of re-executing. A repeat call with the
     * same key and a DIFFERENT body is rejected (409), not replayed.
     */
    @Transactional
    public <T> ResponseEntity<T> execute(String clientSuppliedKey, Object requestPayload,
                                          Class<T> responseType, Supplier<ResponseEntity<T>> operation) {
        String key = IdempotencyKeyResolver.resolve(clientSuppliedKey, requestPayload);
        String requestHash = PayloadHasher.sha256Hex(requestPayload);

        Optional<IdempotencyRecord> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), key, requestHash, responseType);
        }

        ResponseEntity<T> response = operation.get();
        try {
            saveRecord(key, requestHash, response);
        } catch (DataIntegrityViolationException raceLostToConcurrentRequest) {
            // Another request with the same key won the unique-constraint
            // race between our findByIdempotencyKey check and this insert —
            // the atomic-insert safety barrier from ADR-0024's evidence.
            // Replay whatever that concurrent request stored instead of
            // returning two different "first" responses for one key.
            IdempotencyRecord winner = repository.findByIdempotencyKey(key)
                    .orElseThrow(() -> raceLostToConcurrentRequest);
            return replay(winner, key, requestHash, responseType);
        }
        return response;
    }

    private <T> ResponseEntity<T> replay(IdempotencyRecord record, String key, String requestHash,
                                          Class<T> responseType) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);
        }
        try {
            T body = objectMapper.readValue(record.getResponseBody(), responseType);
            return ResponseEntity.status(record.getResponseStatus()).body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt cached idempotent response for key: " + key, e);
        }
    }

    private <T> void saveRecord(String key, String requestHash, ResponseEntity<T> response) {
        try {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setIdempotencyKey(key);
            record.setRequestHash(requestHash);
            record.setResponseBody(objectMapper.writeValueAsString(response.getBody()));
            record.setResponseStatus(response.getStatusCode().value());
            Instant now = Instant.now();
            record.setCreatedAt(now);
            record.setExpiresAt(now.plus(TTL));
            repository.saveAndFlush(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response is not serializable for idempotency caching", e);
        }
    }
}
