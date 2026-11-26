-- Feature 002-device-foundation: Device safe archive, Care History indexing, Maintenance controls & default v0.1 templates
-- Forward-only additive migration (Constitution Principle VII)

ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS origin_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

UPDATE devices
SET origin_source = 'STORE_ORDER'
WHERE unit_key IS NOT NULL AND origin_source = 'MANUAL';

UPDATE devices
SET status = 'ACTIVE'
WHERE status IS NULL OR status NOT IN ('ACTIVE', 'ARCHIVED');

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_devices_status') THEN
        ALTER TABLE devices ADD CONSTRAINT chk_devices_status CHECK (status IN ('ACTIVE', 'ARCHIVED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_devices_origin_source') THEN
        ALTER TABLE devices ADD CONSTRAINT chk_devices_origin_source CHECK (origin_source IN ('MANUAL', 'STORE_ORDER'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_devices_owner_status_created
    ON devices (owner_profile_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_care_records_device_occurred
    ON care_records (device_id, occurred_at DESC);

ALTER TABLE maintenance_policies
    ALTER COLUMN model DROP NOT NULL;

UPDATE maintenance_policies
SET status = 'APPROVED'
WHERE status IS NULL OR status NOT IN ('DRAFT', 'APPROVED', 'ARCHIVED');

UPDATE maintenance_policies
SET cadence_months = 6
WHERE cadence_months IS NULL OR cadence_months < 1 OR cadence_months > 60;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_maintenance_policies_status') THEN
        ALTER TABLE maintenance_policies ADD CONSTRAINT chk_maintenance_policies_status CHECK (status IN ('DRAFT', 'APPROVED', 'ARCHIVED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_maintenance_policies_cadence') THEN
        ALTER TABLE maintenance_policies ADD CONSTRAINT chk_maintenance_policies_cadence CHECK (cadence_months BETWEEN 1 AND 60);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_maintenance_policy_cat_model_ver
    ON maintenance_policies (category, COALESCE(model, '__DEFAULT__'), policy_version);

ALTER TABLE maintenance_schedules
    ADD COLUMN IF NOT EXISTS reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS last_skipped_at TIMESTAMPTZ;

UPDATE maintenance_schedules
SET cadence_months = 6
WHERE cadence_months IS NULL OR cadence_months < 1 OR cadence_months > 60;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_maintenance_schedules_cadence') THEN
        ALTER TABLE maintenance_schedules ADD CONSTRAINT chk_maintenance_schedules_cadence CHECK (cadence_months BETWEEN 1 AND 60);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_maintenance_schedules_due_active
    ON maintenance_schedules (due_on, status, reminder_enabled)
    WHERE status = 'SCHEDULED' AND reminder_enabled = TRUE;

CREATE INDEX IF NOT EXISTS ix_maintenance_schedules_device_due
    ON maintenance_schedules (device_id, due_on ASC);

-- Seed baseline APPROVED v0.1 category maintenance policies (idempotent)
INSERT INTO maintenance_policies (id, device_id, owner_profile_id, category, model, title, cadence_months, policy_version, status, consent_granted, first_due_on, created_at, updated_at)
VALUES
    ('11111111-2222-4333-8444-000000000001', NULL, NULL, 'AIR_CONDITIONER', NULL, 'Vệ sinh & bảo dưỡng điều hòa định kỳ', 6, 1, 'APPROVED', TRUE, NULL, NOW(), NOW()),
    ('11111111-2222-4333-8444-000000000002', NULL, NULL, 'WASHING_MACHINE', NULL, 'Vệ sinh lồng giặt & kiểm tra ống xả', 6, 1, 'APPROVED', TRUE, NULL, NOW(), NOW()),
    ('11111111-2222-4333-8444-000000000003', NULL, NULL, 'WATER_PURIFIER', NULL, 'Thay lõi lọc & kiểm tra chất lượng nước', 6, 1, 'APPROVED', TRUE, NULL, NOW(), NOW()),
    ('11111111-2222-4333-8444-000000000004', NULL, NULL, 'WATER_HEATER', NULL, 'Vệ sinh bình nóng lạnh & kiểm tra chống giật ELCB', 12, 1, 'APPROVED', TRUE, NULL, NOW(), NOW()),
    ('11111111-2222-4333-8444-000000000005', NULL, NULL, 'REFRIGERATOR', NULL, 'Vệ sinh dàn ngưng & kiểm tra ron tủ lạnh', 12, 1, 'APPROVED', TRUE, NULL, NOW(), NOW())
ON CONFLICT DO NOTHING;
