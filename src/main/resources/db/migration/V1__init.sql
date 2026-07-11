-- Two tables only. Everything we discussed lives here.

CREATE TABLE account (
    id          BIGINT        PRIMARY KEY,
    balance     NUMERIC(19,4) NOT NULL,          -- money is NUMERIC, never float
    currency    CHAR(3)       NOT NULL,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE account_hold (
    id           UUID          PRIMARY KEY,
    account_id   BIGINT        NOT NULL REFERENCES account(id),
    order_id     UUID          NOT NULL,          -- idempotency key
    amount       NUMERIC(19,4) NOT NULL,
    currency     CHAR(3)       NOT NULL,
    status       TEXT          NOT NULL,          -- ACTIVE | SETTLED | RELEASED
    expires_at   TIMESTAMPTZ   NOT NULL,          -- TTL: a hold is not eternal
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_amount_positive CHECK (amount > 0)
);

-- GUARANTEE #1 (idempotency): at most one ACTIVE hold per order.
-- If two concurrent retries of the same order both slip past the application-level
-- findByOrderId check, the database physically refuses the second insert.
-- The application optimizes; the database guarantees.
CREATE UNIQUE INDEX ux_hold_active_order
    ON account_hold (order_id)
    WHERE status = 'ACTIVE';

-- Speeds up the "sum of active holds on this account" query.
CREATE INDEX ix_hold_account_active
    ON account_hold (account_id)
    WHERE status = 'ACTIVE';

-- Demo data: one account with 100.00, so the concurrency tests have something to fight over.
INSERT INTO account (id, balance, currency) VALUES (1, 100.0000, 'EUR');
