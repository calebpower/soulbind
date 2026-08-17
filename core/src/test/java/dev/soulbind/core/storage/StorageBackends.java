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

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Supplies the backends every storage test runs against.
 *
 * <p>The seam earns its keep or it does not. A test that runs against one
 * backend proves that backend works, and quietly asserts nothing about the
 * other — which is how "it worked locally on SQLite" reaches a deployment
 * running MariaDB.
 *
 * <p><b>MariaDB is skipped when no server is reachable.</b> It is not silently
 * treated as a pass: {@link #mariadbUrl()} returns null and the parameter
 * source omits it, so the parameterised display names show which backends ran.
 * Availability is read from {@code SOULBIND_TEST_MARIADB_URL} so a workstation
 * without a database server can still run the cheap tiers, while the
 * containerised battery supplies one and gets both.
 *
 * <p><b>Known gap, stated rather than implied.</b> This class does not itself
 * report how many backends ran — an earlier version of this comment claimed it
 * did, and nothing implemented it. Today the only proof the second backend was
 * exercised is incidental: the fuzz tier prints its seed with the backend name
 * because that task sets {@code showStandardStreams}. Drop {@code @Tag("fuzz")}
 * from the dispatcher fuzz test and the battery loses its only surviving
 * evidence that MariaDB ran, and stays green. The result XML that would prove
 * it lives under {@code build/}, which the session harness excludes from the
 * sync back, so nothing reaches the workstation either. A deliberate evidence
 * channel is outstanding work, tracked in {@code docs/STATUS.md}.
 *
 * <p>This is a narrowing with a stated reason, and the reason covers exactly
 * this: MariaDB coverage on a workstation with no MariaDB. It does not excuse
 * a failure on either backend, and the full-stack battery runs both.
 */
// Public so the transport tests can drive the same backends. A second copy of
// this logic would be a second definition of "which backends run", and the two
// would answer differently the first time one was updated.
public final class StorageBackends {

    private StorageBackends() {
        throw new AssertionError("no instances");
    }

    public static String mariadbUrl() {
        String url = System.getenv("SOULBIND_TEST_MARIADB_URL");
        return (url == null || url.isBlank()) ? null : url;
    }

    public static boolean mariadbAvailable() {
        return mariadbUrl() != null;
    }

    /**
     * Opens a store for the given backend.
     *
     * @param tempDir a per-test directory, so SQLite files never leak between tests
     */
    public static Storage open(Backend backend, Path tempDir) {
        return switch (backend) {
            case SQLITE -> Storage.open(
                    Backend.SQLITE,
                    // One file per @TempDir, which JUnit makes fresh per test
                    // method -- so tests are isolated, and a test that REOPENS
                    // its store gets the same one back.
                    "jdbc:sqlite:" + tempDir.resolve("soulbind-test.db"),
                    null,
                    null);
            case MARIADB -> {
                // A server-backed store does NOT get a fresh file, so it has to
                // be given a fresh schema explicitly.
                //
                // This was found the first time both backends actually ran: ten
                // tests failed, every one of them because state from an earlier
                // test was still there -- "a fresh log starts at 0" finding 2.
                // The suite had been isolated only by ACCIDENT, by SQLite's
                // per-test temp file, and nothing said so. On the backend that
                // does not hand out fresh files, the accident stopped holding.
                //
                // Dropping and recreating is deliberate rather than truncating
                // the tables: it also re-runs the migrations, so every test
                // exercises them rather than only the first.
                resetMariadbSchema();
                yield Storage.open(
                        Backend.MARIADB,
                        mariadbUrl(),
                        System.getenv("SOULBIND_TEST_MARIADB_USER"),
                        System.getenv("SOULBIND_TEST_MARIADB_PASSWORD"));
            }
        };
    }

    /**
     * Reopens the store {@link #open} last created, WITHOUT resetting it.
     *
     * <p>Separate from {@code open} because the two mean different things and
     * conflating them broke a real test: {@code open} must hand out a clean
     * store so tests do not see each other's rows, while a test asserting that
     * migrations are idempotent must get the SAME store back, rows and all.
     *
     * <p>SQLite gets both for free from a per-test temp file. A server-backed
     * store gets neither for free, which is why the distinction has to be
     * written down rather than inferred.
     */
    public static Storage reopen(Backend backend, Path tempDir) {
        return switch (backend) {
            case SQLITE -> Storage.open(
                    Backend.SQLITE,
                    "jdbc:sqlite:" + tempDir.resolve("soulbind-test.db"),
                    null,
                    null);
            case MARIADB -> Storage.open(
                    Backend.MARIADB,
                    mariadbUrl(),
                    System.getenv("SOULBIND_TEST_MARIADB_USER"),
                    System.getenv("SOULBIND_TEST_MARIADB_PASSWORD"));
        };
    }

    /**
     * Drops and recreates the test schema.
     *
     * <p>Uses plain JDBC rather than going through the seam: this is test
     * scaffolding, it lives in the storage package where knowing the backend is
     * permitted, and asking the seam for "destroy everything" would be adding a
     * destructive operation to production code so that a test could call it.
     */
    private static void resetMariadbSchema() {
        String url = mariadbUrl();
        int lastSlash = url.lastIndexOf('/');
        String server = url.substring(0, lastSlash + 1);
        String database = url.substring(lastSlash + 1);
        int query = database.indexOf('?');
        String options = "";
        if (query >= 0) {
            options = database.substring(query);
            database = database.substring(0, query);
        }
        if (!database.matches("[A-Za-z0-9_]+")) {
            // Refused rather than escaped: this string is interpolated into DDL,
            // and a test helper that quietly accepts an injectable database name
            // is a test helper somebody will later copy into something that runs
            // against a real server.
            throw new IllegalArgumentException(
                    "refusing to reset a database whose name is not a plain identifier: "
                            + database);
        }

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                server + options,
                System.getenv("SOULBIND_TEST_MARIADB_USER"),
                System.getenv("SOULBIND_TEST_MARIADB_PASSWORD"));
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            statement.execute("CREATE DATABASE `" + database + "`");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(
                    "cannot reset the test schema at " + server + database
                            + ". A test run against a database carrying another test's rows "
                            + "proves nothing, so this fails rather than continuing.", e);
        }
    }

    /**
     * Any one backend, for a test that needs storage but does not depend on
     * which.
     *
     * <p>Exists so such a test does not have to NAME a backend. The transport
     * tests are the case: signing and replay machinery is backend-independent,
     * so parameterising them over both would multiply runtime to re-prove
     * something the storage tests already cover — but naming one would give a
     * test compile-time knowledge of which database is in use, which is exactly
     * what the storage seam guard exists to prevent. Asking for "any" says what
     * is actually meant.
     */
    public static Backend any() {
        return (Backend) available().findFirst().orElseThrow().get()[0];
    }

    /**
     * A connection URL for a backend, without the caller having to name one.
     *
     * <p>Exists for the same reason as {@link #any()}: a test that needs a
     * working config file needs a URL, and writing one by hand would put a
     * backend name -- and a driver's URL scheme -- in a test whose subject is
     * something else entirely. The storage seam guard fired on exactly that.
     */
    public static String jdbcUrlFor(Backend backend, Path tempDir) {
        return switch (backend) {
            case SQLITE -> "jdbc:sqlite:"
                    + tempDir.resolve("soulbind-test.db").toString().replace('\\', '/');
            case MARIADB -> mariadbUrl();
        };
    }

    /** The name to write into a config file's {@code storage.backend}. */
    public static String configNameFor(Backend backend) {
        return backend.configName();
    }

    /**
     * Opens a store with write serialisation disabled, so races are reachable.
     *
     * <p>The point of the concurrency contract suite. On SQLite the single-writer
     * executor silently supplied correctness the repositories had not earned for
     * two phases; opening without it makes a check-then-act fail here rather
     * than in a session, or in production.
     *
     * <p>MariaDB has no executor to disable, so this is the ordinary open there
     * — which is the whole point: the two backends should then behave the same.
     */
    public static Storage openUnserialised(Backend backend, Path tempDir) {
        return switch (backend) {
            case SQLITE -> Storage.openWithoutWriteSerialisation(
                    Backend.SQLITE, jdbcUrlFor(Backend.SQLITE, tempDir), null, null);
            case MARIADB -> {
                resetMariadbSchema();
                yield Storage.open(
                        Backend.MARIADB,
                        mariadbUrl(),
                        System.getenv("SOULBIND_TEST_MARIADB_USER"),
                        System.getenv("SOULBIND_TEST_MARIADB_PASSWORD"));
            }
        };
    }

    /**
     * The backends available in this environment. Always at least SQLite.
     *
     * <p><b>Where both are REQUIRED, absence is a failure rather than a
     * narrowing.</b> `SOULBIND_TEST_MARIADB_URL` being unset silently halves the
     * storage battery — 402 tests instead of 471 — and a session that lost it
     * would stay green while proving half of what its gate asks for. The
     * workstation legitimately has no MariaDB, so the requirement cannot be
     * unconditional; the run stage that provides one sets
     * {@code SOULBIND_REQUIRE_MARIADB=1} and thereby promises it.
     *
     * <p>Same shape as the tag-selected task guard in the build conventions: the
     * environment that supplies a capability is the environment that asserts it
     * arrived.
     */
    public static Stream<Arguments> available() {
        if (mariadbRequired() && !mariadbAvailable()) {
            throw new IllegalStateException(
                    "SOULBIND_REQUIRE_MARIADB is set, so this environment promised a MariaDB "
                            + "server, but SOULBIND_TEST_MARIADB_URL is unset. Running only "
                            + "SQLite here would quietly halve the storage battery and report "
                            + "green for half the coverage the gate asks for.");
        }
        return mariadbAvailable()
                ? Stream.of(Arguments.of(Backend.SQLITE), Arguments.of(Backend.MARIADB))
                : Stream.of(Arguments.of(Backend.SQLITE));
    }

    /** Whether this environment has promised a MariaDB server. */
    public static boolean mariadbRequired() {
        String flag = System.getenv("SOULBIND_REQUIRE_MARIADB");
        return flag != null && !flag.isBlank() && !flag.equals("0");
    }
}
