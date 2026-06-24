-- Enforce "exactly one current rate version" at the database level (R3.5).
-- The original idx_rate_version_current was a non-unique partial index; replace it
-- with a partial UNIQUE index so the DB rejects a second is_current = TRUE row.
DROP INDEX IF EXISTS idx_rate_version_current;

CREATE UNIQUE INDEX idx_rate_version_current
    ON rate_version (is_current)
    WHERE is_current = TRUE;
