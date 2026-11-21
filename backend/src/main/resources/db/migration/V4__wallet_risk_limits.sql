ALTER TABLE wallets ADD COLUMN max_order_usd NUMERIC(19,2) CHECK (max_order_usd > 0);
ALTER TABLE wallets ADD COLUMN btc_limit_pct NUMERIC(5,2) DEFAULT 100 NOT NULL CHECK (btc_limit_pct BETWEEN 0 AND 100);
ALTER TABLE wallets ADD COLUMN eth_limit_pct NUMERIC(5,2) DEFAULT 100 NOT NULL CHECK (eth_limit_pct BETWEEN 0 AND 100);
