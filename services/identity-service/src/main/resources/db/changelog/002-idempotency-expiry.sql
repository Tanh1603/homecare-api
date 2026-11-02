--liquibase formatted sql
--changeset identity:002
CREATE INDEX identity_idempotency_expiry_idx ON identity_idempotency(expires_at);
