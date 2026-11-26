-- Feature 002-device-foundation: Preserve original acceptance timestamp & fast device usage lookup
-- Forward-only additive migration (Constitution Principle VII)

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_bookings_device_id
    ON bookings (device_id)
    WHERE device_id IS NOT NULL;
