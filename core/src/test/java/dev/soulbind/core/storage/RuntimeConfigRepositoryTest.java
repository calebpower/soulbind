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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runtime configuration storage, against every available backend.
 *
 * <p><b>Nothing executed a line of this.</b> Every mutant in
 * {@code JdbcRuntimeConfigRepository} came back "no coverage" — the reads, the
 * upsert and the delete alike. It is what {@code config.set} writes and what the
 * doctor reads back, so a silent failure here surfaces to an operator as "the
 * setting did not take", with nothing to grep for and no row to look at.
 *
 * <p>The upsert is the part worth the most attention: it tries an UPDATE first
 * and falls back to an INSERT, which is two statements where a careless reading
 * sees one. Both orders are exercised here — a key that exists and a key that
 * does not — because getting that backwards produces a repository that appears
 * to work until two writers meet.
 */
class RuntimeConfigRepositoryTest {

    @TempDir
    Path tempDir;

    private static final Instant AT = Instant.parse("2026-03-01T12:00:00Z");

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a key nobody has set reads as absent, not as empty")
    void absentIsAbsent(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            // Absent and "set to the empty string" are different answers, and
            // the caller distinguishes them: one falls back to the file's value,
            // the other overrides it with nothing.
            assertTrue(storage.runtimeConfig().get("nobody.set.this").isEmpty());
            assertTrue(storage.runtimeConfig().all().isEmpty(),
                    storage.runtimeConfig().all()::toString);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a value set is a value read back")
    void setThenGet(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.runtimeConfig().set("linking.codettlseconds", "900", AT, "cli");

            assertEquals(
                    "900",
                    storage.runtimeConfig().get("linking.codettlseconds").orElse(null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("setting an existing key REPLACES it rather than adding a second row")
    void setIsAnUpsert(Backend backend) {
        // The UPDATE-then-INSERT path, in the order that exercises the UPDATE.
        // A repository that always inserted would accumulate rows and `get`
        // would answer whichever the database felt like -- intermittently, and
        // differently on each backend.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.runtimeConfig().set("linking.codettlseconds", "900", AT, "cli");
            storage.runtimeConfig().set(
                    "linking.codettlseconds", "1800", AT.plusSeconds(60), "admin");

            assertEquals(
                    "1800",
                    storage.runtimeConfig().get("linking.codettlseconds").orElse(null),
                    "the second write did not take, so an operator's change was silently"
                            + " discarded");
            assertEquals(
                    1, storage.runtimeConfig().all().size(),
                    "the key exists twice, so which value wins is up to the database: "
                            + storage.runtimeConfig().all());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("all() returns every key, in a stable order")
    void allIsOrdered(Backend backend) {
        // Ordered, because `config.get` renders it for a person and an order
        // that changes between calls makes two readings impossible to compare.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.runtimeConfig().set("zeta", "3", AT, "cli");
            storage.runtimeConfig().set("alpha", "1", AT, "cli");
            storage.runtimeConfig().set("mu", "2", AT, "cli");

            Map<String, String> all = storage.runtimeConfig().all();

            assertEquals(List.of("alpha", "mu", "zeta"), List.copyOf(all.keySet()),
                    "the keys came back in an unstable order: " + all.keySet());
            assertEquals(Map.of("alpha", "1", "mu", "2", "zeta", "3"), all);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("clearing a key removes it and says it did")
    void clearRemoves(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.runtimeConfig().set("linking.codettlseconds", "900", AT, "cli");

            assertTrue(storage.runtimeConfig().clear("linking.codettlseconds"),
                    "clear reported doing nothing to a key that was there");
            assertTrue(storage.runtimeConfig().get("linking.codettlseconds").isEmpty(),
                    "the key survived being cleared, so the override an operator removed is"
                            + " still in force");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("clearing a key that was never there reports FALSE")
    void clearOfAnAbsentKey(Backend backend) {
        // The return value is the whole point: it is how the CLI tells an
        // operator "there was no override to remove" rather than implying it
        // removed one. Reporting true would have somebody believe a setting had
        // reverted when it never existed.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            assertFalse(storage.runtimeConfig().clear("nobody.set.this"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a cleared key can be set again, through the INSERT path")
    void setAfterClear(Backend backend) {
        // The other order: UPDATE matches nothing, so the INSERT fallback runs.
        // A repository whose fallback was broken would appear to work for the
        // life of a process and fail the first time an operator changed their
        // mind twice.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.runtimeConfig().set("linking.codettlseconds", "900", AT, "cli");
            storage.runtimeConfig().clear("linking.codettlseconds");
            storage.runtimeConfig().set("linking.codettlseconds", "300", AT, "cli");

            assertEquals(
                    "300",
                    storage.runtimeConfig().get("linking.codettlseconds").orElse(null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("what was written survives a reopen")
    void survivesAReopen(Backend backend) {
        // Runtime config is the one store whose whole purpose is outliving the
        // process that wrote it: an operator sets an override and expects it
        // after a restart.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.runtimeConfig().set("linking.codettlseconds", "900", AT, "cli");
        }
        try (Storage reopened = StorageBackends.reopen(backend, tempDir)) {
            assertEquals(
                    "900",
                    reopened.runtimeConfig().get("linking.codettlseconds").orElse(null),
                    "an override did not survive a restart, which is the only thing it is"
                            + " for");
        }
    }
}
