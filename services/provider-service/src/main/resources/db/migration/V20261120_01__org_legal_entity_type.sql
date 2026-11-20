ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS legal_entity_type VARCHAR(30);
