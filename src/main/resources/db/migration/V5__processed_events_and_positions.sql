-- Idempotent consumer slice: a dedup ledger + a position projection.

-- The consumer-side guarantee against at-least-once redelivery.
-- One row per (consumer, event). If the same event is delivered twice, the second
-- attempt to record it hits the UNIQUE constraint — the event is a duplicate and is skipped.
CREATE TABLE processed_event (
    id           UUID        PRIMARY KEY,
    consumer     TEXT        NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_processed_event UNIQUE (consumer, event_id)
);

-- A tiny read model, built by consuming OrderExecuted events. Stands in for the
-- Portfolio service's positions. total_amount accumulates settled buy amounts per
-- (account, instrument) — deliberately simple, so that a double-processed event
-- would visibly inflate it if dedup were missing.
CREATE TABLE position (
    id           UUID          PRIMARY KEY,
    account_id   BIGINT        NOT NULL REFERENCES account(id),
    isin         TEXT          NOT NULL,
    total_amount NUMERIC(19,4) NOT NULL,
    CONSTRAINT ux_position UNIQUE (account_id, isin)
);
