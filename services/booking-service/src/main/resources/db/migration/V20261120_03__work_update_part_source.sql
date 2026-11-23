ALTER TABLE booking_work_updates
    ADD COLUMN IF NOT EXISTS part_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS part_order_line_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_work_update_part_source'
    ) THEN
        ALTER TABLE booking_work_updates
            ADD CONSTRAINT chk_work_update_part_source
            CHECK (part_source IS NULL OR part_source IN ('CUSTOMER_SUPPLIED', 'PROVIDER_STOCK', 'HOMECARE_ORDER'));
    END IF;
END $$;
