-- Seed 16 products across 6 lines (R3.9)
INSERT INTO product (product_id, category, product_name, coverage_amount_vnd, deductible_vnd, base_premium_vnd, admin_fee_vnd, active) VALUES
  ('HEALTH_BASIC',          'health',    'Health Basic',          100000000,       0, 2200000,  500000,  TRUE),
  ('HEALTH_STANDARD',      'health',    'Health Standard',       300000000,       0, 4800000,  500000,  TRUE),
  ('HEALTH_PREMIUM',       'health',    'Health Premium',        700000000,       0, 9500000,  700000,  TRUE),
  ('MOTORBIKE_TPL',        'motorbike', 'Motorbike TPL',         150000000,       0, 60000,    20000,   TRUE),
  ('MOTORBIKE_THEFT_FIRE', 'motorbike', 'Motorbike Theft/Fire',  80000000,   500000, 350000,   40000,   TRUE),
  ('MOTORBIKE_COMPREHENSIVE','motorbike','Motorbike Comprehensive',150000000,  500000, 650000,   60000,   TRUE),
  ('CAR_TPL',              'car',       'Car TPL',               300000000,       0, 480000,   80000,   TRUE),
  ('CAR_PHYSICAL_BASIC',   'car',       'Car Physical Basic',   300000000, 2000000, 5000000,  600000,  TRUE),
  ('CAR_PHYSICAL_PREMIUM', 'car',       'Car Physical Premium', 900000000, 1000000,12000000, 800000,  TRUE),
  ('TRAVEL_DOMESTIC',      'travel',    'Travel Domestic',        50000000,       0, 80000,    10000,   TRUE),
  ('TRAVEL_INTERNATIONAL', 'travel',    'Travel International', 1000000000,       0, 650000,   50000,   TRUE),
  ('ACCIDENT_BASIC',       'accident',  'Accident Basic',        100000000,       0, 180000,   30000,   TRUE),
  ('ACCIDENT_STANDARD',    'accident',  'Accident Standard',    200000000,       0, 350000,   40000,   TRUE),
  ('ACCIDENT_PREMIUM',     'accident',  'Accident Premium',     500000000,       0, 850000,   70000,   TRUE),
  ('HOME_FIRE_FLOOD_BASIC','home',      'Home Fire/Flood Basic', 500000000, 2000000, 1800000,  200000,  TRUE),
  ('HOME_FIRE_FLOOD_PREMIUM','home',    'Home Fire/Flood Premium',2000000000,5000000,5500000, 400000,  TRUE);

-- Seed coverage options (each product has itself as the primary coverage option)
INSERT INTO coverage_option (product_id, coverage_amount_vnd, deductible_vnd, base_premium_vnd, admin_fee_vnd)
SELECT product_id, coverage_amount_vnd, deductible_vnd, base_premium_vnd, admin_fee_vnd
FROM product WHERE active = TRUE;

-- Seed initial rate version (exactly one is_current=true, design section 5.2)
INSERT INTO rate_version (rate_version_id, effective_at, created_by, is_current, created_at)
VALUES (gen_random_uuid(), '2025-01-01T00:00:00Z', 'system_seed', TRUE, now());

-- Seed default loading factors per line (1.0 = no adjustment baseline)
INSERT INTO loading_factor (rate_version_id, line, loading_value)
SELECT rv.rate_version_id, line, 1.0
FROM rate_version rv, (VALUES
  ('health'), ('motorbike'), ('car'), ('home'), ('accident'), ('travel')
) AS t(line)
WHERE rv.is_current = TRUE;
