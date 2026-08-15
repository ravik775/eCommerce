package org.bgm.inventoryservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.inventoryservice.dto.BulkInventoryRequest;
import org.bgm.inventoryservice.dto.InventoryResponse;
import org.bgm.inventoryservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/reserve")
    public ResponseEntity<Void> reserve(@Valid @RequestBody BulkInventoryRequest request) {
        inventoryService.reserve(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/release")
    public ResponseEntity<Void> release(@Valid @RequestBody BulkInventoryRequest request) {
        inventoryService.release(request);
        return ResponseEntity.ok().build();
    }

    // Was open to any authenticated user (no @PreAuthorize at all) —
    // real gap, closed as part of Phase 8's provider feature: only
    // ADMIN (any product) or PROVIDER (their own — catalog-service's
    // InventoryServiceClient only calls this right after that same
    // caller created the product, so ownership was already enforced
    // one hop upstream) should be able to add stock.
    @PreAuthorize("hasAnyRole('ADMIN','PROVIDER')")
    @PostMapping("/add")
    public ResponseEntity<Void> add(@Valid @RequestBody BulkInventoryRequest request) {
        inventoryService.add(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{productId}")
    public InventoryResponse getInventory(@PathVariable("productId") long productId) {
        return InventoryResponse.from(inventoryService.getInventory(productId));
    }
}
