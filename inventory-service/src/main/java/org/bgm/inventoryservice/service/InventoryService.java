package org.bgm.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import org.bgm.inventoryservice.dto.BulkInventoryRequest;
import org.bgm.inventoryservice.dto.InventoryItemRequest;
import org.bgm.inventoryservice.exception.InsufficientStockException;
import org.bgm.inventoryservice.exception.ProductNotFoundException;
import org.bgm.inventoryservice.model.Inventory;
import org.bgm.inventoryservice.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public Inventory getInventory(long productId) {
        return inventoryRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * Reserves stock for every item in the request. Atomic across the whole
     * batch: if any item lacks sufficient available stock, an exception is
     * thrown and the surrounding transaction rolls back every reservation
     * already applied in this call — no partial reservation is left behind.
     *
     * REQUIRES_NEW (not the default REQUIRED): found via live saga testing
     * that when this is called from InventorySagaConsumer's @Transactional
     * listener method, an exception here — even caught immediately by the
     * caller — had already marked the caller's shared transaction
     * rollback-only (Spring's standard behavior for a nested REQUIRED
     * transaction), causing UnexpectedRollbackException when the listener
     * later tried to commit its own outbox write. This method's own
     * atomicity is a self-contained concern (see javadoc above) and
     * shouldn't be coupled to whatever transaction its caller happens to
     * be running in.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(BulkInventoryRequest request) {
        for (InventoryItemRequest item : request.items()) {
            Inventory inventory = lockedInventory(item.productId());
            if (inventory.getAvailableQty() < item.quantity()) {
                throw new InsufficientStockException(item.productId(), item.quantity(), inventory.getAvailableQty());
            }
            inventory.setAvailableQty(inventory.getAvailableQty() - item.quantity());
            inventory.setReservedQty(inventory.getReservedQty() + item.quantity());
        }
    }

    /**
     * Undoes a prior reservation (order cancellation compensation,
     * ADR-0007). Same REQUIRES_NEW reasoning as reserve() — also called
     * from InventorySagaConsumer's @Transactional listener.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(BulkInventoryRequest request) {
        for (InventoryItemRequest item : request.items()) {
            Inventory inventory = lockedInventory(item.productId());
            int toRelease = Math.min(item.quantity(), inventory.getReservedQty());
            inventory.setReservedQty(inventory.getReservedQty() - toRelease);
            inventory.setAvailableQty(inventory.getAvailableQty() + toRelease);
        }
    }

    /** Direct restock — used for admin stock-add and order-return add-back. */
    @Transactional
    public void add(BulkInventoryRequest request) {
        for (InventoryItemRequest item : request.items()) {
            Inventory inventory = inventoryRepository.findById(item.productId())
                    .orElseGet(() -> newInventoryRecord(item.productId()));
            inventory.setAvailableQty(inventory.getAvailableQty() + item.quantity());
            inventoryRepository.save(inventory);
        }
    }

    private Inventory lockedInventory(long productId) {
        return inventoryRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private Inventory newInventoryRecord(long productId) {
        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setAvailableQty(0);
        inventory.setReservedQty(0);
        return inventory;
    }
}
