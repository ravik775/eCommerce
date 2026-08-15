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

    // ADR-0025 + Phase 8 provider feature, redefined by ADR-0033:
    // mutations are CATALOG_ADMIN or PROVIDER — ownership (a PROVIDER
    // may only touch their own products) is enforced in ProductService,
    // not here; this annotation is only the coarse "authenticated as one
    // of these roles at all" gate, same split of responsibility as the
    // gateway's RequireRole filter (edge pre-check, app layer
    // authoritative). Read endpoints (get/search) stay open to any
    // authenticated user (CUSTOMER included) since browsing the catalog
    // isn't a privileged action.
    //
    // Not listing SUPER_ADMIN here: it's a Keycloak composite role
    // (CATALOG_ADMIN + INVENTORY_ADMIN + ADMIN, see the realm config) —
    // its JWT already carries CATALOG_ADMIN via composite expansion, so
    // every check below only ever needs to name the one role it actually
    // means. Composition happens once, at the IAM layer.
    @PreAuthorize("hasAnyRole('CATALOG_ADMIN','PROVIDER')")
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request, @AuthenticationPrincipal Jwt jwt) {
        var product = productService.create(request, callerId(jwt), isCatalogAdmin(jwt), jwt.getClaimAsString("name"), jwt.getTokenValue());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(product));
    }

    @PreAuthorize("hasAnyRole('CATALOG_ADMIN','PROVIDER')")
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable("id") long id, @Valid @RequestBody ProductRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ProductResponse.from(productService.update(id, request, callerId(jwt), isCatalogAdmin(jwt)));
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

    // Providers need to see what they've submitted — CATALOG_ADMIN
    // doesn't use this (the whole catalog is already theirs to manage).
    @PreAuthorize("hasRole('PROVIDER')")
    @GetMapping("/mine")
    public List<ProductResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return productService.findMine(callerId(jwt)).stream().map(ProductResponse::from).toList();
    }

    // The one explicit gate a provider's DRAFT product needs to pass
    // before it's visible in search/browse — see ProductStatus's
    // Javadoc for why this is a self-service action, not a separate
    // approval role.
    @PreAuthorize("hasAnyRole('CATALOG_ADMIN','PROVIDER')")
    @PutMapping("/{id}/publish")
    public ProductResponse publish(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        return ProductResponse.from(productService.publish(id, callerId(jwt), isCatalogAdmin(jwt)));
    }

    @PreAuthorize("hasAnyRole('CATALOG_ADMIN','PROVIDER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        productService.deactivate(id, callerId(jwt), isCatalogAdmin(jwt));
        return ResponseEntity.noContent().build();
    }

    // Found live: this Keycloak deployment's issued access tokens don't
    // carry a "sub" claim at all (confirmed on tokens from both the
    // master and ecom realms, with and without the openid scope
    // explicitly requested — not a scope-request artifact, a genuine
    // characteristic of this Keycloak instance). jwt.getSubject() being
    // silently null for every caller was the real cause behind a whole
    // session's worth of "identity looks wrong" symptoms: every
    // provider's productId ownership was being written as null instead
    // of their real identity, since null == null in the ownership check.
    // preferred_username is present and unique per user on every token
    // actually decoded — used here as the reliable identity key instead.
    private String callerId(Jwt jwt) {
        return jwt.getClaimAsString("preferred_username");
    }

    // ADR-0033: renamed from isAdmin — checks CATALOG_ADMIN specifically
    // (the role that actually grants the ownership-bypass this method
    // backs), not the old catch-all ADMIN, which no longer carries
    // operational privilege at all.
    private boolean isCatalogAdmin(Jwt jwt) {
        Object realmAccess = jwt.getClaimAsMap("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> map)) {
            return false;
        }
        Object roles = map.get("roles");
        return roles instanceof List<?> list && list.contains("CATALOG_ADMIN");
    }
}
