-- Runtime config mutable through the config-management capability.
--
-- Deliberately NOT policy. Rules and overrides live in their own tables with
-- their own audit trail; this holds operational knobs such as code TTL and
-- decision-log verbosity. Keeping them apart means "who changed the policy"
-- and "who turned the logging down" are different questions with different
-- answers.

CREATE TABLE runtime_config (
    config_key   VARCHAR(128) NOT NULL,
    config_value TEXT             NULL,
    updated_at   BIGINT       NOT NULL,
    updated_via  VARCHAR(128)     NULL,
    CONSTRAINT pk_runtime_config PRIMARY KEY (config_key)
);
