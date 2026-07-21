-- Saga slice: order aggregate + transactional outbox.

CREATE TABLE orders (
    order_id     UUID          PRIMARY KEY,       -- business key; same UUID used for the hold and the broker
    account_id   BIGINT        NOT NULL REFERENCES account(id),
    isin         TEXT          NOT NULL,
    amount       NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency     CHAR(3)       NOT NULL,
    status       TEXT          NOT NULL,          -- PENDING|RESERVED|SENT|SETTLED|CANCELLED|UNKNOWN
    broker_ref   TEXT,                            -- broker's own id, once we know it
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_orders_status ON orders (status);

-- Transactional outbox: OUTBOUND events (e.g. OrderExecuted) written in the SAME
-- transaction as the state change, so there is never a state-changed-but-not-published gap.
-- A relay publishes them later. Delivery is at-least-once.
CREATE TABLE outbox (
    id            UUID        PRIMARY KEY,
    aggregate_id  UUID        NOT NULL,
    event_type    TEXT        NOT NULL,
    payload       TEXT        NOT NULL,           -- JSON as text; JSONB would need a Hibernate type, overkill here
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ                     -- NULL = not yet published
);

-- The relay scans for unpublished rows; a partial index keeps that scan cheap.
CREATE INDEX ix_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
