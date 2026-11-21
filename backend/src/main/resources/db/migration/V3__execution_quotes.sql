ALTER TABLE trades ALTER COLUMN price_usd TYPE NUMERIC(28,12);
-- Phase 2 receipts did not record a quote timestamp.
ALTER TABLE trades ADD COLUMN price_time TIMESTAMP WITH TIME ZONE;
