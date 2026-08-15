package org.bgm.catalogservice.repository;

import org.bgm.catalogservice.model.Product;
import org.bgm.catalogservice.model.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByNameContainingIgnoreCaseAndActiveTrueAndStatus(String name, ProductStatus status, Pageable pageable);
    List<Product> findByProviderId(String providerId);
}
