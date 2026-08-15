package org.bgm.catalogservice.service;

import lombok.RequiredArgsConstructor;
import org.bgm.catalogservice.client.InventoryServiceClient;
import org.bgm.catalogservice.dto.ProductRequest;
import org.bgm.catalogservice.exception.NotProductOwnerException;
import org.bgm.catalogservice.exception.ProductNotFoundException;
import org.bgm.catalogservice.model.Product;
import org.bgm.catalogservice.model.ProductStatus;
import org.bgm.catalogservice.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryServiceClient inventoryServiceClient;

    // ADMIN-created products don't have a real provider — this fixed
    // placeholder keeps the "provider name" display consistent instead
    // of a blank/null value; see V5__add_provider_name.sql for the same
    // placeholder backfilled onto pre-existing rows.
    private static final String ADMIN_PROVIDER_NAME = "Demo Vendor Co.";

    /**
     * @param callerId       Keycloak subject of the authenticated caller
     * @param callerIsAdmin  true for ADMIN (no ownership restriction), false for PROVIDER
     * @param callerName     display name (JWT "name" claim) shown alongside the product
     * @param bearerToken    forwarded to inventory-service — see InventoryServiceClient's Javadoc
     */
    @Transactional
    public Product create(ProductRequest request, String callerId, boolean callerIsAdmin, String callerName, String bearerToken) {
        Product product = new Product();
        applyRequest(product, request);
        Instant now = Instant.now();
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        product.setActive(true);
        // ADMIN-created products stay owner-less (today's whole catalog,
        // still manageable by any admin) — only PROVIDER-created ones
        // get an owner.
        product.setProviderId(callerIsAdmin ? null : callerId);
        product.setProviderName(callerIsAdmin ? ADMIN_PROVIDER_NAME : callerName);
        // ADMIN products go live immediately (matches the pre-Phase-8
        // catalog's zero-gate behavior); PROVIDER ones start DRAFT and
        // need an explicit publish() call — see ProductStatus's Javadoc.
        product.setStatus(callerIsAdmin ? ProductStatus.LISTED : ProductStatus.DRAFT);
        product = productRepository.save(product);

        if (request.quantity() != null && request.quantity() > 0) {
            inventoryServiceClient.addStock(product.getId(), request.quantity(), bearerToken);
        }
        return product;
    }

    @Transactional
    public Product update(long id, ProductRequest request, String callerId, boolean callerIsAdmin) {
        Product product = get(id);
        requireOwnerOrAdmin(product, callerId, callerIsAdmin);
        applyRequest(product, request);
        product.setUpdatedAt(Instant.now());
        return productRepository.save(product);
    }

    /** Moves a DRAFT product to LISTED — the one explicit gate a provider's product needs to pass. */
    @Transactional
    public Product publish(long id, String callerId, boolean callerIsAdmin) {
        Product product = get(id);
        requireOwnerOrAdmin(product, callerId, callerIsAdmin);
        product.setStatus(ProductStatus.LISTED);
        product.setUpdatedAt(Instant.now());
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product get(long id) {
        return productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    /** Browse/search only ever returns LISTED + active products — a DRAFT product is invisible until published. */
    @Transactional(readOnly = true)
    public Page<Product> search(String query, Pageable pageable) {
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrueAndStatus(query, ProductStatus.LISTED, pageable);
    }

    @Transactional(readOnly = true)
    public List<Product> findMine(String callerId) {
        return productRepository.findByProviderId(callerId);
    }

    /** Soft delete — keeps the product row (order history references it) but hides it from browse/search. */
    @Transactional
    public void deactivate(long id, String callerId, boolean callerIsAdmin) {
        Product product = get(id);
        requireOwnerOrAdmin(product, callerId, callerIsAdmin);
        product.setActive(false);
        product.setUpdatedAt(Instant.now());
        productRepository.save(product);
    }

    private void requireOwnerOrAdmin(Product product, String callerId, boolean callerIsAdmin) {
        if (callerIsAdmin) {
            return;
        }
        if (!Objects.equals(product.getProviderId(), callerId)) {
            throw new NotProductOwnerException(product.getId());
        }
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setCategoryId(request.categoryId());
        product.setPrice(request.price());
    }
}
