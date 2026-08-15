package org.bgm.catalogservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.catalogservice.dto.PageResponse;
import org.bgm.catalogservice.dto.ProductRequest;
import org.bgm.catalogservice.dto.ProductResponse;
import org.bgm.catalogservice.service.ProductService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // ADR-0025 + Phase 8 provider feature: mutations are ADMIN or
    // PROVIDER — ownership (a PROVIDER may only touch their own
    // products) is enforced in ProductService, not here; this
    // annotation is only the coarse "authenticated as one of these
    // roles at all" gate, same split of responsibility as the
    // gateway's RequireRole filter (edge pre-check, app layer
    // authoritative). Read endpoints (get/search) stay open to any
    // authenticated user (CUSTOMER included) since browsing the
    // catalog isn't a privileged action.
    @PreAuthorize("hasAnyRole('ADMIN','PROVIDER')")
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request, @AuthenticationPrincipal Jwt jwt) {
        var product = productService.create(request, jwt.getSubject(), isAdmin(jwt), jwt.getClaimAsString("name"), jwt.getTokenValue());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(product));
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROVIDER')")
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable("id") long id, @Valid @RequestBody ProductRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ProductResponse.from(productService.update(id, request, jwt.getSubject(), isAdmin(jwt)));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable("id") long id) {
        return ProductResponse.from(productService.get(id));
    }

    // page is 0-based; size is capped at 100 regardless of what's
    // requested, so a caller can't force an unbounded full-table scan
    // through this open (any-authenticated-user) endpoint.
    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping("/search")
    public PageResponse<ProductResponse> search(
            @RequestParam("query") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("id"));
        return PageResponse.from(productService.search(query, pageable).map(ProductResponse::from));
    }

    // Providers need to see what they've submitted — ADMIN doesn't use
    // this (the whole catalog is already theirs to manage).
    @PreAuthorize("hasRole('PROVIDER')")
    @GetMapping("/mine")
    public List<ProductResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return productService.findMine(jwt.getSubject()).stream().map(ProductResponse::from).toList();
    }

    // The one explicit gate a provider's DRAFT product needs to pass
    // before it's visible in search/browse — see ProductStatus's
    // Javadoc for why this is a self-service action, not a separate
    // approval role.
    @PreAuthorize("hasAnyRole('ADMIN','PROVIDER')")
    @PutMapping("/{id}/publish")
    public ProductResponse publish(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        return ProductResponse.from(productService.publish(id, jwt.getSubject(), isAdmin(jwt)));
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROVIDER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        productService.deactivate(id, jwt.getSubject(), isAdmin(jwt));
        return ResponseEntity.noContent().build();
    }

    private boolean isAdmin(Jwt jwt) {
        Object realmAccess = jwt.getClaimAsMap("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> map)) {
            return false;
        }
        Object roles = map.get("roles");
        return roles instanceof List<?> list && list.contains("ADMIN");
    }
}
