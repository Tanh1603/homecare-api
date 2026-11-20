CREATE TABLE IF NOT EXISTS maintenance_policies (
    id UUID PRIMARY KEY,
    device_id UUID,
    owner_profile_id VARCHAR(255),
    category VARCHAR(100) NOT NULL,
    model VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    cadence_months INTEGER NOT NULL,
    policy_version INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
    first_due_on DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE maintenance_schedules
    ADD COLUMN IF NOT EXISTS policy_version INTEGER,
    ADD COLUMN IF NOT EXISTS last_serviced_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_maintenance_schedule_device_policy
    ON maintenance_schedules (device_id, policy_version);

CREATE UNIQUE INDEX IF NOT EXISTS uq_care_record_booking_kind
    ON care_records (booking_id, kind);

CREATE INDEX IF NOT EXISTS ix_maintenance_policy_device_status
    ON maintenance_policies (device_id, status, consent_granted);
