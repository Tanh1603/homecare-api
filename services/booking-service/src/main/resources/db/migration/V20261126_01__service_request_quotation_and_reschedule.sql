-- Feature 3 (003-service-booking-core): Service Request, Quotation, Revised Quotation, Fallback, TTL & Reschedule
-- Forward-only additive migration (ddl-auto: validate)

ALTER TABLE service_requests
    ADD COLUMN IF NOT EXISTS inspection_fee_policy VARCHAR(30) NOT NULL DEFAULT 'NO_FEE',
    ADD COLUMN IF NOT EXISTS inspection_fee_warning_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS fallback_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rejected_provider_ids TEXT,
    ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS expiry_warning_notified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(500);

ALTER TABLE service_requests
    ADD CONSTRAINT chk_sr_inspection_fee_policy
        CHECK (inspection_fee_policy IN ('NO_FEE', 'CREDIT_FULL', 'NON_CREDITABLE')),
    ADD CONSTRAINT chk_sr_fallback_count
        CHECK (fallback_count >= 0 AND fallback_count <= 3);

CREATE INDEX IF NOT EXISTS ix_service_requests_ttl_scan
    ON service_requests (status, status_changed_at)
    WHERE status IN ('AWAITING_PROVIDER', 'AWAITING_ALTERNATIVE', 'QUOTATION_REJECTED');

ALTER TABLE quotations
    ALTER COLUMN service_request_id DROP NOT NULL;

ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS quote_kind VARCHAR(20) NOT NULL DEFAULT 'INITIAL',
    ADD COLUMN IF NOT EXISTS booking_id UUID,
    ADD COLUMN IF NOT EXISTS supersede_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS revision_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500);

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_kind
        CHECK (quote_kind IN ('INITIAL', 'REVISED')),
    ADD CONSTRAINT chk_quotation_parent_ref
        CHECK (service_request_id IS NOT NULL OR booking_id IS NOT NULL);

DROP INDEX IF EXISTS uq_quotation_request_version;
CREATE UNIQUE INDEX uq_quotation_request_version
    ON quotations (service_request_id, version)
    WHERE service_request_id IS NOT NULL AND quote_kind = 'INITIAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_booking_version
    ON quotations (booking_id, version)
    WHERE booking_id IS NOT NULL AND quote_kind = 'REVISED';

DROP INDEX IF EXISTS uq_quotation_accepted_per_request;
CREATE UNIQUE INDEX uq_quotation_accepted_per_request
    ON quotations (service_request_id)
    WHERE status = 'ACCEPTED' AND quote_kind = 'INITIAL' AND service_request_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_quotation_pending_revised_per_booking
    ON quotations (booking_id)
    WHERE status = 'SENT' AND quote_kind = 'REVISED' AND booking_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_quotations_sent_valid_until
    ON quotations (valid_until)
    WHERE status = 'SENT';

ALTER TABLE booking_work_updates
    ADD COLUMN IF NOT EXISTS revised_quotation_id UUID;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS inspection_fee_policy VARCHAR(30) NOT NULL DEFAULT 'NO_FEE',
    ADD COLUMN IF NOT EXISTS reschedule_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_reschedule_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_reschedule_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_reschedule_by VARCHAR(40),
    ADD COLUMN IF NOT EXISTS pending_reschedule_reason VARCHAR(500);

ALTER TABLE bookings
    ADD CONSTRAINT chk_booking_inspection_fee_policy
        CHECK (inspection_fee_policy IN ('NO_FEE', 'CREDIT_FULL', 'NON_CREDITABLE')),
    ADD CONSTRAINT chk_booking_reschedule_count
        CHECK (reschedule_count >= 0 AND reschedule_count <= 2);
