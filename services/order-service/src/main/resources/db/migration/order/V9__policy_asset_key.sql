ALTER TABLE policy ADD COLUMN asset_key VARCHAR(255);

CREATE INDEX idx_policy_customer_asset ON policy (customer_id, asset_key) WHERE asset_key IS NOT NULL;
