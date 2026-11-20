ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS service_request_id UUID,
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'REPAIR';

CREATE TABLE IF NOT EXISTS service_requests (
    id UUID PRIMARY KEY,
    requester_profile_id VARCHAR(255) NOT NULL,
    requester_name VARCHAR(255),
    request_type VARCHAR(40) NOT NULL,
    service_category VARCHAR(100) NOT NULL,
    service_offering_id UUID,
    price_type VARCHAR(30) NOT NULL DEFAULT 'FIXED',
    requires_onsite_survey BOOLEAN NOT NULL DEFAULT FALSE,
    inspection_fee BIGINT NOT NULL DEFAULT 0,
    policy_version VARCHAR(64) NOT NULL DEFAULT 'v1',
    device_id UUID,
    order_line_id UUID,
    warranty_id UUID,
    warranty_status VARCHAR(40),
    warranty_responsible_org_id UUID,
    assigned_provider_id UUID,
    fallback_reason VARCHAR(120),
    fallback_notified_at TIMESTAMPTZ,
    diagnostic_booking_id UUID,
    repair_booking_id UUID,
    accepted_quotation_id UUID,
    issue_description VARCHAR(2000),
    preferred_start TIMESTAMPTZ NOT NULL,
    preferred_end TIMESTAMPTZ NOT NULL,
    address VARCHAR(1000) NOT NULL,
    service_address_id UUID,
    recipient_name VARCHAR(255),
    phone_number VARCHAR(50),
    address_line VARCHAR(500),
    ward_code VARCHAR(32),
    ward_name VARCHAR(255),
    province_code VARCHAR(32),
    province_name VARCHAR(255),
    formatted_address VARCHAR(1000),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    status VARCHAR(50) NOT NULL,
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS quotations (
    id UUID PRIMARY KEY,
    service_request_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    version INTEGER NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'VND',
    items_total BIGINT NOT NULL,
    inspection_fee_paid BIGINT NOT NULL DEFAULT 0,
    inspection_fee_credit BIGINT NOT NULL DEFAULT 0,
    total_due_after_credit BIGINT NOT NULL,
    policy_version VARCHAR(64) NOT NULL DEFAULT 'v1',
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMPTZ,
    repair_booking_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_request_version
    ON quotations (service_request_id, version);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_accepted_per_request
    ON quotations (service_request_id)
    WHERE status = 'ACCEPTED';

CREATE TABLE IF NOT EXISTS quotation_items (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL,
    row_index INTEGER NOT NULL,
    item_type VARCHAR(32) NOT NULL,
    description VARCHAR(500) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price BIGINT NOT NULL,
    line_total BIGINT NOT NULL
);
