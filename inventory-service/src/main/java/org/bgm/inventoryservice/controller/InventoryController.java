package org.bgm.inventoryservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.inventoryservice.dto.BulkInventoryRequest;
import org.bgm.inventoryservice.dto.InventoryResponse;
import org.bgm.inventoryservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
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
