ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS unit_key VARCHAR(128);

ALTER TABLE devices
    ALTER COLUMN model DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_device_unit_key
    ON devices (unit_key)
    WHERE unit_key IS NOT NULL;

ALTER TABLE warranties
    ALTER COLUMN device_id DROP NOT NULL;

ALTER TABLE warranties
    ALTER COLUMN starts_on DROP NOT NULL;

ALTER TABLE warranties
    ALTER COLUMN ends_on DROP NOT NULL;

ALTER TABLE warranties
    ADD COLUMN IF NOT EXISTS unit_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS start_trigger VARCHAR(40) NOT NULL DEFAULT 'DELIVERY_DATE',
    ADD COLUMN IF NOT EXISTS warranty_responsible_org_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uq_warranty_unit_key
    ON warranties (unit_key)
    WHERE unit_key IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_address_coordinates'
    ) THEN
        ALTER TABLE user_addresses
            ADD CONSTRAINT chk_user_address_coordinates CHECK (
                (latitude IS NULL AND longitude IS NULL)
                OR (
                    latitude IS NOT NULL
                    AND longitude IS NOT NULL
                    AND latitude BETWEEN -90 AND 90
                    AND longitude BETWEEN -180 AND 180
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_location_level_parent'
    ) THEN
        ALTER TABLE locations
            ADD CONSTRAINT chk_location_level_parent CHECK (
                (level = 'PROVINCE' AND parent_id IS NULL)
                OR (level = 'WARD' AND parent_id IS NOT NULL)
            );
    END IF;
END $$;
