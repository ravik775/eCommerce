-- ADR-0004: runs inside the inventory_service schema.

CREATE TABLE inventory (
    product_id    BIGINT PRIMARY KEY,
    available_qty INTEGER NOT NULL DEFAULT 0,
    reserved_qty  INTEGER NOT NULL DEFAULT 0,
    version       BIGINT NOT NULL DEFAULT 0
);
