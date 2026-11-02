--liquibase formatted sql
--changeset identity:001
CREATE TABLE user_profile (
    id uuid PRIMARY KEY,
    display_name varchar(120), phone varchar(32), address varchar(500),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE external_identity (
    profile_id uuid PRIMARY KEY REFERENCES user_profile(id),
    firebase_project_id varchar(128) NOT NULL,
    firebase_uid varchar(128) NOT NULL,
    UNIQUE (firebase_project_id, firebase_uid)
);
CREATE TABLE profile_role (
    profile_id uuid PRIMARY KEY REFERENCES user_profile(id),
    role varchar(32) NOT NULL CHECK (role IN ('HOUSEHOLD_USER','SERVICE_PROVIDER','TECHNICIAN','MERCHANT','SYSTEM_ADMIN'))
);
CREATE TABLE identity_idempotency (
    scope_hash varchar(64) PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES user_profile(id),
    response_body text NOT NULL,
    response_status integer NOT NULL CHECK (response_status IN (200,201)),
    committed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
-- No automatic destructive rollback; reviewed backup/expand-contract process applies.
