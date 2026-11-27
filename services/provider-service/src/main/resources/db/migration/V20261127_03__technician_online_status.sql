-- Persist technician online/offline availability separately from Org-managed shifts
ALTER TABLE technicians
    ADD COLUMN IF NOT EXISTS is_online BOOLEAN NOT NULL DEFAULT TRUE;
