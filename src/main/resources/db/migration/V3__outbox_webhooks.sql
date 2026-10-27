-- Phase 3: transactional outbox + webhook delivery.
-- Native enums keep the same style as V2 (tx_status, entry_dir).
CREATE TYPE outbox_status AS ENUM ('NEW', 'SENT');
CREATE TYPE delivery_status AS ENUM ('PENDING', 'DELIVERED', 'RETRYING', 'DEAD');

-- The outbox: an event row is written in the SAME transaction as the payment it describes,
-- so "payment committed" and "event recorded" are atomic. A separate poller ships NEW rows
-- to Kafka and flips them to SENT.
CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID          NOT NULL,               -- the transaction this event is about
    event_type   TEXT          NOT NULL,               -- payment.completed | payment.failed | payment.held
    payload      JSONB         NOT NULL,               -- the event body delivered to merchants
    status       outbox_status NOT NULL DEFAULT 'NEW',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- The poller only ever scans unsent rows oldest-first; a partial index stays small as SENT rows pile up.
CREATE INDEX idx_outbox_unsent ON outbox_events (created_at) WHERE status = 'NEW';

-- A merchant's registered webhook target. `secret` is the HMAC key for signing deliveries.
CREATE TABLE webhook_endpoints (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    url        TEXT        NOT NULL,
    secret     TEXT        NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_endpoints_user ON webhook_endpoints (user_id) WHERE active;

-- One row per (event, endpoint) delivery attempt-chain. UNIQUE(event_id, endpoint_id) is the
-- dedupe gate: Kafka is at-least-once, so a redelivered event must not create a second delivery.
CREATE TABLE webhook_deliveries (
    id                 BIGSERIAL PRIMARY KEY,
    event_id           UUID            NOT NULL REFERENCES outbox_events (id),
    endpoint_id        BIGINT          NOT NULL REFERENCES webhook_endpoints (id),
    attempt            INT             NOT NULL DEFAULT 0,
    status             delivery_status NOT NULL DEFAULT 'PENDING',
    next_retry_at      TIMESTAMPTZ,
    last_response_code INT,
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery UNIQUE (event_id, endpoint_id)
);
-- The retry scheduler scans only rows that are still owed a delivery attempt (created-but-unsent
-- PENDING, or failed-and-waiting RETRYING); a partial index keeps that scan tiny as DELIVERED/DEAD
-- rows accumulate.
CREATE INDEX idx_delivery_due ON webhook_deliveries (next_retry_at)
    WHERE status IN ('PENDING', 'RETRYING');
