-- Say the charset out loud, rather than inheriting whatever the server was
-- started with.
--
-- Every table in V1-V7 is created without a CHARACTER SET clause, so each one
-- takes the database's default, which takes the SERVER's default. On a server
-- started `--character-set-server=latin1` -- which is not exotic; it is what a
-- long-lived installation upgraded across major versions typically still has --
-- every text column in this schema is latin1, and the first four-byte character
-- to reach one is either truncated or rejected outright depending on sql_mode.
-- A player whose name is an emoji cannot link, and the error names a column,
-- not a charset.
--
-- Two statements, doing two different jobs:
--
--   * ALTER DATABASE fixes the FUTURE. Every table a later migration creates
--     inherits from here, so V9 does not have to remember this. That is
--     deliberate: a rule enforced by inheritance beats a rule every future
--     migration author has to know.
--   * CONVERT TO fixes the PAST -- the tables V1-V7 already created under
--     whatever default was in force. On a database that was already utf8mb4
--     these are no-ops, which is what makes the migration safe to add now
--     rather than only for new installations.
--
-- utf8mb4_unicode_ci, not utf8mb4_general_ci: the identifiers compared here
-- come from arbitrary platforms and the comparison should follow Unicode
-- collation rather than a fast approximation of it.
--
-- NOT in common/: SQLite has exactly one charset and does not accept this
-- syntax. This is the per-dialect directory earning the reason it exists.
--
-- `flyway_schema_history` is deliberately absent. Flyway is writing this
-- migration's own row into it while these statements run, and converting a
-- table the migrator holds open is a deadlock waiting for a slow day. It
-- carries migration versions, descriptions and filenames -- all of them
-- authored in this repository, all of them ASCII. It stays latin1 on a latin1
-- server and nothing in it can be mangled.

ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Foreign key checks OFF for the conversion, and this is not optional.
--
-- CONVERT TO CHARACTER SET rewrites every char column's definition, and MariaDB
-- refuses to change a column referenced by a foreign key while the other side
-- still has the old charset:
--
--   1833: Cannot change column 'id': used in a foreign key constraint
--         'fk_capability_connector' of table 'soulbind.connector_capability'
--
-- No ordering avoids it. Converting the parent first leaves the child pointing
-- at a column it no longer matches; converting the child first does the same in
-- reverse. Both sides have to move, and they cannot move simultaneously.
--
-- Nothing is at risk while they are off. This migration inserts and deletes
-- nothing -- it rewrites column metadata, and for the ASCII identifiers in
-- these columns the stored bytes are identical before and after. Every table on
-- both sides of every foreign key is converted in this one script, so the
-- schema is consistent again before the flag goes back.
--
-- This is a SESSION variable, so the concern is a pooled connection escaping
-- with checks still disabled. It cannot: on success the flag is restored below,
-- and on failure Flyway aborts, `Storage.open` throws, and core does not start
-- at all -- the pool dies with the process, having served nothing.
--
-- Found by running it. The local build has no MariaDB, so this file was green
-- on the workstation and had never executed a single one of these statements.
SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE runtime_config CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE audit CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE audit_seq CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE connector CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE connector_capability CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE platform_kind CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE subject CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE identity CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE link_code CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE gate CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE rule CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE policy_override CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE event_outbox CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE event_seq CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE event_cursor CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
