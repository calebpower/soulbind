-- Gates, rules and overrides.
--
-- Gates, like platform kinds, are learned at runtime from connector
-- registration. Nothing enumerates them, and a CHECK constraint listing the
-- known ones would undo the property the whole architecture is arranged around.

CREATE TABLE gate (
    name          VARCHAR(128) NOT NULL,
    registered_by VARCHAR(64)      NULL,
    description   VARCHAR(512)     NULL,
    first_seen_at BIGINT       NOT NULL,
    CONSTRAINT pk_gate PRIMARY KEY (name)
);

-- One rule per gate. Not a list: two rules for one gate would need a
-- combination order, and "which of these applies" is a question with no good
-- answer at three in the morning. An operator wanting alternatives expresses
-- them as required kinds, which already has one.
CREATE TABLE rule (
    gate_name      VARCHAR(128) NOT NULL,
    -- Comma-separated rather than a join table. The set is small, always read
    -- whole, and never queried by member -- a join table would be three more
    -- statements per decision to answer a question nobody asks.
    required_kinds VARCHAR(512)     NULL,
    require_linked INT          NOT NULL,
    grace_seconds  BIGINT       NOT NULL,
    default_effect VARCHAR(16)  NOT NULL,
    updated_at     BIGINT       NOT NULL,
    updated_via    VARCHAR(128)     NULL,
    CONSTRAINT pk_rule PRIMARY KEY (gate_name)
);

-- Overrides beat rules. Exactly one of subject_id or identity_ref is set; the
-- pair is not a foreign key to subject, deliberately, because an operator often
-- needs to admit somebody BEFORE they have linked anything and an override that
-- could only name an existing subject would be useless in exactly that case.
CREATE TABLE policy_override (
    id           VARCHAR(64)  NOT NULL,
    gate_name    VARCHAR(128) NOT NULL,
    subject_id   VARCHAR(64)      NULL,
    identity_ref VARCHAR(256)     NULL,
    effect       VARCHAR(16)  NOT NULL,
    -- NOT NULL, and enforced again in the record's constructor. An override
    -- nobody can review will outlive whoever added it.
    reason       VARCHAR(512) NOT NULL,
    expires_at   BIGINT           NULL,
    created_at   BIGINT       NOT NULL,
    created_by   VARCHAR(128)     NULL,
    CONSTRAINT pk_policy_override PRIMARY KEY (id)
);

CREATE INDEX ix_override_gate ON policy_override (gate_name);
CREATE INDEX ix_override_subject ON policy_override (subject_id);
CREATE INDEX ix_override_identity ON policy_override (identity_ref);
