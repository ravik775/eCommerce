package org.bgm.paymentservice.client;

public class OrderLookupException extends RuntimeException {
    public OrderLookupException(long orderId, Throwable cause) {
        super("Could not look up amount for order " + orderId, cause);
    }
}
