-- V5__add_full_name.sql
-- Store the customer's display name sourced from Keycloak (the JWT `name` claim,
-- i.e. firstName + lastName). Populated at JIT provisioning and kept in sync on login,
-- so the admin console can show the real identity instead of a derived gender·age label.

ALTER TABLE account ADD COLUMN full_name VARCHAR(254);
