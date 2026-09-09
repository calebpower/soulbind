-- Named numeric observations a connector reports about one platform account.
--
-- A MEASURE is "how much of something, over how long a window, observed when".
-- Core never learns what the number counts: that it means minutes of play is a
-- convention between the connector that reports it and the operator who writes
-- the rule. Naming a platform here would put a platform name in core, which a
-- guard refuses and hub-and-spoke depends on.
--
-- ONE ROW per (identity_ref, name), OVERWRITTEN. Not a time series, and that is
-- the decision the whole design rests on. Keeping samples would mean core
-- computing windows, which forces retention, which forces a sweep -- and
-- nothing in this system re-evaluates on a timer. Storing the answer the
-- reporter already computed, plus enough provenance to decide whether to still
-- believe it, removes all three problems rather than solving them.
--
-- identity_ref is VARCHAR(256), matching audit, policy_override and
-- event_outbox. It holds a COMPOSITE `kind:id`, and the parts are
-- platform_kind VARCHAR(64) and platform_id VARCHAR(191) -- so a legal
-- reference reaches 256 characters and 191 truncates it.
--
-- An earlier draft used 191 and justified it on an index budget. That argument
-- was wrong twice over: it does not bind here -- (256 + 64) x 4 = 1280 bytes,
-- comfortably under the 3072-byte DYNAMIC limit -- and the failure it would have
-- caused is the exact shape this project keeps finding. SQLite ignores VARCHAR
-- length entirely, so every test would pass; MariaDB in strict mode raises
-- error 1406 and the connector gets a 500. It would have been invisible until a
-- deployment, and only for accounts with long identifiers.
--
-- NOT a foreign key to `identity`, for the reason policy_override is not one to
-- subject: an account can accumulate a measure before it is linked to anything,
-- and a table that could only name an existing identity would be empty in
-- exactly the case an operator cares about. Orphans are removed in the unlink's
-- own transaction rather than by a reaper nothing calls.

CREATE TABLE measure (
    identity_ref   VARCHAR(256) NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    value          BIGINT       NOT NULL,
    -- The window the value covers, as the REPORTER understands it. Stored
    -- rather than assumed, because a thirty-day total satisfying a seven-day
    -- threshold is a misconfiguration nothing would otherwise report -- it
    -- would simply grant, silently, to people who had not earned it.
    window_seconds BIGINT       NOT NULL,
    -- When CORE recorded it, from core's own clock. Never the caller's:
    -- freshness a connector can assert is freshness any connector can extend,
    -- which is the same reasoning that keeps first-seen out of requests.
    observed_at    BIGINT       NOT NULL,
    reported_by    VARCHAR(64)  NOT NULL,
    CONSTRAINT pk_measure PRIMARY KEY (identity_ref, name)
);

-- For "which accounts hold this measure", which is how an operator asks whether
-- a reporter is reporting at all.
CREATE INDEX ix_measure_name ON measure (name);

-- A rule's THRESHOLD on a measure. Four nullable columns rather than a side
-- table: a rule has at most one, it is always read with the rule, and never
-- queried on its own -- the same reasoning that made required_kinds a comma
-- separated column rather than a join.
--
-- All four null together means "no measure requirement", which is every rule
-- written before now. The reader treats a null NAME as absence and ignores the
-- rest, so a partially-written row cannot become a requirement nobody typed.
ALTER TABLE rule ADD COLUMN measure_name VARCHAR(64) NULL;
ALTER TABLE rule ADD COLUMN measure_at_least BIGINT NULL;
ALTER TABLE rule ADD COLUMN measure_window_seconds BIGINT NULL;
ALTER TABLE rule ADD COLUMN measure_max_age_seconds BIGINT NULL;
