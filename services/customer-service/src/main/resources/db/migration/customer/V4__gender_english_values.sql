-- V4: Replace Vietnamese gender values with English: nam->male, nu->female, khac->other

ALTER TABLE customer_profile DROP CONSTRAINT IF EXISTS chk_profile_gender;

UPDATE customer_profile SET gender = 'male' WHERE gender = 'nam';
UPDATE customer_profile SET gender = 'female' WHERE gender = 'nu';
UPDATE customer_profile SET gender = 'other' WHERE gender = 'khac';

ALTER TABLE customer_profile ADD CONSTRAINT chk_profile_gender CHECK (gender IN ('male', 'female', 'other'));
