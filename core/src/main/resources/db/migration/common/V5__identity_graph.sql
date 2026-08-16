-- Subjects, identities, and link codes.
--
-- A SUBJECT is a person, as far as this system is concerned. It has no name, no
-- email and no password: soulbind does not have accounts of its own, and adding
-- one would make it a thing to be logged into, breached and reset. A subject is
-- the join between identities and nothing more.
--
-- An IDENTITY is one platform account. `(platform_kind, platform_id)` is unique
-- across the whole table, not per subject: one platform account belongs to at
-- most one person, and letting two subjects claim the same one is how two people
-- end up sharing an entitlement.
--
-- Platform kinds are NOT enumerated here or anywhere in code. They arrive at
-- registration and are recorded as they are seen. That is the property the whole
-- architecture is arranged to keep, and a column constraint listing them would
-- undo it with one CHECK.

CREATE TABLE subject (
    id         VARCHAR(64) NOT NULL,
    created_at BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL,
    CONSTRAINT pk_subject PRIMARY KEY (id)
);

CREATE TABLE identity (
    id            VARCHAR(64)  NOT NULL,
    subject_id    VARCHAR(64)  NOT NULL,
    platform_kind VARCHAR(64)  NOT NULL,
    platform_id   VARCHAR(191) NOT NULL,
    display       VARCHAR(191)     NULL,
    -- Free-form flags as JSON rather than columns. A column per platform trait
    -- would be a platform name in the schema, which is exactly the compile-time
    -- knowledge the dispatcher must not have. A connector sets what it knows;
    -- core stores it and never branches on it.
    flags         TEXT             NULL,
    proof_method  VARCHAR(64)      NULL,
    verified_at   BIGINT           NULL,
    created_at    BIGINT       NOT NULL,
    CONSTRAINT pk_identity PRIMARY KEY (id),
    -- 191 rather than 255: MariaDB's utf8mb4 index prefix limit under the older
    -- 767-byte cap. Stated so the number does not look arbitrary and get
    -- "tidied" to 255 by somebody who only ever runs the other backend.
    CONSTRAINT uq_identity_platform UNIQUE (platform_kind, platform_id),
    CONSTRAINT fk_identity_subject FOREIGN KEY (subject_id)
        REFERENCES subject (id) ON DELETE CASCADE
);

CREATE INDEX ix_identity_subject ON identity (subject_id);
CREATE INDEX ix_identity_kind ON identity (platform_kind);

-- Link codes.
--
-- `code` is the NORMALISED form -- the only form that is ever stored or
-- compared. Storing what the user typed and normalising on read would mean two
-- codes differing only in case could both exist, and the collision would appear
-- as a redeem that silently linked the wrong account.
--
-- Single use is enforced by an UPDATE that also carries the predicate:
-- `SET redeemed_at = ? WHERE code = ? AND redeemed_at IS NULL`. One row updated
-- means this caller redeemed it; zero means somebody else already had. That is
-- the whole mechanism, and it needs no lock, no read-then-write and no
-- backend-specific isolation level.
CREATE TABLE link_code (
    code                  VARCHAR(64)  NOT NULL,
    issued_by_connector   VARCHAR(64)  NOT NULL,
    issued_for_kind       VARCHAR(64)  NOT NULL,
    issued_for_id         VARCHAR(191) NOT NULL,
    issued_for_display    VARCHAR(191)     NULL,
    issued_at             BIGINT       NOT NULL,
    expires_at            BIGINT       NOT NULL,
    redeemed_at           BIGINT           NULL,
    redeemed_by_connector VARCHAR(64)      NULL,
    CONSTRAINT pk_link_code PRIMARY KEY (code)
);

CREATE INDEX ix_link_code_expires ON link_code (expires_at);

-- platform_kind is NOT created here: V1 already has it. Recording that rather
-- than leaving the absence unexplained, because "the identity graph migration
-- does not create the table it references" reads as an omission otherwise.
