package org.bgm.orderservice.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
    }
}
