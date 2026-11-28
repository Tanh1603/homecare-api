CREATE TABLE IF NOT EXISTS reviews (
    id UUID PRIMARY KEY,
    author_profile_id VARCHAR(255) NOT NULL,
    author_name VARCHAR(255),
    target_type VARCHAR(255) NOT NULL,
    source_id UUID NOT NULL UNIQUE,
    target_id UUID NOT NULL,
    rating INTEGER NOT NULL,
    comment VARCHAR(1500) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
