CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    recipient_id VARCHAR(255) NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body VARCHAR(1500) NOT NULL,
    deeplink VARCHAR(500),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS processed_events (
    id UUID PRIMARY KEY,
    consumer_name VARCHAR(128) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_processed_events_consumer_event UNIQUE (consumer_name, event_id)
);

CREATE TABLE IF NOT EXISTS notification_devices (
    id UUID PRIMARY KEY,
    recipient_id VARCHAR(255) NOT NULL,
    platform VARCHAR(255) NOT NULL,
    push_token VARCHAR(512) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created
    ON notifications (recipient_id, created_at DESC);
