-- Phase 8: matching stock for catalog-service's V2__seed_products.sql
-- (1000 generated products, IDs 2..1001, plus the pre-existing product 1)
-- — without this, every checkout fails inventory reservation with
-- "No inventory record for product: N" (found live: silently cancels
-- the order, no user-facing error, since the saga's failure path is
-- backend-only and this UI doesn't poll for outcome).
-- Cross-schema reference: both schemas live in the same "ecommerce"
-- database (ADR-0004), just under different currentSchema connection
-- params — a same-database, cross-schema SELECT works as long as this
-- migration's connecting user has read access to catalog_service (it
-- does; both schemas are owned by the same ecommerce_dev role).
INSERT INTO inventory (product_id, available_qty, reserved_qty, version)
SELECT id, 50, 0, 0 FROM catalog_service.product
WHERE id NOT IN (SELECT product_id FROM inventory);
