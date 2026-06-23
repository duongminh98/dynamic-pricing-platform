-- V3__add_first_failed_at.sql
-- Track the timestamp of the first failed login in the current 15-minute window
-- to implement R1.7: 5 failed attempts within 15 minutes triggers a 15-minute lock.

ALTER TABLE account ADD COLUMN first_failed_at TIMESTAMPTZ;
