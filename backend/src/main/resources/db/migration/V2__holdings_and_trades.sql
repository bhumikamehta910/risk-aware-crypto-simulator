CREATE TABLE holdings (
    user_id UUID NOT NULL REFERENCES wallets(user_id),
    asset VARCHAR(3) NOT NULL CHECK (asset IN ('BTC', 'ETH')),
    quantity NUMERIC(24,8) NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (user_id, asset)
);

CREATE TABLE trades (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES wallets(user_id),
    request_key UUID NOT NULL,
    asset VARCHAR(3) NOT NULL CHECK (asset IN ('BTC', 'ETH')),
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity NUMERIC(24,8) NOT NULL CHECK (quantity > 0),
    price_usd NUMERIC(19,2) NOT NULL CHECK (price_usd > 0),
    total_usd NUMERIC(19,2) NOT NULL CHECK (total_usd > 0),
    price_source VARCHAR(32) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (user_id, request_key)
);
CREATE INDEX ix_trades_user_time ON trades(user_id, executed_at DESC, id DESC);
