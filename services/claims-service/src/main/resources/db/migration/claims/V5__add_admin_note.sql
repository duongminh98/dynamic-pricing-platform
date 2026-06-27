-- V5__add_admin_note.sql
-- Add admin_note column for reject reason, approve note, misrepresentation reasons.

ALTER TABLE claim ADD COLUMN admin_note VARCHAR(2000);
