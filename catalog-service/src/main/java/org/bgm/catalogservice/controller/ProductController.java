package org.bgm.catalogservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.catalogservice.dto.ProductRequest;
import org.bgm.catalogservice.dto.ProductResponse;
import org.bgm.catalogservice.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // ADR-0025: product mutations are ADMIN-only — the concrete example
    // the ADR's "annotate the actual admin-only endpoints" follow-up asked
    // for. Read endpoints (get/search) stay open to any authenticated user
    // (CUSTOMER included) since browsing the catalog isn't an admin action.
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        var product = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(product));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable("id") long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.update(id, request));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable("id") long id) {
        return ProductResponse.from(productService.get(id));
    }

    @GetMapping("/search")
    public List<ProductResponse> search(@RequestParam("query") String query) {
        return productService.search(query).stream().map(ProductResponse::from).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        productService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
