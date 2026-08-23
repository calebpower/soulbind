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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link Storage#open} decides before it opens anything.
 *
 * <p><b>Why these are asserted here rather than left to the concurrency
 * contract.</b> Every decision below used to be observable only through its
 * consequences on a live SQLite database under concurrent writers -- a pool of
 * four where one was meant, or no serialising writer at all, produces
 * {@code SQLITE_BUSY} <i>if the race goes the wrong way inside the test's
 * window</i>. That made the guard probabilistic: across three mutation runs of
 * an unchanged tree, the mutants of these four lines landed in SURVIVED,
 * TIMED_OUT and KILLED in different combinations, and they were the whole of
 * {@code core}'s run-to-run movement.
 *
 * <p>A probabilistic guard is worse than none, because it reads as one. These
 * tests read the decision straight off an inert {@link HikariConfig}: no
 * server, no connection, no race, and no backend that this machine does not
 * have. DECISIONS 10.46.
 */
class PoolConfigurationTest {

    private static final String SQLITE_URL = "jdbc:sqlite:/tmp/does-not-need-to-exist.db";
    private static final String MARIADB_URL = "jdbc:mariadb://nowhere:3306/soulbind";

    private static String property(HikariConfig cfg, String key) {
        Properties props = cfg.getDataSourceProperties();
        return props == null ? null : props.getProperty(key);
    }

    // --- the SQLite single-writer rule ---------------------------------------

    @Test
    @DisplayName("SQLite gets one connection when writes are serialised, and four when not")
    void sqlitePoolSizeFollowsSerialisation() {
        assertEquals(
                1,
                Storage.poolConfig(Backend.SQLITE, SQLITE_URL, null, null, true)
                        .getMaximumPoolSize(),
                "SQLite permits exactly one writer; a pool of more does not make that untrue, "
                        + "it makes it intermittent");
        assertEquals(
                4,
                Storage.poolConfig(Backend.SQLITE, SQLITE_URL, null, null, false)
                        .getMaximumPoolSize(),
                "without the serialising executor the pool is what carries concurrency, and a "
                        + "pool of one would serialise reads too");
    }

    @Test
    @DisplayName("writes are serialised for SQLite on request, and never for MariaDB")
    void serialisationIsSqliteOnly() {
        assertTrue(Storage.serialisesWrites(Backend.SQLITE, true));
        assertFalse(
                Storage.serialisesWrites(Backend.SQLITE, false),
                "openWithoutWriteSerialisation exists so tests can reach the races; it must "
                        + "actually turn the executor off");
        assertFalse(
                Storage.serialisesWrites(Backend.MARIADB, true),
                "MariaDB handles concurrent writers, and funnelling them through one thread "
                        + "throws that away for no benefit -- asking for it must not get it");
        assertFalse(Storage.serialisesWrites(Backend.MARIADB, false));
    }

    // --- the three SQLite pragmas -------------------------------------------

    @Test
    @DisplayName("SQLite is opened in WAL, with a busy timeout and foreign keys on")
    void sqlitePragmasAreSet() {
        HikariConfig cfg = Storage.poolConfig(Backend.SQLITE, SQLITE_URL, null, null, true);

        assertEquals(
                "WAL",
                property(cfg, "journal_mode"),
                "without WAL, SQLite locks out every reader for the duration of every write");
        assertEquals(
                "5000",
                property(cfg, "busy_timeout"),
                "a zero busy timeout turns a moment's contention into an immediate SQLITE_BUSY");
        assertEquals(
                "true",
                property(cfg, "foreign_keys"),
                "SQLite does not enforce foreign keys unless asked per connection, so without "
                        + "this every constraint in the schema is decorative");
    }

    // --- MariaDB ------------------------------------------------------------

    @Test
    @DisplayName("MariaDB gets a real pool and a stated utf8mb4 collation")
    void mariadbConfiguration() {
        HikariConfig cfg = Storage.poolConfig(Backend.MARIADB, MARIADB_URL, "u", "p", true);

        assertEquals(10, cfg.getMaximumPoolSize());
        assertEquals(
                "utf8mb4_unicode_ci",
                property(cfg, "connectionCollation"),
                "the columns are utf8mb4; a connection negotiated down to a latin1 server "
                        + "default mangles four-byte text while every column definition still "
                        + "looks correct");
        assertNull(
                property(cfg, "journal_mode"),
                "the SQLite pragmas must not leak onto a backend that has no idea what they are");
    }

    // --- credentials, which no backend is needed to observe ------------------

    @Test
    @DisplayName("a credential is passed through when there is one, and left unset when not")
    void credentialsArePassedThroughOnlyWhenPresent() {
        HikariConfig with = Storage.poolConfig(Backend.MARIADB, MARIADB_URL, "u", "p", false);
        assertEquals("u", with.getUsername());
        assertEquals("p", with.getPassword());

        // The reason this is asserted rather than assumed: setUsername(null) and
        // "never called" are different states to Hikari, and the branch that
        // decides between them is on the path every SQLite open takes.
        HikariConfig without = Storage.poolConfig(Backend.SQLITE, SQLITE_URL, null, null, true);
        assertNull(without.getUsername(), "a SQLite file has no user to authenticate as");
        assertNull(without.getPassword());
    }

    @Test
    @DisplayName("the decision reaches the store, not just the decision function")
    void theStoreIsWiredToTheDecision() throws Exception {
        java.nio.file.Path db = java.nio.file.Files.createTempFile("soulbind-pool", ".db");
        java.nio.file.Files.delete(db);
        String url = "jdbc:sqlite:" + db;
        try {
            try (Storage serialised = Storage.open(Backend.SQLITE, url, null, null)) {
                assertTrue(
                        serialised.writesAreSerialised(),
                        "serialisesWrites() answering correctly is worth nothing if open() "
                                + "then wires the answer up backwards");
            }
            try (Storage direct =
                    Storage.openWithoutWriteSerialisation(Backend.SQLITE, url, null, null)) {
                assertFalse(
                        direct.writesAreSerialised(),
                        "openWithoutWriteSerialisation exists so a test can reach the races; a "
                                + "store that quietly serialises anyway makes that test vacuous");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(db);
        }
    }

    @Test
    @DisplayName("the pool is named for its backend")
    void poolIsNamedForItsBackend() {
        assertEquals(
                "soulbind-" + Backend.SQLITE.configName(),
                Storage.poolConfig(Backend.SQLITE, SQLITE_URL, null, null, true).getPoolName(),
                "the pool name is what a thread dump and Hikari's own logging identify this by; "
                        + "two backends sharing one name is a diagnosis nobody can make");
        assertEquals(
                "soulbind-" + Backend.MARIADB.configName(),
                Storage.poolConfig(Backend.MARIADB, MARIADB_URL, null, null, false).getPoolName());
    }

    @Test
    @DisplayName("the url reaches the pool")
    void urlReachesThePool() {
        assertEquals(
                SQLITE_URL,
                Storage.poolConfig(Backend.SQLITE, SQLITE_URL, null, null, true).getJdbcUrl());
    }

    @Test
    @DisplayName("an unhandled backend is refused before anything is opened")
    void unhandledBackendThrows() {
        // Backend is an enum, so this branch is unreachable through the type
        // system today and exists for the next value somebody adds. Asserted so
        // that the next value fails loudly here rather than silently taking
        // SQLite's pragmas or MariaDB's pool size.
        assertThrows(
                NullPointerException.class,
                () -> Storage.poolConfig(null, SQLITE_URL, null, null, true),
                "a null backend must not reach a pool");
    }
}
