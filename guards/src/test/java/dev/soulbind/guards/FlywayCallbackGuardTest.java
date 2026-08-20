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

package dev.soulbind.guards;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No Flyway SQL callbacks live in the migration locations.
 *
 * <p><b>They deadlock SQLite.</b> Flyway opens a <em>second</em> connection to
 * execute a callback, and this project's SQLite pool is deliberately one
 * connection — SQLite permits exactly one writer, and a pool of several does not
 * make that untrue, it makes it intermittent. So a {@code beforeMigrate.sql}
 * waits thirty seconds for a connection the migration it precedes is still
 * holding, and then fails the boot with "Connection is not available, request
 * timed out".
 *
 * <p>Found by trying it. A callback was the third attempt at setting the
 * database charset before the tables are created; it looked like the idiomatic
 * Flyway answer, it is correctly discovered from the per-dialect location, and
 * it is unusable here. {@code docs/DECISIONS.md} 8.24.
 *
 * <p>The trap is that a callback added to the <em>mariadb</em> directory would
 * work — that pool has ten connections — and the identical file under
 * {@code common/} or {@code sqlite/} would take the whole product down at boot
 * on the backend a small deployment is most likely to be using. That asymmetry
 * is exactly the kind nobody discovers until it is in front of a user, so the
 * rule is all locations, not the dangerous ones.
 *
 * <p>The charset that wanted a callback is set in {@code Storage.migrate}
 * instead, on the pool's own connection.
 */
class FlywayCallbackGuardTest {

    private static final Path MIGRATIONS =
            SourceTree.repoRoot().resolve("core/src/main/resources/db/migration");

    /**
     * Flyway's SQL callback names, lower-cased for comparison.
     *
     * <p>All of them, not just the one that bit: {@code afterMigrate} runs on
     * the same second connection and deadlocks identically, and a reader who
     * finds only {@code beforeMigrate} forbidden will reasonably conclude the
     * others are fine.
     */
    private static final List<String> CALLBACKS = List.of(
            "beforemigrate", "beforemigrateerror", "aftermigrate", "aftermigrateerror",
            "beforeeachmigrate", "aftereachmigrate", "aftereachmigrateerror",
            "beforevalidate", "aftervalidate", "aftervalidateerror",
            "beforebaseline", "afterbaseline", "afterbaselineerror",
            "beforerepair", "afterrepair", "afterrepairerror",
            "beforeclean", "afterclean", "aftercleanerror",
            "beforeinfo", "afterinfo", "afterinfoerror",
            "beforeconnect", "afterconnect");

    @Test
    @DisplayName("no Flyway SQL callback exists under db/migration")
    void noCallbacksInTheMigrationLocations() throws IOException {
        assertTrue(Files.isDirectory(MIGRATIONS),
                () -> "no migration directory at " + MIGRATIONS + "; this guard is looking in"
                        + " the wrong place and would pass whatever was added");

        List<Path> found;
        try (var paths = Files.walk(MIGRATIONS)) {
            found = paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!name.endsWith(".sql")) {
                            return false;
                        }
                        String stem = name.substring(0, name.length() - 4);
                        // Flyway permits a description suffix after two
                        // underscores, so beforeMigrate__whatever.sql is also a
                        // callback and must not slip through on an exact match.
                        int suffix = stem.indexOf("__");
                        String base = suffix >= 0 ? stem.substring(0, suffix) : stem;
                        return CALLBACKS.contains(base);
                    })
                    .toList();
        }

        assertTrue(found.isEmpty(),
                () -> "Flyway SQL callbacks found under db/migration: " + found
                        + ". Flyway runs callbacks on a SECOND connection, and the SQLite"
                        + " pool is one connection by design, so this fails the boot after a"
                        + " thirty-second timeout on that backend. Whatever it does belongs"
                        + " in Storage.migrate, on the pool's own connection. DECISIONS 8.24.");
    }

    @Test
    @DisplayName("GUARD FIRES: a callback dropped into a migration location is rejected")
    void guardFiresOnAPlantedCallback() throws IOException {
        // The must-fail fixture, planted and removed rather than committed: a
        // committed one would have to live somewhere this guard does not look,
        // which would make it a fixture for a different rule.
        Path planted = MIGRATIONS.resolve("common/afterMigrate.sql");
        Files.writeString(planted, "SELECT 1;\n");
        try {
            boolean rejected = false;
            try {
                noCallbacksInTheMigrationLocations();
            } catch (AssertionError expected) {
                rejected = true;
            }
            assertTrue(rejected,
                    "a callback planted at " + planted + " was not rejected, so this guard"
                            + " would not have caught the one that deadlocked SQLite");
        } finally {
            Files.deleteIfExists(planted);
        }
    }
}
