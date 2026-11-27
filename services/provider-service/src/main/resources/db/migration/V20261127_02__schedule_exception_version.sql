-- Add optimistic concurrency version column to schedule_exceptions (BR-SCH-065)
ALTER TABLE schedule_exceptions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
