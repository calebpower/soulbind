/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Proves migrations are idempotent against a live deployment.
 *
 * <p>Run as a single-file source program against core's own installed
 * classpath, so it uses the same Flyway, the same drivers and the same
 * {@link Storage#open} the server uses. A separate re-implementation of
 * "apply the migrations" would be a second definition that could agree with
 * itself while disagreeing with the server.
 *
 * <p><b>What idempotence means here.</b> {@code Storage.open} migrates on every
 * open, so a deployment re-runs migrations on every restart. If a second apply
 * were not a no-op, every restart would mutate the schema — and the failure
 * would appear as drift on a long-lived server rather than as anything a test
 * on a fresh database could see. That is why this runs in-session against a
 * database that has already been migrated and used, not against an empty one.
 *
 * <p>The fingerprint is deliberately dialect-neutral: Flyway's history rows
 * plus JDBC {@code DatabaseMetaData}. Comparing raw DDL text would compare
 * SQLite's and MariaDB's spelling of the same schema, which differs for reasons
 * that are not defects.
 *
 * <p><b>It covers more than tables and columns, because the first version did
 * not.</b> An adversarial review dropped an index and inserted a row into a
 * sequence-emulation table, and the fingerprint reported the database
 * unchanged. The seed-row case is the one that matters: a migration that
 * re-inserts or resets {@code audit_seq} / {@code event_seq} on every apply
 * means reused audit and event identifiers on every restart — precisely the
 * "drift per restart, invisible to a fresh-database test" this file exists to
 * catch, and it passed.
 *
 * <p>So it now also reads indexes, primary keys, foreign keys, column size,
 * nullability, defaults and ordinal position, and the CONTENTS of the small
 * seed tables. What it still does not see is written down rather than implied:
 * views and triggers (the schema has none), and CHECK constraints, which JDBC
 * metadata does not expose portably.
 *
 * <p>Usage: {@code MigrationFingerprint <backend> <jdbcUrl> [user] [password]}
 */
public final class MigrationFingerprint {

    /**
     * Tables at or below this many rows have their CONTENTS fingerprinted.
     *
     * <p>Derived rather than a list of names: the sequence-emulation tables,
     * the runtime config, the gate and rule tables and the platform-kind
     * registry are all small and all operationally load-bearing, and a list
     * would silently miss the next one.
     */
    private static final int SMALL_TABLE_ROWS = 200;

    /** A floor on how much the fingerprint must have measured to mean anything. */
    private static final int MINIMUM_FINGERPRINT_LINES = 20;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                    "usage: MigrationFingerprint <backend> <jdbcUrl> [user] [password]");
            System.exit(2);
        }
        Backend backend = Backend.fromConfigName(args[0]).orElseThrow(
                () -> new IllegalArgumentException("unknown backend: " + args[0]));
        String url = args[1];
        String user = args.length > 2 && !args[2].isBlank() ? args[2] : null;
        String password = args.length > 3 && !args[3].isBlank() ? args[3] : null;

        // The PRECONDITION, checked first and reported as itself.
        //
        // The first real run of this stage failed with a raw SQLite stack trace
        // and the verdict "re-applying migrations was not a no-op" -- when the
        // truth was that the up stage had failed and there was no migrated
        // database at all. A check that blames idempotence for a missing
        // database sends whoever reads it to the wrong place entirely.
        if (!hasMigrationHistory(url, user, password)) {
            System.err.println("there is no migrated database at " + url);
            System.err.println("flyway_schema_history is absent, so nothing has been migrated here");
            System.err.println("and idempotence is not the question. Did the up stage complete?");
            System.exit(3);
        }

        String before = fingerprint(url, user, password);

        // A FLOOR. Two empty fingerprints compare equal, so a metadata call that
        // silently returned nothing -- a driver quirk, a catalog-scoping
        // difference between the two backends -- would print "migrations are
        // idempotent" having compared no schema at all. The number is a floor,
        // not the exact count, so adding a migration does not edit this file.
        long tableLines = before.lines().filter(l -> l.startsWith("  ") && !l.startsWith("    ")).count();
        if (tableLines < MINIMUM_FINGERPRINT_LINES) {
            System.err.println("the fingerprint measured almost nothing (" + tableLines
                    + " lines), so comparing it to itself would prove nothing. Expected at least "
                    + MINIMUM_FINGERPRINT_LINES + ". Is the metadata call seeing this catalog?");
            System.exit(2);
        }

        // The whole point: open the way the server opens, which migrates.
        try (Storage ignored = Storage.open(backend, url, user, password)) {
            // Nothing to do. Opening is the operation under test.
        }

        String after = fingerprint(url, user, password);

        if (before.equals(after)) {
            System.out.println("migrations are idempotent on " + backend.configName());
            System.out.println(before);
            return;
        }

        System.err.println("MIGRATIONS ARE NOT IDEMPOTENT on " + backend.configName());
        System.err.println("A second apply changed the database. Every restart of a deployed");
        System.err.println("core re-runs migrations, so this is drift on every restart --");
        System.err.println("visible on a long-lived server and invisible to any test that");
        System.err.println("only ever migrates a fresh database.");
        System.err.println();
        System.err.println("--- before ---");
        System.err.println(before);
        System.err.println("--- after ---");
        System.err.println(after);
        System.exit(1);
    }

    private static boolean hasMigrationHistory(String url, String user, String password) {
        try (Connection c = user == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
                var st = c.createStatement()) {
            st.executeQuery("SELECT 1 FROM flyway_schema_history").close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fingerprint(String url, String user, String password)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = user == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password)) {

            sb.append("flyway history:\n");
            try (var st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT installed_rank, version, description, type, checksum, success"
                                    + " FROM flyway_schema_history ORDER BY installed_rank")) {
                while (rs.next()) {
                    sb.append("  ")
                            .append(rs.getInt(1)).append(' ')
                            .append(rs.getString(2)).append(' ')
                            .append(rs.getString(3)).append(' ')
                            .append(rs.getString(4)).append(' ')
                            .append(rs.getString(5)).append(' ')
                            .append(rs.getBoolean(6))
                            .append('\n');
                }
            }

            // Tables and columns from JDBC metadata rather than DDL text: the
            // two dialects spell the same schema differently, and a diff of
            // their spelling would report a difference that is not one.
            sb.append("schema:\n");
            DatabaseMetaData md = c.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            Collections.sort(tables);
            for (String table : tables) {
                sb.append("  ").append(table).append('\n');
                List<String> columns = new ArrayList<>();
                // The table name is escaped for LIKE: getColumns takes a
                // PATTERN, so an underscore is a single-character wildcard.
                // Harmless in today's schema, and `link_code` would happily
                // match a `linkXcode` if one ever appeared.
                String pattern = md.getSearchStringEscape();
                String escaped = table.replace("_", pattern + "_").replace("%", pattern + "%");
                try (ResultSet rs = md.getColumns(null, null, escaped, "%")) {
                    while (rs.next()) {
                        // Size, default and ordinal included: a VARCHAR(64)
                        // widened to VARCHAR(255), a default appearing, or a
                        // column order changing are all real schema drift that
                        // name-and-type alone reports as identical.
                        columns.add(rs.getInt("ORDINAL_POSITION")
                                + ":" + rs.getString("COLUMN_NAME")
                                + ':' + rs.getString("TYPE_NAME")
                                + '(' + rs.getInt("COLUMN_SIZE") + ')'
                                + ":null=" + rs.getInt("NULLABLE")
                                + ":default=" + rs.getString("COLUMN_DEF"));
                    }
                }
                Collections.sort(columns);
                for (String column : columns) {
                    sb.append("    ").append(column).append('\n');
                }

                appendSorted(sb, "    index ", indexes(md, table));
                appendSorted(sb, "    pk ", primaryKeys(md, table));
                appendSorted(sb, "    fk ", foreignKeys(md, table));
            }


            // CONTENTS, for every small table, and a row count for every table.
            //
            // The first version dumped a hand-written list of two sequence
            // tables. That list was both incomplete and unguarded: resetting
            // `runtime_config.config_value` -- an operator's configured code TTL
            // -- and emptying `platform_kind` and `identity` all left the
            // fingerprint IDENTICAL. The reasoning that justified reading
            // audit_seq applies verbatim to any of them: a repeatable migration
            // or an afterMigrate callback that rewrites operational rows is
            // drift on every restart with no schema change at all.
            //
            // Derived by size rather than named, so a future seq or config table
            // is covered the day it exists and cannot be forgotten -- the same
            // reason the guards derive their module list from settings.gradle.kts
            // rather than hand-listing it.
            sb.append("rows:\n");
            for (String table : tables) {
                String quoted = quote(md, table);
                long count = countRows(c, quoted);
                sb.append("  ").append(table).append(' ').append(count).append('\n');
                if (count < 0) {
                    continue;
                }
                if (count <= SMALL_TABLE_ROWS) {
                    for (String row : rowsOf(c, table, quoted)) {
                        sb.append("    ").append(row).append('\n');
                    }
                } else {
                    // A DIGEST, not a shrug.
                    //
                    // Dumping every row of a large table is unbounded output, but
                    // stopping at a count leaves a silent coverage cliff: at 200
                    // rows a rewrite of every row was caught, at 201 it was
                    // invisible -- and `audit` and `event_outbox`, where reused
                    // identifiers actually hurt, are exactly the tables that
                    // cross it on a long-lived server. Worse, the cliff is
                    // data-dependent, so the same drift is caught on a fresh
                    // database and missed on the used one this file exists to
                    // test. Streaming a digest costs one pass and no memory.
                    sb.append("    digest=").append(digestOf(c, table, quoted)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Quotes a table name the way THIS database quotes identifiers.
     *
     * <p>The first version wrote {@code "table"} unconditionally. MariaDB reads
     * a double-quoted token as a string literal unless {@code ANSI_QUOTES} is
     * set, and nothing here sets it — so every count and every contents read
     * would have been a syntax error, caught by the broad handler, reported as
     * {@code -1} in both fingerprints, and compared equal. The whole
     * contents-and-counts half would have been silently dead on the backend it
     * had never run against. Core itself never quotes identifiers, for the same
     * dialect reason.
     */
    private static String quote(DatabaseMetaData md, String table) throws Exception {
        String q = md.getIdentifierQuoteString();
        if (q == null || q.isBlank()) {
            return table;
        }
        return q + table + q;
    }

    private static long countRows(Connection c, String quoted) {
        try (var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quoted)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (Exception e) {
            // Reported in the fingerprint rather than swallowed: a table that
            // becomes unreadable between the two reads IS a difference.
            return -1;
        }
    }

    /** Every row, rendered column-name:value and sorted, so order is not noise. */
    private static List<String> rowsOf(Connection c, String table, String quoted) {
        List<String> out = new ArrayList<>();
        try (var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM " + quoted)) {
            var md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder(table).append(": ");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.append(md.getColumnLabel(i)).append('=').append(rs.getString(i)).append(' ');
                }
                out.add(row.toString().strip());
            }
        } catch (Exception e) {
            out.add(table + ": UNREADABLE " + e.getClass().getSimpleName());
        }
        Collections.sort(out);
        return out;
    }

    /** A streamed digest of every row, so a large table is measured, not skipped. */
    private static String digestOf(Connection c, String table, String quoted) {
        try (var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM " + quoted)) {
            var md = rs.getMetaData();
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            // Row order is not guaranteed, so each row is hashed independently
            // and the hashes are XOR-folded -- order-insensitive, and still
            // sensitive to any row's contents changing.
            byte[] acc = new byte[32];
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.append(md.getColumnLabel(i)).append('=').append(rs.getString(i)).append('\u001f');
                }
                byte[] h = digest.digest(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (int i = 0; i < acc.length; i++) {
                    acc[i] ^= h[i];
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : acc) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "UNREADABLE:" + e.getClass().getSimpleName();
        }
    }

    private static void appendSorted(StringBuilder sb, String prefix, List<String> values) {
        Collections.sort(values);
        for (String value : values) {
            sb.append(prefix).append(value).append('\n');
        }
    }

    private static List<String> indexes(DatabaseMetaData md, String table) throws Exception {
        List<String> out = new ArrayList<>();
        // approximate=false, so the answer is read rather than estimated. unique
        // is part of the key: dropping a UNIQUE constraint is the difference
        // between "one identity per platform account" and "as many as you like".
        try (ResultSet rs = md.getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                out.add(name + ":unique=" + !rs.getBoolean("NON_UNIQUE")
                        + ":pos=" + rs.getShort("ORDINAL_POSITION")
                        + ":col=" + rs.getString("COLUMN_NAME"));
            }
        }
        return out;
    }

    private static List<String> primaryKeys(DatabaseMetaData md, String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
                out.add(rs.getShort("KEY_SEQ") + ":" + rs.getString("COLUMN_NAME"));
            }
        }
        return out;
    }

    private static List<String> foreignKeys(DatabaseMetaData md, String table) throws Exception {
        List<String> out = new ArrayList<>();
        // The delete rule is included because ON DELETE CASCADE quietly becoming
        // NO ACTION changes what a deletion does to the identity graph, and
        // leaves every column exactly where it was.
        try (ResultSet rs = md.getImportedKeys(null, null, table)) {
            while (rs.next()) {
                // UPDATE_RULE too, the direct sibling of DELETE_RULE: two
                // schemas differing only in ON UPDATE rendered identically.
                // FK_NAME and KEY_SEQ because appendSorted would otherwise sort
                // away a composite key's column ordering.
                out.add(rs.getString("FK_NAME")
                        + ':' + rs.getShort("KEY_SEQ")
                        + ':' + rs.getString("FKCOLUMN_NAME")
                        + "->" + rs.getString("PKTABLE_NAME")
                        + '.' + rs.getString("PKCOLUMN_NAME")
                        + ":delete=" + rs.getShort("DELETE_RULE")
                        + ":update=" + rs.getShort("UPDATE_RULE"));
            }
        }
        return out;
    }
}
