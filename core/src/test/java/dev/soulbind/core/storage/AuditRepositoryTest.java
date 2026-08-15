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

import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditQuery;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Audit storage, against every available backend.
 *
 * <p>What this does NOT prove: that every action which should be audited is
 * audited. That is a completeness claim, and it is asserted from both sides by
 * the simulated-user tier — the shadow model predicts the rows that must exist,
 * and no row may exist the model cannot account for. These tests prove the
 * storage mechanism underneath that claim.
 */
class AuditRepositoryTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("assigns monotonic sequence numbers")
    void assignsMonotonicSequences(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            AuditRepository audit = storage.audit();

            assertEquals(0L, audit.highestSequence(), "a fresh log starts at 0");

            AuditEntry first = audit.append(
                    AuditEntry.of(Instant.now(), "admin:boot", "connector.registered", Map.of()));
            AuditEntry second = audit.append(
                    AuditEntry.of(Instant.now(), "admin:boot", "connector.registered", Map.of()));

            assertEquals(1L, first.sequence());
            assertEquals(2L, second.sequence());
            assertEquals(2L, audit.highestSequence());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("round-trips every field, including structured detail")
    void roundTripsFields(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Instant when = Instant.ofEpochMilli(1_700_000_000_000L);
            AuditEntry written = storage.audit().append(new AuditEntry(
                    0L, when, "connector:abc", "identity.linked",
                    "subject-1", "kindA:id-1", "gate.x",
                    Map.of("proof", "link-code", "attempts", 2)));

            List<AuditEntry> back = storage.audit().query(AuditQuery.recent(10));
            assertEquals(1, back.size());
            AuditEntry read = back.get(0);

            assertEquals(written.sequence(), read.sequence());
            assertEquals(when, read.at());
            assertEquals("connector:abc", read.actor());
            assertEquals("identity.linked", read.action());
            assertEquals("subject-1", read.subjectId());
            assertEquals("kindA:id-1", read.identityRef());
            assertEquals("gate.x", read.gate());
            assertEquals("link-code", read.detail().get("proof"));
            assertEquals(2, ((Number) read.detail().get("attempts")).intValue());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("survives hostile text in detail and actor")
    void survivesHostileText(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            // Drawn from corpus/hostile-inputs.txt: astral-plane (four-byte
            // UTF-8), RTL override, zero-width, and SQL-shaped text. The last is
            // not a SQL-injection test -- parameters make that structurally
            // impossible -- it is a "does the value survive intact" test.
            String astral = "😀🤖";
            String rtl = "‮txet desrever";
            String zeroWidth = "a​b";
            String sqlish = "'; DROP TABLE audit; --";

            storage.audit().append(new AuditEntry(
                    0L, Instant.now(), sqlish, "hostile.test", null, null, null,
                    Map.of("astral", astral, "rtl", rtl, "zeroWidth", zeroWidth)));

            AuditEntry read = storage.audit().query(AuditQuery.recent(10)).get(0);
            assertEquals(sqlish, read.actor(), "actor text was altered in storage");
            assertEquals(astral, read.detail().get("astral"),
                    "four-byte UTF-8 did not survive; the column or connection charset is wrong");
            assertEquals(rtl, read.detail().get("rtl"));
            assertEquals(zeroWidth, read.detail().get("zeroWidth"));

            // The table is still there, which is the other half of the point.
            assertEquals(1, storage.audit().query(AuditQuery.recent(10)).size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("filters by actor, subject and action")
    void filters(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            AuditRepository audit = storage.audit();
            audit.append(new AuditEntry(0L, Instant.now(), "a", "x", "s1", null, null, Map.of()));
            audit.append(new AuditEntry(0L, Instant.now(), "b", "y", "s2", null, null, Map.of()));
            audit.append(new AuditEntry(0L, Instant.now(), "a", "y", "s1", null, null, Map.of()));

            assertEquals(2, audit.query(
                    new AuditQuery(null, null, "a", null, null, 100)).size());
            assertEquals(2, audit.query(
                    new AuditQuery(null, null, null, "s1", null, 100)).size());
            assertEquals(2, audit.query(
                    new AuditQuery(null, null, null, null, "y", 100)).size());
            assertEquals(1, audit.query(
                    new AuditQuery(null, null, "a", "s1", "y", 100)).size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("caps an unbounded query rather than returning everything")
    void capsLimit(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            for (int i = 0; i < 20; i++) {
                storage.audit().append(
                        AuditEntry.of(Instant.now(), "a", "bulk", Map.of("i", i)));
            }
            // A caller asking for more than MAX_LIMIT gets MAX_LIMIT, not an
            // error and not everything. An unbounded audit query from an
            // authenticated endpoint is a way to exhaust memory.
            AuditQuery huge = new AuditQuery(null, null, null, null, null, 999_999);
            assertEquals(AuditQuery.MAX_LIMIT, huge.limit());
            assertEquals(20, storage.audit().query(huge).size());

            assertEquals(5, storage.audit().query(AuditQuery.recent(5)).size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("concurrent appends produce no duplicate or missing sequence")
    void concurrentAppendsAreUnique(Backend backend) throws Exception {
        // Tier 8 in miniature. The assertion is about the RESOURCE read back --
        // the set of sequences in storage -- never about how many calls returned
        // successfully, which would pass even if two writers collided.
        final int writers = 8;
        final int perWriter = 25;
        final int expected = writers * perWriter;

        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            Set<Long> assigned = new ConcurrentSkipListSet<>();
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int w = 0; w < writers; w++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            assigned.add(storage.audit()
                                    .append(AuditEntry.of(
                                            Instant.now(), "racer", "concurrent", Map.of()))
                                    .sequence());
                        }
                        return null;
                    }));
                }
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers did not finish");

                // Every future is checked. Without this, a writer that threw was
                // simply a writer that wrote nothing, and the run looked like
                // fewer appends rather than like failures -- which is exactly how
                // 155 primary-key violations read as "45 sequences" instead of
                // "155 appends threw". awaitTermination returning true says the
                // threads stopped, not that they succeeded.
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(expected, storage.audit().highestSequence(),
                    "highest sequence disagrees with the number of appends, so a sequence was "
                            + "reused or skipped");
            assertEquals(expected, assigned.size(),
                    "two appends were given the same sequence number");

            List<AuditEntry> all = storage.audit().query(
                    new AuditQuery(null, null, null, null, "concurrent", AuditQuery.MAX_LIMIT));
            assertEquals(expected, all.size(), "rows are missing from storage");
            for (int i = 0; i < all.size(); i++) {
                assertEquals(i + 1L, all.get(i).sequence(), "sequence has a gap at position " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("migrations are idempotent: a second open rebuilds nothing")
    void migrationsAreIdempotent(Backend backend) {
        try (Storage first = StorageBackends.open(backend, tempDir)) {
            first.audit().append(AuditEntry.of(Instant.now(), "a", "before-reopen", Map.of()));
        }
        // Reopening runs migrations again. If they were not idempotent this
        // either throws or silently drops the row written above.
        //
        // reopen(), not open(): open() hands out a CLEAN store, which is what
        // keeps tests from seeing each other's rows. Using it here would wipe
        // the row this test just wrote and the assertion below would fail for a
        // reason that has nothing to do with migrations.
        try (Storage second = StorageBackends.reopen(backend, tempDir)) {
            List<AuditEntry> back = second.audit().query(AuditQuery.recent(10));
            assertEquals(1, back.size(), "reopening lost data, so migrations are not idempotent");
            assertEquals("before-reopen", back.get(0).action());
            assertNotNull(second.audit());
        }
    }
}
