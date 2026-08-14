package org.bgm.inventoryservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// Records what was reserved for an order, so a later compensating
// `payment-failed` event (which per ADR-0007/common-lib's schema carries
// only orderId + reason, not item detail — that's payment's domain, not
// inventory's) can be released without re-deriving it. One row per
// (orderId, productId).
@Getter
@Setter
@Entity
@Table(name = "inventory_reservation")
public class InventoryReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long orderId;
    private long productId;
    private int quantity;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    public enum ReservationStatus {
        RESERVED,
        RELEASED
    }
}
