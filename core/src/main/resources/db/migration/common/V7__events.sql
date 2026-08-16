-- The event outbox, and one cursor per subscriber.
--
-- An OUTBOX rather than direct delivery. An event emitted by calling a
-- connector inline would be an event lost when that call fails, and it would
-- make every mutation's latency depend on the slowest subscriber. Writing the
-- event in the same transaction as the change that caused it is what makes
-- "the change happened but nobody heard" impossible.
--
-- Delivery is at-least-once, deliberately. Exactly-once across a network does
-- not exist; what exists is at-least-once plus an idempotency key, and being
-- honest about which one is being offered is how connector authors know they
-- must dedup.

CREATE TABLE event_outbox (
    seq             BIGINT       NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    subject_id      VARCHAR(64)      NULL,
    identity_ref    VARCHAR(256)     NULL,
    gate_name       VARCHAR(128)     NULL,
    payload         TEXT             NULL,
    -- Assigned by core and carried to every subscriber unchanged. Two
    -- subscribers seeing the same event see the same key, so a connector that
    -- reconnects mid-stream can recognise what it already applied.
    idempotency_key VARCHAR(64)  NOT NULL,
    created_at      BIGINT       NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (seq)
);

CREATE INDEX ix_event_created ON event_outbox (created_at);
CREATE INDEX ix_event_subject ON event_outbox (subject_id);

-- Allocated the same way audit sequences are, and for the same reason: SELECT
-- MAX takes no lock, so two emitters read the same maximum and one loses on the
-- primary key. That defect was invisible on the single-writer backend for an
-- entire phase.
CREATE TABLE event_seq (
    id       INT    NOT NULL,
    next_seq BIGINT NOT NULL,
    CONSTRAINT pk_event_seq PRIMARY KEY (id)
);

INSERT INTO event_seq (id, next_seq)
SELECT 1, COALESCE(MAX(seq), 0) FROM event_outbox;

-- Where each connector has got to.
--
-- A cursor per connector, not a global one: a connector that was down must
-- receive what it missed, and a shared position would mean whichever
-- subscriber was fastest decided what the others never saw.
--
-- The cursor advances only on ACKNOWLEDGEMENT, never on send. Advancing on send
-- turns a delivery that failed in flight into an event nobody will ever
-- receive -- which is the whole failure the outbox exists to prevent.
CREATE TABLE event_cursor (
    connector_id VARCHAR(64) NOT NULL,
    position     BIGINT      NOT NULL,
    updated_at   BIGINT      NOT NULL,
    CONSTRAINT pk_event_cursor PRIMARY KEY (connector_id)
);
