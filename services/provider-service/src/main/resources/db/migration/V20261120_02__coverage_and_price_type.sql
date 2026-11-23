ALTER TABLE service_offerings
    ADD COLUMN IF NOT EXISTS price_type VARCHAR(30) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN IF NOT EXISTS inspection_fee BIGINT,
    ADD COLUMN IF NOT EXISTS price_version INTEGER NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS service_coverage_areas (
    id UUID PRIMARY KEY,
    service_offering_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    location_code VARCHAR(32) NOT NULL,
    level VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_service_coverage_offering_code
    ON service_coverage_areas (service_offering_id, level, location_code);
