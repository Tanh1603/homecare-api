ALTER TABLE providers
    ADD COLUMN IF NOT EXISTS review_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rating_calculated_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_providers_rating_avg') THEN
        ALTER TABLE providers ADD CONSTRAINT chk_providers_rating_avg
            CHECK (rating_avg >= 0.0 AND rating_avg <= 5.0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_providers_review_count') THEN
        ALTER TABLE providers ADD CONSTRAINT chk_providers_review_count
            CHECK (review_count >= 0);
    END IF;
END $$;
