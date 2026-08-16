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

import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditQuery;
import dev.soulbind.core.events.EventRecord;
import dev.soulbind.core.identity.LinkCodeRecord;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.EventType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The concurrency contract: <b>repository correctness must not depend on the
 * single-writer executor.</b>
 *
 * <p>Every test here opens storage with write serialisation <em>disabled</em>,
 * so writes genuinely interleave on SQLite too. Without that, this whole file
 * would pass on a workstation regardless of what the repositories do — which is
 * exactly what happened for two phases.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>The executor is a SQLite <em>deployment</em> necessity: that backend
 * permits one writer, and a pool of them makes the constraint intermittent
 * rather than absent. But it is not a correctness mechanism, and for two phases
 * it silently supplied correctness the repositories had not earned.
 *
 * <p>Three defects hid behind it, each found only when a multi-writer backend
 * finally ran:
 *
 * <ul>
 *   <li>audit sequences allocated by {@code SELECT MAX(seq)+1} — 200 concurrent
 *       appends produced 45 distinct sequences;
 *   <li>{@code platformKind.seen} as SELECT-then-INSERT;
 *   <li>{@code gate.seen} likewise — which reached a live HTTP 500.
 * </ul>
 *
 * <p>Each would have failed here on the first run. That is the point: a defect
 * that only a session can find is a defect found late, and the session tier
 * should be catching things a workstation genuinely cannot, not things a
 * workstation was merely configured not to.
 */
class StorageConcurrencyContractTest {

    @TempDir
    Path tempDir;

    private static final int THREADS = 12;

    /** Runs a body on many threads at once, surfacing every failure. */
    private static void race(int threads, ThrowingIntConsumer body) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    body.accept(n);
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "the race did not finish");
            // Every future checked. A worker that threw would otherwise look
            // like a worker that did less -- the exact reading that made 155
            // primary-key violations look like "45 sequences".
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int n) throws Exception;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("audit sequences are unique and gapless under real concurrency")
    void auditSequences(Backend backend) throws Exception {
        // The Phase 1 defect. Would have failed here on the first run.
        int perThread = 25;
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            Set<Long> assigned = new ConcurrentSkipListSet<>();

            race(THREADS, n -> {
                for (int i = 0; i < perThread; i++) {
                    assigned.add(storage.audit()
                            .append(AuditEntry.of(
                                    Instant.now(), "racer", "concurrent", Map.of()))
                            .sequence());
                }
            });

            int expected = THREADS * perThread;
            assertEquals(expected, assigned.size(), "two appends shared a sequence");
            assertEquals(expected, storage.audit().highestSequence());

            List<AuditEntry> all = storage.audit().query(
                    new AuditQuery(null, null, null, null, "concurrent", AuditQuery.MAX_LIMIT));
            assertEquals(expected, all.size(), "rows are missing");
            for (int i = 0; i < all.size(); i++) {
                assertEquals(i + 1L, all.get(i).sequence(), "a gap at position " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("event sequences are unique and gapless under real concurrency")
    void eventSequences(Backend backend) throws Exception {
        int perThread = 20;
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            Set<Long> assigned = new ConcurrentSkipListSet<>();

            race(THREADS, n -> {
                for (int i = 0; i < perThread; i++) {
                    assigned.add(storage.events()
                            .append(EventRecord.of(
                                    EventType.IDENTITY_VERIFIED, "s", "kind-a:acct", null,
                                    Map.of(), Instant.now()))
                            .sequence());
                }
            });

            assertEquals(THREADS * perThread, assigned.size());
            assertEquals(THREADS * perThread, storage.events().highestSequence());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("insert-if-absent survives every thread inserting the same key")
    void insertIfAbsentRaces(Backend backend) throws Exception {
        // The Phase 3/4 defect, in both places it occurred. This is the test
        // that would have turned a live 500 into a red build.
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            race(THREADS, n -> {
                storage.platformKinds().seen("kind-a", "conn-" + n);
                storage.policy().gateSeen("gate.x", "conn-" + n, null);
            });

            assertEquals(
                    1,
                    storage.platformKinds().list().stream().filter("kind-a"::equals).count(),
                    "concurrent seen() produced duplicate rows");
            assertEquals(
                    1,
                    storage.policy().gates().stream().filter("gate.x"::equals).count(),
                    "concurrent gateSeen() produced duplicate rows");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a link code is claimed by exactly one racer")
    void linkCodeClaim(Backend backend) throws Exception {
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            Instant now = Instant.now();
            storage.linkCodes().issue(new LinkCodeRecord(
                    "BCDFGHJK", "conn-a", "kind-a", "acct-1", null,
                    now, now.plusSeconds(600), null, null));

            AtomicInteger winners = new AtomicInteger();
            race(THREADS, n -> {
                if (storage.linkCodes().claim("BCDFGHJK", "conn-" + n, Instant.now())) {
                    winners.incrementAndGet();
                }
            });

            assertEquals(1, winners.get(), "a code was claimed more than once");
            assertTrue(storage.linkCodes().find("BCDFGHJK").orElseThrow().isRedeemed());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("one platform account is bound to exactly one subject")
    void identityBindRaces(Backend backend) throws Exception {
        // The uniqueness is a database constraint rather than a check before the
        // insert, precisely so this race resolves in the database. Asserted
        // rather than assumed.
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            AtomicInteger bound = new AtomicInteger();

            race(THREADS, n -> {
                var subject = storage.identities().createSubject(Instant.now());
                try {
                    storage.identities().bind(
                            subject.id(), "kind-a", "acct-1", null, Map.of(),
                            null, null, Instant.now());
                    bound.incrementAndGet();
                } catch (RuntimeException expected) {
                    // Somebody else got there. That is the constraint working.
                }
            });

            assertEquals(1, bound.get(), "one account was bound to more than one subject");
            assertTrue(storage.identities().findIdentity("kind-a", "acct-1").isPresent());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a cursor never goes backwards, however the acknowledgements interleave")
    void cursorMonotonicity(Backend backend) throws Exception {
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            for (int i = 0; i < 50; i++) {
                storage.events().append(EventRecord.of(
                        EventType.IDENTITY_VERIFIED, "s", "kind-a:acct", null, Map.of(),
                        Instant.now()));
            }

            // Threads acknowledging wildly different positions at once. The
            // cursor must end at the highest, never at whichever call landed
            // last.
            race(THREADS, n -> {
                for (int i = 0; i < 20; i++) {
                    storage.events().acknowledge("conn-1", (n * 4L) + i, Instant.now());
                }
            });

            long finalPosition = storage.events().cursorOf("conn-1");
            assertTrue(
                    finalPosition >= (THREADS - 1) * 4L,
                    () -> "the cursor ended at " + finalPosition + ", behind the highest "
                            + "acknowledged position -- a later ack overwrote an earlier, "
                            + "higher one");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a connector name is registered at most once")
    void connectorRegistrationRaces(Backend backend) throws Exception {
        try (Storage storage = StorageBackends.openUnserialised(backend, tempDir)) {
            AtomicInteger registered = new AtomicInteger();

            race(THREADS, n -> {
                try {
                    storage.connectors().register(
                            "same-name", "hash-" + n, Set.of(Capability.CODE_DISPLAY));
                    registered.incrementAndGet();
                } catch (RuntimeException expected) {
                    // The constraint doing its job.
                }
            });

            assertTrue(
                    registered.get() >= 1,
                    "nobody managed to register at all, which is not a race being handled");
            assertEquals(
                    registered.get(),
                    storage.connectors().list().stream()
                            .filter(c -> "same-name".equals(c.name())).count(),
                    "the number of rows disagrees with the number of successful registrations");
        }
    }
}
