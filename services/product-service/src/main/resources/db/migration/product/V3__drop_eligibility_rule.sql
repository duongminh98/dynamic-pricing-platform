-- Drop the eligibility_rule table (R26 automatic rules removed from scope).
-- Kept migration history clean: V1 created the table, V3 drops it.
DROP TABLE IF EXISTS eligibility_rule;
