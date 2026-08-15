ALTER TABLE product ADD COLUMN provider_name VARCHAR(255);
UPDATE product SET provider_name = 'Demo Vendor Co.' WHERE provider_name IS NULL;
