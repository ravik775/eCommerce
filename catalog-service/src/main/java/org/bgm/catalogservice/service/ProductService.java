package org.bgm.catalogservice.service;

import lombok.RequiredArgsConstructor;
import org.bgm.catalogservice.dto.ProductRequest;
import org.bgm.catalogservice.exception.ProductNotFoundException;
import org.bgm.catalogservice.model.Product;
import org.bgm.catalogservice.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public Product create(ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        Instant now = Instant.now();
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        product.setActive(true);
        return productRepository.save(product);
    }

    @Transactional
    public Product update(long id, ProductRequest request) {
        Product product = get(id);
        applyRequest(product, request);
        product.setUpdatedAt(Instant.now());
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product get(long id) {
        return productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Product> search(String query) {
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(query);
    }

    /** Soft delete — keeps the product row (order history references it) but hides it from browse/search. */
    @Transactional
    public void deactivate(long id) {
        Product product = get(id);
        product.setActive(false);
        product.setUpdatedAt(Instant.now());
        productRepository.save(product);
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setCategoryId(request.categoryId());
        product.setPrice(request.price());
    }
}
