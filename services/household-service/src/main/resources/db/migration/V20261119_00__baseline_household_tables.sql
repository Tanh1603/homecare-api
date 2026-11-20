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

CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    owner_profile_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    device_type VARCHAR(255) NOT NULL,
    brand VARCHAR(255) NOT NULL,
    model VARCHAR(255) NOT NULL,
    serial_number VARCHAR(255),
    purchase_date DATE,
    status VARCHAR(255) NOT NULL,
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS warranties (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    owner_profile_id VARCHAR(255) NOT NULL,
    owner_name VARCHAR(255),
    organization_id UUID,
    order_id UUID,
    order_line_id UUID,
    product_id UUID,
    source VARCHAR(255) NOT NULL DEFAULT 'MANUAL',
    provider_name VARCHAR(255) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    notes VARCHAR(1000),
    document_url VARCHAR(255),
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS maintenance_schedules (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    owner_profile_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    cadence_months INTEGER,
    due_on DATE NOT NULL,
    status VARCHAR(255) NOT NULL,
    notified_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS care_records (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    owner_profile_id VARCHAR(255) NOT NULL,
    kind VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    title VARCHAR(255),
    notes VARCHAR(2000),
    booking_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS household_outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload_json VARCHAR(4000) NOT NULL,
    status VARCHAR(255) NOT NULL,
    retry_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
