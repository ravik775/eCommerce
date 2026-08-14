package org.bgm.paymentservice.service.strategy;

import java.util.UUID;

/**
 * All processors are simulated gateways (see {@link PaymentProcessor}).
 * Deterministic, not random, so behavior is testable: always succeeds,
 * since real gateway failure-mode simulation isn't this phase's concern.
 */
abstract class AbstractSimulatedProcessor implements PaymentProcessor {

    @Override
    public ProcessorResult pay(long orderId, double amount) {
        String transactionId = gatewayName() + "-" + UUID.randomUUID();
        return new ProcessorResult(true, gatewayName(), transactionId, null);
    }

    protected abstract String gatewayName();
}
