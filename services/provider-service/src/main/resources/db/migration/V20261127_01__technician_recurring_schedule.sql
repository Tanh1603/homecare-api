-- Technician recurring weekly schedules, date-specific exceptions, audit log, and schedule_windows provenance + non-overlap constraints (D-32, BR-SCH-001..060)
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE IF NOT EXISTS weekly_schedule_rules (
    id UUID PRIMARY KEY,
    technician_id UUID NOT NULL REFERENCES technicians(id) ON DELETE CASCADE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    effective_from DATE NOT NULL,
    effective_until DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_weekly_schedule_rules_dates
        CHECK (effective_until IS NULL OR effective_until >= effective_from),
    CONSTRAINT ex_weekly_schedule_rules_non_overlapping
        EXCLUDE USING gist (
            technician_id WITH =,
            daterange(effective_from, effective_until, '[]') WITH &&
        )
);

CREATE INDEX IF NOT EXISTS idx_weekly_schedule_rules_tech_dates
    ON weekly_schedule_rules (technician_id, effective_from, effective_until);

CREATE TABLE IF NOT EXISTS weekly_schedule_rule_intervals (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES weekly_schedule_rules(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL CHECK (end_time > start_time)
);

CREATE INDEX IF NOT EXISTS idx_weekly_rule_intervals_rule_day
    ON weekly_schedule_rule_intervals (rule_id, day_of_week, start_time);

CREATE TABLE IF NOT EXISTS schedule_exceptions (
    id UUID PRIMARY KEY,
    technician_id UUID NOT NULL REFERENCES technicians(id) ON DELETE CASCADE,
    local_date DATE NOT NULL,
    exception_type VARCHAR(32) NOT NULL
        CHECK (exception_type IN ('UNAVAILABLE', 'CUSTOM_HOURS', 'EXTRA_HOURS')),
    start_time TIME,
    end_time TIME,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedule_exceptions_times
        CHECK (
            (start_time IS NULL AND end_time IS NULL AND exception_type = 'UNAVAILABLE')
            OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
        )
);

CREATE INDEX IF NOT EXISTS idx_schedule_exceptions_tech_date
    ON schedule_exceptions (technician_id, local_date);

CREATE TABLE IF NOT EXISTS technician_schedule_audits (
    id UUID PRIMARY KEY,
    technician_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    scope VARCHAR(32),
    effective_from DATE,
    effective_until DATE,
    details_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tech_schedule_audits_tech_created
    ON technician_schedule_audits (technician_id, created_at DESC);

ALTER TABLE schedule_windows
    ADD COLUMN IF NOT EXISTS source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS origin_rule_id UUID REFERENCES weekly_schedule_rules(id) ON DELETE SET NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_schedule_windows_source'
          AND conrelid = 'schedule_windows'::regclass
    ) THEN
        ALTER TABLE schedule_windows
            ADD CONSTRAINT chk_schedule_windows_source
            CHECK (source IN ('MANUAL', 'RECURRING'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ex_schedule_windows_non_overlapping'
          AND conrelid = 'schedule_windows'::regclass
    ) THEN
        ALTER TABLE schedule_windows
            ADD CONSTRAINT ex_schedule_windows_non_overlapping
            EXCLUDE USING gist (
                technician_id WITH =,
                tstzrange(starts_at, ends_at, '[)') WITH &&
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_schedule_windows_tech_starts_ends
    ON schedule_windows (technician_id, starts_at, ends_at);
