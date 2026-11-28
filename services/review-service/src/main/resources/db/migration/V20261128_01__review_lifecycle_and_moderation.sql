ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS edit_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS hidden_reason VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS hidden_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS restored_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS restored_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS restore_note VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reviews_target_type') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_reviews_target_type
            CHECK (target_type IN ('SERVICE_BOOKING', 'PRODUCT_ORDER_LINE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reviews_rating_range') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_reviews_rating_range
            CHECK (rating >= 1 AND rating <= 5);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reviews_status') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_reviews_status
            CHECK (status IN ('PUBLISHED', 'DELETED', 'HIDDEN'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reviews_edit_count') THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_reviews_edit_count
            CHECK (edit_count >= 0 AND edit_count <= 1);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_reviews_provider_public
    ON reviews (target_type, target_id, created_at DESC)
    WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS idx_reviews_admin_status
    ON reviews (target_type, status, created_at DESC);
