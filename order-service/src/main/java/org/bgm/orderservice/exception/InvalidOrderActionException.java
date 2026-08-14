package org.bgm.orderservice.exception;

public class InvalidOrderActionException extends RuntimeException {
    public InvalidOrderActionException(String message) {
        super(message);
    }
}
