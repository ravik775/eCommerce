package org.bgm.paymentservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "payment")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long orderId;
    private Double amount;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    // "gateway" per Notes.md's payment table — the simulated/mock gateway
    // name a processor used (see service/strategy package). No real
    // gateway (Stripe/Razorpay/etc.) is integrated in this phase.
    private String gateway;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String transactionId;
    private String refundTransactionId;
    private String refundReason;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
