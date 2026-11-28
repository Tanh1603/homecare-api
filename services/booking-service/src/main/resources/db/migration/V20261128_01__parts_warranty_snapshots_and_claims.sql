-- Forward-only migration for 006-warranty-and-claims (D-05, D-12, D-14, D-18, D-19, D-24, D-28, D-30)

CREATE TABLE IF NOT EXISTS booking_parts_requests (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    provider_id UUID NOT NULL,
    requested_by_profile_id VARCHAR(255) NOT NULL,
    part_name VARCHAR(255) NOT NULL,
    part_code VARCHAR(120),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    part_source VARCHAR(32) NOT NULL CHECK (part_source IN ('PROVIDER_INVENTORY','PROVIDER_SUPPLIER','EXTERNAL_SUPPLIER','CUSTOMER_SUPPLIED')),
    supplier_name VARCHAR(255),
    cost_price BIGINT CHECK (cost_price IS NULL OR cost_price >= 0),
    unit_price BIGINT CHECK (unit_price IS NULL OR unit_price >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_PROVIDER_APPROVAL','PROVIDER_APPROVED','PROVIDER_REJECTED','CUSTOMER_PART_REFUSED','USED','CANCELLED')),
    compatibility_note VARCHAR(1000),
    refusal_reason VARCHAR(1000),
    evidence_ref VARCHAR(1000),
    approved_by_profile_id VARCHAR(255),
    approved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_booking_parts_requests_booking ON booking_parts_requests (booking_id, created_at DESC);

CREATE TABLE IF NOT EXISTS booking_parts_usage (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    parts_request_id UUID REFERENCES booking_parts_requests(id),
    part_name VARCHAR(255) NOT NULL,
    part_code VARCHAR(120),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    part_source VARCHAR(32) NOT NULL CHECK (part_source IN ('PROVIDER_INVENTORY','PROVIDER_SUPPLIER','EXTERNAL_SUPPLIER','CUSTOMER_SUPPLIED')),
    unit_price BIGINT NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
    parts_warranty_status VARCHAR(24) NOT NULL CHECK (parts_warranty_status IN ('AVAILABLE','NONE','UNVERIFIED')),
    warranty_issuer VARCHAR(24) CHECK (warranty_issuer IS NULL OR warranty_issuer IN ('PROVIDER','SUPPLIER','MANUFACTURER')),
    warranty_duration_days INTEGER CHECK (warranty_duration_days IS NULL OR warranty_duration_days >= 0),
    warranty_terms VARCHAR(1000),
    evidence_ref VARCHAR(1000),
    purchase_proof_ref VARCHAR(1000),
    recorded_by_profile_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_booking_parts_usage_booking ON booking_parts_usage (booking_id, created_at ASC);

CREATE TABLE IF NOT EXISTS warranty_commitment_snapshots (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    quotation_id UUID,
    quotation_version INTEGER NOT NULL DEFAULT 0,
    service_category VARCHAR(64) NOT NULL,
    workmanship_duration_days INTEGER NOT NULL CHECK (workmanship_duration_days >= 0 AND workmanship_duration_days <= 90),
    committed_parts_json TEXT NOT NULL DEFAULT '[]',
    policy_summary VARCHAR(1000),
    committed_at TIMESTAMPTZ NOT NULL,
    committed_by_profile_id VARCHAR(255) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_commitment_snapshot_booking_version
    ON warranty_commitment_snapshots (booking_id, quotation_version);

CREATE TABLE IF NOT EXISTS warranty_activation_snapshots (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id),
    commitment_snapshot_id UUID REFERENCES warranty_commitment_snapshots(id),
    workmanship_duration_days INTEGER NOT NULL CHECK (workmanship_duration_days >= 0 AND workmanship_duration_days <= 90),
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    actual_parts_json TEXT NOT NULL DEFAULT '[]',
    excluded_scope_json TEXT NOT NULL DEFAULT '[]',
    material_discrepancy BOOLEAN NOT NULL DEFAULT FALSE,
    discrepancy_summary VARCHAR(1500),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS warranty_claims (
    id UUID PRIMARY KEY,
    booking_id UUID REFERENCES bookings(id),
    device_id UUID,
    household_warranty_id UUID,
    provider_id UUID,
    requester_profile_id VARCHAR(255) NOT NULL,
    warranty_category VARCHAR(32) NOT NULL CHECK (warranty_category IN ('PRODUCT_MANUFACTURER','WORKMANSHIP','PARTS')),
    warranty_issuer VARCHAR(24) NOT NULL CHECK (warranty_issuer IN ('MANUFACTURER','SELLER','PROVIDER','SUPPLIER')),
    reference_serial VARCHAR(160),
    issue_description VARCHAR(2000) NOT NULL,
    warranty_evidence_refs TEXT NOT NULL DEFAULT '[]',
    authorization_evidence_refs TEXT NOT NULL DEFAULT '[]',
    claim_status VARCHAR(32) NOT NULL CHECK (claim_status IN ('SUBMITTED','PENDING_VERIFICATION','ACCEPTED','REJECTED','CLOSED')),
    warranty_verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED' CHECK (warranty_verification_status IN ('UNVERIFIED','VERIFIED','REJECTED')),
    authorization_verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED' CHECK (authorization_verification_status IN ('UNVERIFIED','VERIFIED','REJECTED')),
    validity_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (validity_status IN ('ACTIVE','EXPIRED','REVOKED')),
    reported_at TIMESTAMPTZ NOT NULL,
    provider_response_deadline_at TIMESTAMPTZ,
    provider_no_response BOOLEAN NOT NULL DEFAULT FALSE,
    rework_booking_id UUID REFERENCES bookings(id),
    verified_by VARCHAR(255),
    verified_at TIMESTAMPTZ,
    rejection_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_warranty_claims_open_workmanship
    ON warranty_claims (booking_id)
    WHERE warranty_category = 'WORKMANSHIP' AND claim_status IN ('SUBMITTED', 'PENDING_VERIFICATION');

CREATE INDEX IF NOT EXISTS idx_warranty_claims_sla_due
    ON warranty_claims (provider_response_deadline_at)
    WHERE warranty_category = 'WORKMANSHIP' AND claim_status = 'SUBMITTED' AND provider_no_response = FALSE;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS rework_source_claim_id UUID,
    ADD COLUMN IF NOT EXISTS warranty_rework BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_bookings_rework_source_claim ON bookings (rework_source_claim_id) WHERE rework_source_claim_id IS NOT NULL;

ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS workmanship_duration_days INTEGER CHECK (workmanship_duration_days IS NULL OR (workmanship_duration_days >= 0 AND workmanship_duration_days <= 90));
