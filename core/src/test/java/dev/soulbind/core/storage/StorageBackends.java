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
 * <p><b>MariaDB is skipped when no server is reachable, and that skip is
 * visible.</b> It is not silently treated as a pass: {@link #mariadbUrl()}
 * returns null and the parameter source omits it, and the test class reports
 * how many backends actually ran. Availability is read from
 * {@code SOULBIND_TEST_MARIADB_URL} so a workstation without a database server
 * can still run the cheap tiers, while the containerised battery supplies one
 * and gets both.
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

    /** The backends available in this environment. Always at least SQLite. */
    public static Stream<Arguments> available() {
        return mariadbAvailable()
                ? Stream.of(Arguments.of(Backend.SQLITE), Arguments.of(Backend.MARIADB))
                : Stream.of(Arguments.of(Backend.SQLITE));
    }
}
