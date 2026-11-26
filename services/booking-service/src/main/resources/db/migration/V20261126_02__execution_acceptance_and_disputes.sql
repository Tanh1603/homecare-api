-- Feature 4 (004-execution-acceptance-dispute): Field Execution, Conditional Acceptance, Timers & Disputes
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS arrived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS waiting_for_parts_since TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS waiting_for_parts_reminder_stage INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS completion_submitted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completion_summary VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS completion_notified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS notification_failed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS auto_accept_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acceptance_reminder_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acceptance_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS revision_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS revision_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS customer_no_show_evidence_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_bookings_acceptance_source') THEN
        ALTER TABLE bookings ADD CONSTRAINT chk_bookings_acceptance_source
            CHECK (acceptance_source IS NULL OR acceptance_source IN ('CUSTOMER', 'SYSTEM_TIMEOUT'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_bookings_revision_count') THEN
        ALTER TABLE bookings ADD CONSTRAINT chk_bookings_revision_count
            CHECK (revision_count >= 0 AND revision_count <= 2);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_bookings_waiting_parts_stage') THEN
        ALTER TABLE bookings ADD CONSTRAINT chk_bookings_waiting_parts_stage
            CHECK (waiting_for_parts_reminder_stage IN (0, 7, 12, 14));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bookings_auto_accept_due
    ON bookings (auto_accept_at)
    WHERE status = 'COMPLETED_PENDING_ACCEPTANCE' AND auto_accept_at IS NOT NULL AND notification_failed = FALSE;

CREATE INDEX IF NOT EXISTS idx_bookings_revision_timeout
    ON bookings (revision_requested_at)
    WHERE status = 'REVISION_REQUESTED';

CREATE INDEX IF NOT EXISTS idx_bookings_waiting_parts_ttl
    ON bookings (waiting_for_parts_since)
    WHERE status = 'WAITING_FOR_PARTS';

CREATE TABLE IF NOT EXISTS disputes (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    opened_by_profile_id VARCHAR(255) NOT NULL,
    provider_id UUID NOT NULL,
    opened_stage VARCHAR(32) NOT NULL CHECK (opened_stage IN ('PRE_ACCEPTANCE', 'POST_ACCEPTANCE')),
    category VARCHAR(64) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(40) NOT NULL,
    provider_response VARCHAR(2000),
    provider_responded_at TIMESTAMPTZ,
    provider_no_response BOOLEAN NOT NULL DEFAULT FALSE,
    sla_due_at TIMESTAMPTZ,
    sla_reminder_stage INTEGER NOT NULL DEFAULT 0,
    resolution_proposal VARCHAR(2000),
    resolution_outcome VARCHAR(40),
    resolved_by_admin_id VARCHAR(255),
    resolution_note VARCHAR(2000),
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_disputes_active_per_booking
    ON disputes (booking_id)
    WHERE status NOT IN ('RESOLVED', 'ESCALATED', 'CLOSED');

CREATE INDEX IF NOT EXISTS idx_disputes_sla_due
    ON disputes (sla_due_at)
    WHERE status IN ('AWAITING_PROVIDER_RESPONSE', 'AWAITING_CUSTOMER_EVIDENCE', 'RESOLUTION_PROPOSED');

CREATE TABLE IF NOT EXISTS dispute_evidence (
    id UUID PRIMARY KEY,
    dispute_id UUID NOT NULL REFERENCES disputes(id),
    uploaded_by_profile_id VARCHAR(255) NOT NULL,
    uploader_role VARCHAR(32) NOT NULL CHECK (uploader_role IN ('CUSTOMER', 'PROVIDER', 'ADMIN')),
    work_evidence_id UUID,
    bucket VARCHAR(120) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL
);
