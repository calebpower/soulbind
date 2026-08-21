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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Upgrading a database that already exists, which nothing else here does.
 *
 * <p><b>Every other storage test starts from nothing.</b>
 * {@link StorageBackends#open} drops and recreates the schema for each test, so
 * a fresh database is the only kind the suite has ever seen. That is correct for
 * isolating tests and it leaves one enormous case uncovered: the deployment that
 * has been running, which is every deployment after the first day.
 *
 * <p>It has already cost. The charset migration of 8.18 was green on the
 * workstation and rejected outright by a real server — twice — because the thing
 * it did wrong could only happen against tables that already existed. Three
 * rounds of review found the wrong problems. `docs/DECISIONS.md` 8.23 and 8.24.
 *
 * <p>So: migrate to an OLDER version, put rows in it, and then upgrade. Flyway's
 * {@code target} is what makes the first step honest — the old schema is built
 * by the same migrations an older soulbind would have run, not by hand-written
 * DDL that could drift from them.
 */
class UpgradePathTest {

    /**
     * The version to stop at when building the "old" database.
     *
     * <p>Deliberately not the newest-minus-one. It wants to be a version far
     * enough back that several migrations run during the upgrade, because a
     * one-migration upgrade tests one migration and this is meant to test the
     * *path*.
     */
    private static final String OLD_VERSION = "4";

    @TempDir Path tempDir;

    /**
     * A database with nothing in it, including no migration history.
     *
     * <p>SQLite gets one from a fresh {@code @TempDir} file. MariaDB does not:
     * it is a server, the schema persists between tests, and an "upgrade" onto
     * a database that is already at the latest version would migrate nothing
     * and assert nothing.
     */
    private void freshSchema(Backend backend) throws Exception {
        if (backend != Backend.MARIADB) {
            return;
        }
        try (Storage discard = StorageBackends.open(backend, tempDir)) {
            // StorageBackends.open drops and recreates for MariaDB, which is
            // exactly the reset wanted here. Closing it immediately leaves a
            // schema at the current version, so wipe the history and objects
            // below rather than reusing it.
            assertNotNull(discard);
        }
        try (HikariDataSource ds = dataSourceFor(backend, tempDir);
                Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + databaseName() + "`");
            s.execute("CREATE DATABASE `" + databaseName() + "`");
        }
    }

    private static String databaseName() {
        String url = StorageBackends.mariadbUrl();
        String tail = url.substring(url.lastIndexOf('/') + 1);
        int query = tail.indexOf('?');
        return query < 0 ? tail : tail.substring(0, query);
    }

    private static HikariDataSource dataSourceFor(Backend backend, Path tempDir) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(StorageBackends.jdbcUrlFor(backend, tempDir));
        if (backend == Backend.MARIADB) {
            cfg.setUsername(System.getenv("SOULBIND_TEST_MARIADB_USER"));
            cfg.setPassword(System.getenv("SOULBIND_TEST_MARIADB_PASSWORD"));
            cfg.setMaximumPoolSize(2);
        } else {
            cfg.setMaximumPoolSize(1);
        }
        return new HikariDataSource(cfg);
    }

    /** Migrates only as far as {@code OLD_VERSION}, the way an older release would have. */
    private static void migrateToOldVersion(Backend backend, HikariDataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/common",
                        "classpath:db/migration/" + backend.configName())
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion(OLD_VERSION))
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("rows written by an older version survive the upgrade")
    void dataSurvivesAnUpgrade(Backend backend) throws Exception {
        // A fresh database, taken to an old version and left there.
        freshSchema(backend);

        try (HikariDataSource old = dataSourceFor(backend, tempDir)) {
            migrateToOldVersion(backend, old);

            // A row an older deployment would have. Written with raw SQL on
            // purpose: the repositories are today's code, and using them to
            // populate yesterday's schema would be testing a combination that
            // never existed.
            try (Connection c = old.getConnection();
                    PreparedStatement insert = c.prepareStatement(
                            "INSERT INTO connector (id, name, credential_hash, status,"
                                    + " registered_at) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, "conn-from-the-past");
                insert.setString(2, "an older release wrote this");
                insert.setString(3, "hash-0001");
                insert.setString(4, "ACTIVE");
                insert.setLong(5, 1_700_000_000L);
                insert.executeUpdate();
            }
        }

        // Now today's code opens it, which runs every remaining migration.
        try (Storage storage = Storage.open(
                Backend.MARIADB == backend ? Backend.MARIADB : Backend.SQLITE,
                StorageBackends.jdbcUrlFor(backend, tempDir),
                backend == Backend.MARIADB
                        ? System.getenv("SOULBIND_TEST_MARIADB_USER") : null,
                backend == Backend.MARIADB
                        ? System.getenv("SOULBIND_TEST_MARIADB_PASSWORD") : null)) {

            assertNotNull(
                    storage.connectors().findByName("an older release wrote this")
                            .orElse(null),
                    "the row an older release wrote did not survive the upgrade. Every other"
                            + " storage test starts from an empty database, so nothing else"
                            + " here would notice.");

            // And the upgrade actually happened -- a "successful" upgrade that
            // ran no migrations would pass the assertion above trivially.
            try (Connection c = storage.dataSource().getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                            "SELECT COUNT(*) FROM flyway_schema_history")) {
                assertTrue(rs.next());
                int applied = rs.getInt(1);
                assertTrue(applied > Integer.parseInt(OLD_VERSION),
                        () -> "only " + applied + " migrations are recorded, so the upgrade"
                                + " from version " + OLD_VERSION + " ran almost nothing and"
                                + " this test asserted almost nothing");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the second open of an upgraded database changes nothing further")
    void upgradingIsIdempotent(Backend backend) throws Exception {
        // Idempotence is already asserted for a database built at the CURRENT
        // version. This asserts it for one that arrived by upgrade, which is a
        // different history and the one a real deployment has.
        freshSchema(backend);
        try (HikariDataSource old = dataSourceFor(backend, tempDir)) {
            migrateToOldVersion(backend, old);
        }

        int afterFirst = openAndCountMigrations(backend);
        int afterSecond = openAndCountMigrations(backend);

        assertEquals(afterFirst, afterSecond,
                "re-opening an upgraded database applied more migrations, so a restart is"
                        + " not a no-op and the drift is per-restart");
    }

    private int openAndCountMigrations(Backend backend) throws Exception {
        try (Storage storage = Storage.open(
                backend,
                StorageBackends.jdbcUrlFor(backend, tempDir),
                backend == Backend.MARIADB
                        ? System.getenv("SOULBIND_TEST_MARIADB_USER") : null,
                backend == Backend.MARIADB
                        ? System.getenv("SOULBIND_TEST_MARIADB_PASSWORD") : null);
                Connection c = storage.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
