-- Connector registry and the platform kinds connectors bring with them.
--
-- platform_kind has no seed data and never will. A kind exists because a
-- connector registered it; enumerating them here would compile the dispatcher's
-- ignorance away, and that ignorance is what lets a new platform arrive without
-- a dispatcher change.

CREATE TABLE connector (
    id              VARCHAR(64)   NOT NULL,
    name            VARCHAR(128)  NOT NULL,
    credential_hash VARCHAR(128)  NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    registered_at   BIGINT        NOT NULL,
    last_seen_at    BIGINT            NULL,
    CONSTRAINT pk_connector PRIMARY KEY (id),
    CONSTRAINT uq_connector_name UNIQUE (name),
    -- Lookup is by credential hash on every authenticated request, so this is
    -- the hot index, and uniqueness also stops two connectors sharing a secret.
    CONSTRAINT uq_connector_credential UNIQUE (credential_hash)
);

CREATE TABLE connector_capability (
    connector_id VARCHAR(64) NOT NULL,
    capability   VARCHAR(64) NOT NULL,
    CONSTRAINT pk_connector_capability PRIMARY KEY (connector_id, capability),
    CONSTRAINT fk_capability_connector FOREIGN KEY (connector_id)
        REFERENCES connector (id) ON DELETE CASCADE
);

CREATE TABLE platform_kind (
    kind          VARCHAR(64) NOT NULL,
    registered_by VARCHAR(64)     NULL,
    first_seen_at BIGINT      NOT NULL,
    CONSTRAINT pk_platform_kind PRIMARY KEY (kind)
);
