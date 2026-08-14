package org.bgm.paymentservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.bgm.common.idempotency.IdempotencyKeyResolver;
import org.bgm.common.idempotency.PayloadHasher;
import org.bgm.paymentservice.exception.IdempotencyConflictException;
import org.bgm.paymentservice.model.IdempotencyRecord;
import org.bgm.paymentservice.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

// ADR-0024: same pattern as order-service's IdempotencyService.
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

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
