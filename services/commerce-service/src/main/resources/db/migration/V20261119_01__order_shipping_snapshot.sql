ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shipping_address_id UUID,
    ADD COLUMN IF NOT EXISTS shipping_recipient_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS shipping_phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS shipping_address_line VARCHAR(500),
    ADD COLUMN IF NOT EXISTS shipping_ward_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS shipping_ward_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS shipping_province_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS shipping_province_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS shipping_formatted_address VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS shipping_latitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS shipping_longitude DOUBLE PRECISION;
