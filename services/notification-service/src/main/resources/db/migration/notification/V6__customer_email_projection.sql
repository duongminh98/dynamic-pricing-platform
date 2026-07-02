CREATE TABLE IF NOT EXISTS customer_email_projection (
    customer_id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
