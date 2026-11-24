-- Provider Onboarding, Governance & Service Catalog (v0.1: D-04, D-07, D-12, D-13, D-22, D-25, D-29)

ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS resubmit_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS identity_evidence_refs TEXT,
    ADD COLUMN IF NOT EXISTS identity_verified_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS identity_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_legal_entity_upgrade VARCHAR(30);

-- Enforce 1:1 active Technician <-> Organization in v0.1 (D-29)
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_member_active_technician
    ON organization_members (user_id)
    WHERE role = 'ORG_TECHNICIAN' AND status = 'ACTIVE';

-- Service Offering governance & warranty bounds (D-04, D-12, D-22)
ALTER TABLE service_offerings
    ADD COLUMN IF NOT EXISTS inspection_fee_policy VARCHAR(30) NOT NULL DEFAULT 'NO_FEE',
    ADD COLUMN IF NOT EXISTS inspection_fee_audit_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS workmanship_duration_days INTEGER NOT NULL DEFAULT 7;

-- Category qualification verification for high-risk categories (D-07, D-25)
CREATE TABLE IF NOT EXISTS provider_category_qualifications (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    category VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    evidence_refs TEXT NOT NULL DEFAULT '[]',
    reason VARCHAR(1000),
    verified_by VARCHAR(255),
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_category_qualification
    ON provider_category_qualifications (provider_id, category);

-- Immutable organization enforcement audit trail (D-07, D-25, BR-PLAT-02)
CREATE TABLE IF NOT EXISTS organization_enforcements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    evidence_refs TEXT,
    safety_intervention_flag BOOLEAN NOT NULL DEFAULT FALSE,
    issued_by_admin VARCHAR(255) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_org_enforcements_org_issued
    ON organization_enforcements (organization_id, issued_at DESC);

-- Organization appeals with 14d window, 5-business-day SLA, two-Admin BANNED reversal (D-25, D-29)
CREATE TABLE IF NOT EXISTS organization_appeals (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    enforcement_id UUID NOT NULL REFERENCES organization_enforcements(id),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    appeal_reason VARCHAR(2000) NOT NULL,
    evidence_refs TEXT,
    submitted_by VARCHAR(255) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sla_due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    reviewed_by_admin VARCHAR(255),
    review_note VARCHAR(1000),
    reviewed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_org_appeals_org_status
    ON organization_appeals (organization_id, status);

-- Admin-configurable platform governance thresholds (D-22, D-29)
CREATE TABLE IF NOT EXISTS platform_governance_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO platform_governance_settings (setting_key, setting_value, updated_by, updated_at)
VALUES
    ('inspection_fee_soft_warning_vnd', '500000', 'SYSTEM_SEED', NOW()),
    ('organization_max_resubmit_count', '3', 'SYSTEM_SEED', NOW())
ON CONFLICT (setting_key) DO NOTHING;

-- Immutable user suspension audit trail (D-29)
CREATE TABLE IF NOT EXISTS user_enforcement_audits (
    id UUID PRIMARY KEY,
    target_user_id UUID NOT NULL,
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    issued_by_admin VARCHAR(255) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_enforcement_audits_target
    ON user_enforcement_audits (target_user_id, issued_at DESC);
