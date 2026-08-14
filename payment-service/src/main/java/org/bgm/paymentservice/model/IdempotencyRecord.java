package org.bgm.paymentservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// ADR-0024: same pattern as order-service's IdempotencyRecord.
@Getter
@Setter
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private int responseStatus;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
