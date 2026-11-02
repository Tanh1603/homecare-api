-- Operator-only synthetic fixtures; run after migration with psql variable firebase_project_id.
-- Never use against production or map these records to real people. No Firebase passwords are stored.
BEGIN;
DO $$ BEGIN
    IF current_database() !~ '^hc_test_[a-z0-9]+_identity$' THEN
        RAISE EXCEPTION 'Synthetic role seed requires a dedicated Identity test database';
    END IF;
END $$;
CREATE TEMP TABLE identity_seed_fixture (id uuid, uid text, role text) ON COMMIT DROP;
INSERT INTO identity_seed_fixture VALUES
('019a4310-0000-7000-8000-000000000001','fixture-household','HOUSEHOLD_USER'),
('019a4310-0000-7000-8000-000000000002','fixture-provider','SERVICE_PROVIDER'),
('019a4310-0000-7000-8000-000000000003','fixture-technician','TECHNICIAN'),
('019a4310-0000-7000-8000-000000000004','fixture-merchant','MERCHANT'),
('019a4310-0000-7000-8000-000000000005','fixture-admin','SYSTEM_ADMIN');
-- Deliberately fail on existing records; do not silently grant/change roles on a rerun.
INSERT INTO user_profile(id,status,created_at,updated_at,version)
SELECT id,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0 FROM identity_seed_fixture;
INSERT INTO external_identity(profile_id,firebase_project_id,firebase_uid)
SELECT id, :'firebase_project_id', uid FROM identity_seed_fixture;
INSERT INTO profile_role(profile_id,role) SELECT id,role FROM identity_seed_fixture;
COMMIT;
