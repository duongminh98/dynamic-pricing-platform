-- V4__gender_english_values.sql
-- Replace Vietnamese gender values with English: nam->male, n?->female, khác->other

ALTER TABLE customer_profile DROP CONSTRAINT chk_profile_gender;

UPDATE customer_profile SET gender = 'male' WHERE gender = 'nam';
UPDATE customer_profile SET gender = 'female' WHERE gender = 'n?';
UPDATE customer_profile SET gender = 'other' WHERE gender = 'khác';

ALTER TABLE customer_profile ADD CONSTRAINT chk_profile_gender CHECK (gender IN ('male', 'female', 'other'));