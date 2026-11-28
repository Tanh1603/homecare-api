ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS notification_failed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS notification_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_notification_retry_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_bookings_notification_retry_count') THEN
        ALTER TABLE bookings ADD CONSTRAINT chk_bookings_notification_retry_count
            CHECK (notification_retry_count >= 0);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bookings_completion_unnotified
    ON bookings (completion_submitted_at)
    WHERE status = 'COMPLETED_PENDING_ACCEPTANCE'
      AND completion_notified_at IS NULL
      AND notification_failed = FALSE;

CREATE INDEX IF NOT EXISTS idx_bookings_notification_failed
    ON bookings (notification_failed_at DESC)
    WHERE status = 'COMPLETED_PENDING_ACCEPTANCE'
      AND notification_failed = TRUE;
