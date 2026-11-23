CREATE TABLE IF NOT EXISTS part_compatibilities (
    id UUID PRIMARY KEY,
    part_product_id UUID NOT NULL,
    device_type VARCHAR(100) NOT NULL,
    device_brand VARCHAR(100) NOT NULL,
    device_model VARCHAR(255) NOT NULL,
    compatibility_status VARCHAR(32) NOT NULL,
    source VARCHAR(100) NOT NULL,
    version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_part_compatibility_status CHECK (
        compatibility_status IN ('VERIFIED_COMPATIBLE', 'UNKNOWN', 'INCOMPATIBLE')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_part_compatibility_target
    ON part_compatibilities(part_product_id, device_type, device_brand, device_model);

ALTER TABLE order_lines
    ADD COLUMN IF NOT EXISTS target_device_id UUID;

ALTER TABLE part_compatibilities
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

INSERT INTO part_compatibilities
    (id, part_product_id, device_type, device_brand, device_model, compatibility_status, source, version, created_at)
VALUES
    ('77777777-7777-7777-7777-777777777701', '66666666-6666-6666-6666-666666666601',
     'AC', 'Daikin', 'FTKF25', 'VERIFIED_COMPATIBLE', 'CURATED_PILOT', '2026-11-20', CURRENT_TIMESTAMP),
    ('77777777-7777-7777-7777-777777777702', '66666666-6666-6666-6666-666666666605',
     'WATER_PURIFIER', 'Karofi', 'KAQ-U95', 'VERIFIED_COMPATIBLE', 'CURATED_PILOT', '2026-11-20', CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
