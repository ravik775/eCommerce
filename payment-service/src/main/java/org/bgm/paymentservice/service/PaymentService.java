package org.bgm.paymentservice.service;

import lombok.RequiredArgsConstructor;
import org.bgm.paymentservice.dto.InitiatePaymentRequest;
import org.bgm.paymentservice.exception.InvalidRefundException;
import org.bgm.paymentservice.exception.PaymentNotFoundException;
import org.bgm.paymentservice.model.Payment;
import org.bgm.paymentservice.model.PaymentStatus;
import org.bgm.paymentservice.repository.PaymentRepository;
import org.bgm.paymentservice.service.strategy.PaymentProcessor;
import org.bgm.paymentservice.service.strategy.PaymentProcessorResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessorResolver processorResolver;

    @Transactional
    public Payment initiate(InitiatePaymentRequest request) {
        PaymentProcessor processor = processorResolver.resolve(request.method());
        PaymentProcessor.ProcessorResult result = processor.pay(request.orderId(), request.amount());

        Payment payment = new Payment();
        payment.setOrderId(request.orderId());
        payment.setAmount(request.amount());
        payment.setMethod(request.method());
        payment.setGateway(result.gatewayName());
        payment.setStatus(result.success() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);
        payment.setTransactionId(result.transactionId());
        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());

        // ADR-0007 (doc/adr/ADR-0007-saga-outbox-idempotency.md): Phase 3
        // publishes `payment-success`/`payment-failed` via the outbox here,
        // in this same transaction, driving order-service's saga step and
        // inventory-service's compensating release on failure.
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment getPayment(long id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional
    public Payment refund(long id, String reason) {
        Payment payment = getPayment(id);
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new InvalidRefundException(
                    "Payment " + id + " cannot be refunded from status " + payment.getStatus());
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundTransactionId("REFUND-" + UUID.randomUUID());
        payment.setRefundReason(reason);
        payment.setUpdatedAt(Instant.now());
        return paymentRepository.save(payment);
    }
}
