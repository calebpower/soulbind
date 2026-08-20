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

package dev.soulbind.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The schema says what charset it is in, rather than inheriting one.
 *
 * <p>Specification §11 Tier 6 requires the battery's MariaDB to start
 * <b>latin1</b>. The point of that is not to test MariaDB — it is that core
 * must state its charset rather than take the server's, because a long-lived
 * installation upgraded across major versions very often still has latin1 as
 * its server default and nobody involved knows it.
 *
 * <p>{@code AuditRepositoryTest.survivesHostileText} already pushes four-byte
 * text through and reads it back, and that is the end-to-end oracle. This test
 * exists beside it for the case that oracle cannot see: a <b>new table</b>
 * added by a later migration, on a latin1 server, whose text columns nobody
 * happens to write an emoji into during the suite. That column is broken from
 * the day it ships and every test is green. So this asserts on the schema
 * itself, table by table and column by column, and will fail the first time a
 * migration adds one that inherited the wrong default.
 *
 * <p>Not tagged {@code charset}: the JVM's default charset is not an input
 * here. This is about what the DDL declares, which is the same under any
 * {@code -Dfile.encoding}.
 */
final class SchemaCharsetTest {

    /**
     * Flyway's own bookkeeping table is excluded, and the exclusion covers
     * exactly it.
     *
     * <p>Two reasons, both specific. Flyway is writing this migration's row into
     * that table at the moment the dialect migration runs, so converting a table
     * the migrator is holding open invites a deadlock on a slow day for no gain.
     * And its contents are migration versions, descriptions and filenames — all
     * authored in this repository, all ASCII — so there is nothing in it that a
     * latin1 column could mangle. It does not excuse any soulbind table.
     */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    /**
     * The floor the enumeration must clear.
     *
     * <p>Every assertion below is of the form "nothing in this list is wrong",
     * and a query returning nothing satisfies all of them. That is the exact
     * shape of the vacuous assertion this project keeps finding, so the count is
     * checked first. Fifteen tables exist at V8; the floor is stated as the
     * number that must be visible for the rest of the test to mean anything, and
     * it rises only when somebody deliberately raises it.
     */
    private static final int MIGRATED_TABLES = 15;

    /**
     * The same floor, for columns.
     *
     * <p>Stated separately rather than reusing the table count, because "one
     * text column per table" is not true — {@code audit_seq} and
     * {@code event_seq} are pure counters and have none. About forty-eight
     * char-typed columns exist at V8; forty is a vacuity guard, not a census, so
     * adding a column does not force an edit here and deleting most of the
     * schema does.
     */
    private static final int TEXT_COLUMNS = 40;

    @TempDir Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("every table and text column is declared four-byte capable")
    void schemaIsFourByteCapable(Backend backend) throws Exception {
        try (Storage storage = StorageBackends.open(backend, tempDir);
                // The pool the repositories write through, deliberately, rather
                // than a fresh DriverManager connection: a second connection
                // would prove that *a* connection to this server speaks utf8mb4,
                // which is not the claim.
                Connection c = storage.dataSource().getConnection()) {
            switch (backend) {
                case SQLITE -> assertSqliteIsUtf8(c);
                case MARIADB -> assertMariadbIsUtf8mb4(c);
            }
        }
    }

    /**
     * SQLite is not charset-free — it is UTF-8 or UTF-16, fixed at creation and
     * unchangeable afterwards. A file created UTF-16 stores the same text and
     * every byte-oriented assumption about it is wrong.
     */
    private void assertSqliteIsUtf8(Connection c) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("PRAGMA encoding")) {
            assertTrue(rs.next(), "PRAGMA encoding returned no row");
            assertEquals("UTF-8", rs.getString(1), "the SQLite file is not UTF-8");
        }
    }

    private void assertMariadbIsUtf8mb4(Connection c) throws Exception {
        // The database default, which is what every table a FUTURE migration
        // creates will inherit. Asserting only on today's tables would pass
        // while leaving tomorrow's broken.
        assertEquals("utf8mb4", one(c,
                "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA"
                        + " WHERE SCHEMA_NAME = DATABASE()"),
                "the database default charset is not utf8mb4, so the next migration's tables"
                        + " will inherit the server's default instead");

        // The connection. utf8mb4 columns reached over a latin1 connection are
        // mangled on the way in while every column definition still looks right.
        for (String variable : List.of(
                "character_set_client", "character_set_connection", "character_set_results")) {
            assertEquals("utf8mb4", one(c,
                    "SELECT @@SESSION." + variable),
                    variable + " is not utf8mb4 on the pool core writes through");
        }

        List<String> tables = new ArrayList<>();
        List<String> wrongTables = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (FLYWAY_HISTORY.equals(table)) {
                    continue;
                }
                tables.add(table);
                String collation = rs.getString(2);
                if (collation == null || !collation.startsWith("utf8mb4")) {
                    wrongTables.add(table + " is " + collation);
                }
            }
        }

        assertTrue(tables.size() >= MIGRATED_TABLES,
                () -> "only " + tables.size() + " tables visible (" + tables + "); expected at"
                        + " least " + MIGRATED_TABLES + ". Every other assertion here passes"
                        + " trivially on an empty enumeration, so this fails first.");
        assertTrue(wrongTables.isEmpty(),
                () -> "tables not declared utf8mb4: " + wrongTables
                        + ". On a latin1 server these hold latin1 text columns and the first"
                        + " four-byte character to reach one is truncated or rejected.");

        int textColumns = 0;
        List<String> wrongColumns = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME"
                                + " FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE()"
                                + " AND CHARACTER_SET_NAME IS NOT NULL")) {
            while (rs.next()) {
                if (FLYWAY_HISTORY.equals(rs.getString(1))) {
                    continue;
                }
                textColumns++;
                if (!"utf8mb4".equals(rs.getString(3))) {
                    wrongColumns.add(rs.getString(1) + "." + rs.getString(2)
                            + " is " + rs.getString(3));
                }
            }
        }

        // A table can be declared utf8mb4 while a column inside it was pinned to
        // something else at creation. CONVERT TO fixes both; asserting only on
        // the table would not notice if a later migration did not.
        int seen = textColumns;
        assertTrue(seen >= TEXT_COLUMNS,
                () -> "only " + seen + " text columns visible, expected at least "
                        + TEXT_COLUMNS + ". As above: an empty enumeration satisfies the"
                        + " assertion below without asserting anything.");
        assertTrue(wrongColumns.isEmpty(),
                () -> "columns not declared utf8mb4: " + wrongColumns);
    }

    private static String one(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("no row from: " + sql);
            }
            return rs.getString(1);
        }
    }
}
