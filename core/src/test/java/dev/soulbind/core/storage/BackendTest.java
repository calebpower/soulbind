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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What each backend needs, asked directly.
 *
 * <p>These answers exist so the doctor can check an installation without
 * learning which database is in use — the storage seam caught it doing exactly
 * that, and the knowledge moved here. Which meant the doctor's tests became the
 * only thing exercising them, and only ever against the backend this
 * workstation has, so a mutation sweep found every branch for the other one
 * surviving.
 *
 * <p>Asserted here rather than through the doctor because these are total
 * functions over a two-value enum: the exhaustive form is cheap and does not
 * depend on which database happens to be reachable.
 */
class BackendTest {

    @Test
    @DisplayName("every backend says whether it needs a username, and they differ")
    void credentialsDifferByBackend() {
        // The property worth pinning is the DIFFERENCE. A version answering the
        // same for both would make the doctor's "storage.user is set and this
        // backend ignores it" warning fire on everything or nothing, and either
        // way it stops meaning anything.
        assertFalse(Backend.SQLITE.usesCredentials(),
                "SQLite is a file; a username for it is a setting that does nothing");
        assertTrue(Backend.MARIADB.usesCredentials(),
                "MariaDB is a server and a connection to it needs a user");

        long needing = java.util.Arrays.stream(Backend.values())
                .filter(Backend::usesCredentials).count();
        assertTrue(needing > 0 && needing < Backend.values().length,
                "every backend answered the same way, so the doctor's warning is vacuous");
    }

    @Test
    @DisplayName("a file-backed backend names the directory it must be able to write")
    void writableDirectoryForFiles() {
        Optional<Path> dir = Backend.SQLITE.writableDirectory("jdbc:sqlite:/var/lib/soulbind/s.db");

        assertTrue(dir.isPresent(), "SQLite keeps a file and named no directory to write");
        assertEquals(Path.of("/var/lib/soulbind"), dir.get());
    }

    @Test
    @DisplayName("a server-backed backend needs no local directory at all")
    void noDirectoryForServers() {
        // Not merely a different directory -- none. The doctor must not report
        // a missing directory for a database that lives on another host.
        assertTrue(Backend.MARIADB.writableDirectory(
                        "jdbc:mariadb://127.0.0.1:3306/soulbind").isEmpty(),
                "a network backend was reported as needing a local directory");
    }

    @Test
    @DisplayName("a URL that names no file yields no directory, rather than a guess")
    void urlsWithoutFiles() {
        // Each of these reaches a different branch, and each would otherwise
        // have the doctor demanding a directory that is not part of the
        // configuration at all.
        assertTrue(Backend.SQLITE.writableDirectory(null).isEmpty(), "null url");
        assertTrue(Backend.SQLITE.writableDirectory("").isEmpty(), "empty url");
        assertTrue(Backend.SQLITE.writableDirectory("postgres://elsewhere").isEmpty(),
                "a url for a different driver");
        assertTrue(Backend.SQLITE.writableDirectory("jdbc:sqlite:").isEmpty(),
                "a url naming no path");
        assertTrue(Backend.SQLITE.writableDirectory("jdbc:sqlite::memory:").isEmpty(),
                "an in-memory database has no directory to write");
    }

    @Test
    @DisplayName("a relative location is reported as relative, and an absolute one is not")
    void relativeLocations() {
        // Both directions. A version answering true for everything would have
        // the doctor warning about every correct installation, which is how a
        // warning becomes something operators skip past.
        assertTrue(Backend.SQLITE.isRelativeLocation("jdbc:sqlite:soulbind.db"),
                "a bare filename resolves against the working directory");
        assertTrue(Backend.SQLITE.isRelativeLocation("jdbc:sqlite:data/soulbind.db"));

        assertFalse(Backend.SQLITE.isRelativeLocation("jdbc:sqlite:/var/lib/soulbind/s.db"),
                "an absolute path was reported as relative");
        assertFalse(Backend.SQLITE.isRelativeLocation("jdbc:sqlite::memory:"));
        assertFalse(Backend.SQLITE.isRelativeLocation(null));
        assertFalse(Backend.MARIADB.isRelativeLocation("jdbc:mariadb://host/db"),
                "a network backend has no location to be relative");
    }

    @Test
    @DisplayName("config names round-trip, and nothing else parses")
    void configNames() {
        for (Backend backend : Backend.values()) {
            assertEquals(backend, Backend.fromConfigName(backend.configName()).orElseThrow());
            assertEquals(backend,
                    Backend.fromConfigName("  " + backend.configName().toUpperCase(
                            java.util.Locale.ROOT) + "  ").orElseThrow(),
                    "backend names should not depend on case or stray whitespace");
        }
        assertTrue(Backend.fromConfigName("postgres").isEmpty());
        assertTrue(Backend.fromConfigName("").isEmpty());
        assertTrue(Backend.fromConfigName(null).isEmpty());
    }
}
