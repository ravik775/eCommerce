package org.bgm.catalogservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String name;
    private String description;
    private Long categoryId;
    private Double price;
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    // Null for admin-created products (today's whole catalog) — set to
    // the creating user's Keycloak subject for provider-created ones.
    // See V3__add_provider_id.sql.
    private String providerId;

    // Denormalized display name shown alongside the product (avoids a
    // per-listing call to user-service just to render a name). Set at
    // creation from the caller's JWT "name" claim for PROVIDER-created
    // products; ADMIN-created ones get a fixed placeholder — see
    // V5__add_provider_name.sql for the backfill on pre-existing rows.
    private String providerName;

    // Admin-created products go LISTED immediately (matches the
    // pre-Phase-8 catalog's zero-gate behavior — ADMIN is already
    // trusted). Provider-created ones start DRAFT and need an explicit
    // publish (ProductService.publish) — see ProductStatus's Javadoc
    // for why this is a status field, not a full approval workflow.
    @Enumerated(EnumType.STRING)
    private ProductStatus status = ProductStatus.LISTED;
}
