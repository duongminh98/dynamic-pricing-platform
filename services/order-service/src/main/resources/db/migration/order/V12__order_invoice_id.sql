-- V12__order_invoice_id.sql
-- Add invoice_id column to order_ table so the frontend can initiate VNPAY
-- payment directly from the order detail without reading the notification.

ALTER TABLE order_ ADD COLUMN IF NOT EXISTS invoice_id UUID;
CREATE INDEX IF NOT EXISTS idx_order_invoice_id ON order_(invoice_id);
