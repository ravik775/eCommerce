-- DRAFT/LISTED lifecycle for provider-created products (see
-- ProductStatus's Javadoc). DEFAULT 'LISTED': every existing row
-- (all admin-created, pre-Phase-8) stays visible exactly as before —
-- only newly provider-created products start DRAFT.
ALTER TABLE product ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'LISTED';
