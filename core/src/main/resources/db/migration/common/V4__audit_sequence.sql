-- Atomic allocation of audit sequence numbers.
--
-- The previous scheme read COALESCE(MAX(seq), 0) + 1 and inserted it, inside a
-- transaction, with a comment claiming the transaction prevented two appenders
-- choosing the same number. It did not. A SELECT takes no lock, so two
-- transactions read the same maximum and both insert -- one wins, the other
-- fails the primary key.
--
-- That was invisible for as long as only SQLite ran, because SQLite's
-- single-writer executor serialised every append. The first run against a
-- multi-writer backend produced 45 distinct sequences out of 200 appends.
--
-- One row, updated in place. UPDATE takes an exclusive row lock, so a
-- concurrent appender blocks at its own UPDATE until the first commits, and
-- the value read afterwards inside the same transaction is that appender's
-- alone. Portable: no dialect needs its own version of this.
--
-- A database-native auto-increment column would also work, and was rejected:
-- the two dialects spell it differently, so it would put the audit table's DDL
-- in two files that must agree forever -- and per-dialect migrations are meant
-- for a difference a dialect genuinely forces, not one a choice here created.

CREATE TABLE audit_seq (
    id       INT    NOT NULL,
    next_seq BIGINT NOT NULL,
    CONSTRAINT pk_audit_seq PRIMARY KEY (id)
);

-- Seeded from whatever is already there, so an existing log keeps counting
-- rather than restarting and colliding with its own history.
INSERT INTO audit_seq (id, next_seq)
SELECT 1, COALESCE(MAX(seq), 0) FROM audit;
