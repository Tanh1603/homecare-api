-- Governance flags on bookings when user/provider suspension triggers manual Admin review (D-25, D-29)

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS admin_review_flagged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS admin_review_reason VARCHAR(1000);
