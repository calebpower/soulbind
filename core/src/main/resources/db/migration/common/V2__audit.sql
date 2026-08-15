-- The audit log.
--
-- Append-only is enforced in the storage API, which exposes no update and no
-- delete. The schema does not attempt to enforce it as well: a database-level
-- trigger would be a second place the rule lives, and two copies drift. The
-- structural guard asserting no code path acquires an update is the mechanism.
--
-- seq is assigned by storage rather than supplied by callers. Two writers
-- choosing their own positions would collide or leave gaps, and the resulting
-- confusion would arrive exactly when audit is being read to explain something.

CREATE TABLE audit (
    seq          BIGINT        NOT NULL,
    at           BIGINT        NOT NULL,
    actor        VARCHAR(128)  NOT NULL,
    action       VARCHAR(64)   NOT NULL,
    subject_id   VARCHAR(64)       NULL,
    identity_ref VARCHAR(256)      NULL,
    gate         VARCHAR(128)      NULL,
    detail       TEXT              NULL,
    CONSTRAINT pk_audit PRIMARY KEY (seq)
);

CREATE INDEX ix_audit_at ON audit (at);
CREATE INDEX ix_audit_actor ON audit (actor);
CREATE INDEX ix_audit_subject ON audit (subject_id);
