ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS installation_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS installation_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS installation_booking_id UUID,
    ADD COLUMN IF NOT EXISTS installation_evidence_refs VARCHAR(2000);

ALTER TABLE warranties
    ADD COLUMN IF NOT EXISTS manufacturer_registration_status VARCHAR(40) NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN IF NOT EXISTS trigger_evidence_ref VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS coverage_months INTEGER;

UPDATE warranties
SET manufacturer_registration_status = 'NOT_VERIFIED'
WHERE manufacturer_registration_status IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_warranty_manufacturer_registration_status'
    ) THEN
        ALTER TABLE warranties
            ADD CONSTRAINT chk_warranty_manufacturer_registration_status
            CHECK (manufacturer_registration_status IN ('NOT_VERIFIED', 'EXTERNAL_REQUIRED', 'VERIFIED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_warranty_order_line_device
    ON warranties (order_line_id, device_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_installation_care_record_booking
    ON care_records (booking_id)
    WHERE booking_id IS NOT NULL AND kind = 'INSTALLATION';
