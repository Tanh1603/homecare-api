-- Forward-only migration for 006-warranty-and-claims (D-05, D-14, D-18)

ALTER TABLE warranties
    ADD COLUMN IF NOT EXISTS warranty_category VARCHAR(32) NOT NULL DEFAULT 'PRODUCT_MANUFACTURER'
        CHECK (warranty_category IN ('PRODUCT_MANUFACTURER','WORKMANSHIP','PARTS')),
    ADD COLUMN IF NOT EXISTS warranty_issuer VARCHAR(24) NOT NULL DEFAULT 'MANUFACTURER'
        CHECK (warranty_issuer IN ('MANUFACTURER','SELLER','PROVIDER','SUPPLIER')),
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (verification_status IN ('UNVERIFIED','VERIFIED','REJECTED')),
    ADD COLUMN IF NOT EXISTS validity_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (validity_status IN ('ACTIVE','EXPIRED','REVOKED')),
    ADD COLUMN IF NOT EXISTS authorization_evidence_ref VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS verified_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS verification_note VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE warranties
SET verification_status = 'VERIFIED'
WHERE manufacturer_registration_status = 'VERIFIED' AND verification_status = 'UNVERIFIED';

CREATE INDEX IF NOT EXISTS idx_warranties_verification_status ON warranties (verification_status, created_at DESC);
