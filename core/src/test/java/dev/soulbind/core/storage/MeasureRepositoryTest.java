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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Measure storage, against every available backend.
 *
 * <p>The write is an UPDATE that falls back to an INSERT — two statements where
 * a careless reading sees one, and the same shape as the runtime-config upsert
 * beside it. Both orders are exercised, because getting them backwards produces
 * a repository that appears to work until the second report arrives.
 *
 * <p>The overwrite is the property that matters most. This table is deliberately
 * not a time series: if a second report for the same (reference, name) inserted
 * instead of replacing, the table would grow without bound and the read would
 * start returning two answers to a question with one.
 */
class MeasureRepositoryTest {

    @TempDir
    Path tempDir;

    private static final Instant AT = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T12:15:00Z");
    private static final String REF = "game:9f2c";
    private static final String OTHER = "chat:1234";
    private static final long WEEK = 604_800L;

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("nothing reported reads as nothing, and an empty ref list asks nothing")
    void absentIsAbsent(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            assertEquals(List.of(), storage.measures().forRefs(List.of(REF), null));
            // `IN ()` is not valid SQL on either backend, and a subject with no
            // identities is an ordinary state rather than a caller's mistake.
            assertEquals(List.of(), storage.measures().forRefs(List.of(), null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("every column written is a column read back")
    void roundTrip(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.measures().report(REF, "playtime", 25_200L, WEEK, AT, "connector:reporter");

            List<MeasureRecord> found = storage.measures().forRefs(List.of(REF), null);
            assertEquals(1, found.size());
            MeasureRecord record = found.get(0);
            // Every value distinct, so a transposed pair of setters cannot pass.
            assertEquals(REF, record.identityRef());
            assertEquals("playtime", record.name());
            assertEquals(25_200L, record.value());
            assertEquals(WEEK, record.windowSeconds());
            assertEquals(AT, record.observedAt());
            assertEquals("connector:reporter", record.reportedBy());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a second report replaces the first rather than joining it")
    void reportOverwrites(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.measures().report(REF, "playtime", 100L, WEEK, AT, "connector:reporter");
            storage.measures().report(REF, "playtime", 999L, WEEK, LATER, "connector:reporter");

            List<MeasureRecord> found = storage.measures().forRefs(List.of(REF), null);
            assertEquals(1, found.size(),
                    () -> "the table grew a second row, so it is a time series after all: "
                            + found);
            assertEquals(999L, found.get(0).value());
            assertEquals(LATER, found.get(0).observedAt());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("names and references are separate keys")
    void namesAndRefsAreIndependent(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.measures().report(REF, "playtime", 1L, WEEK, AT, "c");
            storage.measures().report(REF, "posts", 2L, WEEK, AT, "c");
            storage.measures().report(OTHER, "playtime", 3L, WEEK, AT, "c");

            assertEquals(2, storage.measures().forRefs(List.of(REF), null).size(),
                    "two measures on one reference collapsed into one");
            assertEquals(3, storage.measures().forRefs(List.of(REF, OTHER), null).size());

            List<MeasureRecord> narrowed =
                    storage.measures().forRefs(List.of(REF, OTHER), "playtime");
            assertEquals(2, narrowed.size());
            assertTrue(narrowed.stream().allMatch(r -> r.name().equals("playtime")),
                    narrowed::toString);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("forget removes one reference's measures and leaves the rest")
    void forgetIsScoped(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.measures().report(REF, "playtime", 1L, WEEK, AT, "c");
            storage.measures().report(REF, "posts", 2L, WEEK, AT, "c");
            storage.measures().report(OTHER, "playtime", 3L, WEEK, AT, "c");

            assertEquals(2, storage.measures().forget(REF),
                    "forget must report what it removed, or a caller cannot tell it did nothing");
            assertEquals(List.of(), storage.measures().forRefs(List.of(REF), null));
            assertEquals(1, storage.measures().forRefs(List.of(OTHER), null).size(),
                    "forgetting one account's measures took another account's with it");

            assertEquals(0, storage.measures().forget("game:nobody"),
                    "forgetting nothing reported that it had removed something");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the longest legal identity reference fits")
    void longestLegalReferenceFits(Backend backend) {
        // A reference is `kind:id`, and the graph allows platform_kind up to 64
        // characters and platform_id up to 191 -- so 256 is legal and reachable.
        //
        // THIS TEST IS ONLY MEANINGFUL ON MARIADB, and that is the point.
        // SQLite ignores VARCHAR length entirely, so a column declared too
        // narrow passes every workstation run and raises error 1406 on the
        // first deployment, for exactly the accounts with long identifiers.
        // The column was 191 when this was written.
        String kind = "k".repeat(64);
        String id = "i".repeat(191);
        String ref = kind + ":" + id;
        assertEquals(256, ref.length(), "the widest legal reference is not 256 characters");

        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.measures().report(ref, "playtime", 7L, WEEK, AT, "connector:reporter");

            List<MeasureRecord> found = storage.measures().forRefs(List.of(ref), null);
            assertEquals(1, found.size(), "the widest legal reference did not round-trip");
            assertEquals(ref, found.get(0).identityRef(),
                    "the reference came back truncated, so two accounts could collide");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the longest legal measure name fits")
    void longestLegalNameFits(Backend backend) {
        String name = "n".repeat(64);
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.measures().report(REF, name, 1L, WEEK, AT, "connector:reporter");
            assertEquals(name, storage.measures().forRefs(List.of(REF), null).get(0).name());
        }
    }
}
