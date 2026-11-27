-- Phase 7: Technician leave and shift-change request workflow
CREATE TABLE IF NOT EXISTS technician_schedule_change_requests (
    id UUID PRIMARY KEY,
    technician_id UUID NOT NULL REFERENCES technicians(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL,
    request_type VARCHAR(24) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    requested_start_time TIME,
    requested_end_time TIME,
    reason VARCHAR(500),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewed_by VARCHAR(255),
    review_note VARCHAR(500),
    reviewed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_sched_req_type CHECK (request_type IN ('LEAVE', 'SHIFT_CHANGE', 'EXTRA_HOURS')),
    CONSTRAINT chk_sched_req_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_sched_req_dates CHECK (to_date >= from_date)
);

CREATE INDEX IF NOT EXISTS idx_sched_req_org_status
    ON technician_schedule_change_requests (organization_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sched_req_tech_status
    ON technician_schedule_change_requests (technician_id, status, created_at DESC);
