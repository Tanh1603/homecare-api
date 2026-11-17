DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM technician_allocations
        WHERE status = 'ALLOCATED'
        GROUP BY booking_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate active allocations detected';
    END IF;
END $$;

ALTER TABLE technician_allocations
    ADD COLUMN IF NOT EXISTS operation_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uq_technician_allocations_active_booking
    ON technician_allocations (booking_id)
    WHERE status = 'ALLOCATED';
