CREATE TABLE IF NOT EXISTS locations (
    id SERIAL PRIMARY KEY,
    official_code VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    level VARCHAR(20) NOT NULL,
    codename VARCHAR(255) NOT NULL,
    division_type VARCHAR(100) NOT NULL,
    phone_code INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    parent_id INTEGER REFERENCES locations(id) ON DELETE RESTRICT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_location_level_parent CHECK (
        (level = 'PROVINCE' AND parent_id IS NULL)
        OR (level = 'WARD' AND parent_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_location_province_code
    ON locations (official_code)
    WHERE parent_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_location_ward_code
    ON locations (parent_id, official_code)
    WHERE parent_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_locations_parent_active
    ON locations (parent_id, is_active);

CREATE OR REPLACE FUNCTION fn_enforce_location_hierarchy()
RETURNS TRIGGER AS $$
DECLARE
    parent_level VARCHAR(20);
BEGIN
    IF NEW.level = 'WARD' THEN
        SELECT level INTO parent_level FROM locations WHERE id = NEW.parent_id;
        IF parent_level IS DISTINCT FROM 'PROVINCE' THEN
            RAISE EXCEPTION 'WARD location must reference a PROVINCE parent (parent_id=%)', NEW.parent_id;
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.level = 'PROVINCE' AND NEW.level = 'WARD' THEN
        IF EXISTS (SELECT 1 FROM locations WHERE parent_id = OLD.id) THEN
            RAISE EXCEPTION 'Cannot downgrade PROVINCE (id=%) that still has child WARDs', OLD.id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_locations_enforce_hierarchy ON locations;
CREATE TRIGGER trg_locations_enforce_hierarchy
    BEFORE INSERT OR UPDATE ON locations
    FOR EACH ROW
    EXECUTE FUNCTION fn_enforce_location_hierarchy();

CREATE TABLE IF NOT EXISTS user_addresses (
    id UUID PRIMARY KEY,
    owner_profile_id VARCHAR(255) NOT NULL,
    label VARCHAR(100) NOT NULL,
    recipient_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(50) NOT NULL,
    address_line VARCHAR(500) NOT NULL,
    location_id INTEGER NOT NULL REFERENCES locations(id),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    is_default_shipping BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_user_address_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (
            latitude IS NOT NULL
            AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_user_addresses_owner_active
    ON user_addresses (owner_profile_id, is_active);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_default_shipping
    ON user_addresses (owner_profile_id)
    WHERE is_default_shipping = TRUE AND is_active = TRUE;

CREATE TABLE IF NOT EXISTS households (
    id UUID PRIMARY KEY,
    owner_profile_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    timezone VARCHAR(255) NOT NULL,
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE households
    ADD COLUMN IF NOT EXISTS default_service_address_id UUID REFERENCES user_addresses(id);
