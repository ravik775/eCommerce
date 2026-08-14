package org.bgm.inventoryservice.repository;

import org.bgm.inventoryservice.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    // Pessimistic lock: reserve/release mutate available/reserved quantities
    // and must not race with a concurrent reservation for the same product.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productId = :productId")
    Optional<Inventory> findByIdForUpdate(long productId);

    List<Inventory> findByProductIdIn(List<Long> productIds);
}
