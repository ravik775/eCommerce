-- Phase 8 provider feature: nullable so every existing (admin-created)
-- product stays owner-less, matching today's implicit "the catalog is
-- admin-only" model. Stores the Keycloak subject (a UUID string), not
-- a numeric user-service ID like order-service's customerId — no
-- cross-service lookup needed to resolve it, and there's no existing
-- requirement that provider identity match the customer-ID numbering
-- scheme (different bounded context).
ALTER TABLE product ADD COLUMN provider_id VARCHAR(255);
CREATE INDEX idx_product_provider_id ON product (provider_id);
