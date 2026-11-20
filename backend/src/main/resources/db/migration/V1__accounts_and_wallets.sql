CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_app_users_email UNIQUE (email),
    CONSTRAINT ck_app_users_normalized CHECK (email = lower(trim(email)))
);

CREATE TABLE wallets (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    cash_usd NUMERIC(19, 2) NOT NULL CHECK (cash_usd >= 0)
);
