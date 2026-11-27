-- Baseline idempotent schema for booking-service (enables spring.flyway.enabled=true + ddl-auto=validate)
CREATE TABLE IF NOT EXISTS bookings (
    id UUID PRIMARY KEY,
    requester_profile_id VARCHAR(255) NOT NULL,
    requester_name VARCHAR(255),
    service_id UUID NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    provider_id UUID,
    preferred_provider_id UUID,
    booking_type VARCHAR(255) NOT NULL DEFAULT 'STANDARD',
    warranty_id UUID,
    device_id UUID,
    device_type VARCHAR(255),
    assigned_technician_id UUID,
    assigned_technician_profile_id VARCHAR(255),
    assigned_technician_name VARCHAR(255),
    preferred_start TIMESTAMPTZ NOT NULL,
    preferred_end TIMESTAMPTZ NOT NULL,
    address VARCHAR(255) NOT NULL,
    notes VARCHAR(1000),
    status VARCHAR(255) NOT NULL,
    allocation_status VARCHAR(255) NOT NULL,
    active_allocation_operation_id UUID,
    quoted_price BIGINT NOT NULL DEFAULT 0,
    accepted_at TIMESTAMPTZ,
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_history (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    from_status VARCHAR(255),
    to_status VARCHAR(255) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    actor_name VARCHAR(255),
    old_technician_id UUID,
    new_technician_id UUID,
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_work_updates (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    technician_profile_id VARCHAR(255) NOT NULL,
    kind VARCHAR(255) NOT NULL,
    note VARCHAR(1500),
    extra_cost BIGINT,
    extra_cost_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    extra_cost_approved_at TIMESTAMPTZ,
    extra_cost_approved_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_work_evidence (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    work_update_id UUID,
    uploader_profile_id VARCHAR(255) NOT NULL,
    bucket VARCHAR(120) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload_json VARCHAR(4000) NOT NULL,
    status VARCHAR(255) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_assignment_attempts (
    operation_id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    service_id UUID NOT NULL,
    device_type VARCHAR(255),
    preferred_start TIMESTAMPTZ NOT NULL,
    preferred_end TIMESTAMPTZ NOT NULL,
    provider_id UUID,
    strict_provider_match BOOLEAN NOT NULL DEFAULT FALSE,
    technician_id UUID,
    attempt_type VARCHAR(32) NOT NULL DEFAULT 'ALLOCATE',
    previous_technician_id UUID,
    previous_technician_profile_id VARCHAR(255),
    previous_technician_name VARCHAR(255),
    previous_operation_id UUID,
    reason VARCHAR(500),
    status VARCHAR(255) NOT NULL,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_allocation_saga (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL UNIQUE,
    intent_id VARCHAR(255) NOT NULL UNIQUE,
    saga_status VARCHAR(255) NOT NULL,
    current_step VARCHAR(255) NOT NULL,
    provider_id UUID,
    assigned_technician_id UUID,
    failure_reason VARCHAR(500),
    compensation_reason VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
