package org.bgm.inventoryservice.repository;

import org.bgm.inventoryservice.model.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {
    List<InventoryReservation> findByOrderIdAndStatus(long orderId, InventoryReservation.ReservationStatus status);
}
