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

package dev.soulbind.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.audit.AuditQuery;
import dev.soulbind.core.events.EventEmitter;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import dev.soulbind.core.storage.StorageBackends;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Linking: issue, redeem, and everything that must not happen.
 *
 * <p>Two claims here are load-bearing. <b>Exactly one redeem</b> — a code that
 * leaks and is typed by two people must link one of them and refuse the other,
 * and the refusal must be a refusal rather than a second silent link. And
 * <b>symmetry</b> — either side can issue, because a system where the chat
 * platform is implicitly the root of identity is a different system from the
 * one specified.
 *
 * <p>Every test runs against both backends. The single-use mechanism is one SQL
 * statement whose behaviour under concurrency differs between them, so proving
 * it on one proves nothing about the other — which is exactly the mistake the
 * audit sequence bug turned out to be.
 */
class LinkingServiceTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.ofEpochSecond(1_700_000_000L);
    private static final Duration TTL = Duration.ofMinutes(10);

    /** A clock the tests move deliberately, so TTL edges are reachable. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(T0);

        @Override
        public Instant instant() {
            return now.get();
        }

        void set(Instant at) {
            now.set(at);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /**
     * A whole linking stack, closed with the test.
     *
     * <p>AutoCloseable so `try (Fixture f = ...)` reads naturally. Holding the
     * store in a separate try-with-resources whose variable is never used
     * produces a compiler warning for every test, and a suite that warns ten
     * times is a suite whose warnings nobody reads.
     */
    private record Fixture(Storage storage, LinkingService linking, MovableClock clock)
            implements AutoCloseable {
        @Override
        public void close() {
            storage.close();
        }
    }

    private Fixture fixture(Backend backend) {
        Storage storage = StorageBackends.open(backend, tempDir);
        MovableClock clock = new MovableClock();
        return new Fixture(
                storage,
                new LinkingService(
                        new EventEmitter(storage.events(), clock),
                        storage.identities(), storage.linkCodes(), storage.platformKinds(),
                        storage.audit(), clock, TTL),
                clock);
    }

    // --- the happy path, both directions --------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a code issued by one side and redeemed by the other links them")
    void linksTwoAccounts(Backend backend) {
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");

            LinkingService.Result result =
                    f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", "Alex too");

            LinkingService.Result.Linked linked =
                    assertInstanceOf(LinkingService.Result.Linked.class, result);

            List<Identity> graph = storage.identities().identitiesOf(linked.subject().id());
            assertEquals(2, graph.size());
            assertEquals(
                    Set.of("kind-a:acct-1", "kind-b:acct-2"),
                    Set.copyOf(graph.stream().map(Identity::ref).toList()));

            // The graph is asserted by READING IT BACK, not by trusting the
            // response. A response can be right about work that did not persist.
            assertEquals(
                    linked.subject().id(),
                    storage.identities().subjectOf("kind-b", "acct-2").orElseThrow().id());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("every entry point registers the platform kind it was handed")
    void learnsPlatformKinds(Backend backend) {
        // Core has no compiled-in list of platforms -- it learns them from what
        // connectors do, and `soulbind doctor`, the admin surface and the
        // policy engine all read that list back. Deleting any of the three
        // `kinds.seen(...)` calls left every assertion in this file green:
        // linking worked, the graph read back correctly, and core simply never
        // learned that the platform existed. Mutation coverage found all three.
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            assertFalse(storage.platformKinds().isKnown("kind-a"),
                    "the fixture started with the kind already registered, so this test"
                            + " would pass without anything registering it");

            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            assertTrue(storage.platformKinds().isKnown("kind-a"),
                    "issuing a code did not register the issuing platform kind");

            assertFalse(storage.platformKinds().isKnown("kind-b"));
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", "Alex too");
            assertTrue(storage.platformKinds().isKnown("kind-b"),
                    "redeeming did not register the redeeming platform kind");

            assertFalse(storage.platformKinds().isKnown("kind-c"));
            f.linking().attest("conn-c", "kind-c", "acct-3", "Alex elsewhere", "oauth");
            assertTrue(storage.platformKinds().isKnown("kind-c"),
                    "attesting did not register the attested platform kind");

            assertEquals(
                    Set.of("kind-a", "kind-b", "kind-c"),
                    Set.copyOf(storage.platformKinds().list()),
                    "the registry does not list exactly the kinds these calls used");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("symmetry: the same flow works with the sides reversed")
    void isSymmetric(Backend backend) {
        // Core never learns which pairing is "normal", because it never sees a
        // pairing. If this ever needs a special case, the seam is gone.
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-b", "kind-b", "acct-2", null);
            LinkingService.Result result =
                    f.linking().redeem("conn-a", code.code(), "kind-a", "acct-1", null);

            LinkingService.Result.Linked linked =
                    assertInstanceOf(LinkingService.Result.Linked.class, result);
            assertEquals(
                    2, storage.identities().identitiesOf(linked.subject().id()).size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a code is accepted in whatever case and spacing the person typed")
    void normalisesTypedCode(Backend backend) {
        try (Fixture f = fixture(backend)) {
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);

            String typed = "  " + code.code().toLowerCase(java.util.Locale.ROOT) + " ";
            assertInstanceOf(
                    LinkingService.Result.Linked.class,
                    f.linking().redeem("conn-b", typed, "kind-b", "acct-2", null),
                    "a person typing a code in lower case with a stray space has not made a "
                            + "mistake worth refusing");
        }
    }

    // --- exactly one redeem ---------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("EXACTLY ONE of many concurrent redeems succeeds")
    void exactlyOneRedeem(Backend backend) throws Exception {
        // The gate's concurrency claim. A leaked code typed by twenty people
        // must link one and refuse nineteen -- and the nineteen must be
        // REFUSALS, not silent no-ops that leave each person believing they
        // linked.
        final int racers = 20;

        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);

            ExecutorService pool = Executors.newFixedThreadPool(racers);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<LinkingService.Result> results = new ConcurrentLinkedQueue<>();
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < racers; i++) {
                    final int n = i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        results.add(f.linking().redeem(
                                "conn-b", code.code(), "kind-b", "acct-" + n, null));
                        return null;
                    }));
                }
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
                // Checked, not assumed: a racer that threw would otherwise look
                // like a racer that was refused.
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            long linked = results.stream()
                    .filter(r -> r instanceof LinkingService.Result.Linked).count();
            assertEquals(1, linked, () -> "expected exactly one link, got " + linked);
            assertEquals(racers, results.size(), "every racer must get an answer");

            for (LinkingService.Result result : results) {
                if (result instanceof LinkingService.Result.Denied denied) {
                    assertEquals(
                            LinkingService.Refusal.ALREADY_REDEEMED,
                            denied.refusal(),
                            "a loser must be told the code was used, not given some other "
                                    + "reason that sends them to fix the wrong thing");
                }
            }

            // And the RESOURCE agrees: one subject, two identities. Asserting
            // only the responses would pass even if two writers both linked.
            List<Identity> graph = f.linking().graphOf("kind-a", "acct-1");
            assertEquals(
                    2, graph.size(),
                    () -> "the graph read back holds " + graph.size() + " identities, so more "
                            + "than one redeem took effect");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a second redeem of the same code is refused, not silently repeated")
    void secondRedeemRefused(Backend backend) {
        try (Fixture f = fixture(backend)) {
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", null);

            LinkingService.Result.Denied denied = assertInstanceOf(
                    LinkingService.Result.Denied.class,
                    f.linking().redeem("conn-b", code.code(), "kind-b", "acct-3", null));
            assertEquals(LinkingService.Refusal.ALREADY_REDEEMED, denied.refusal());
        }
    }

    // --- TTL boundaries -------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a code is still good at the exact instant it expires")
    void usableAtTheBoundary(Backend backend) {
        // Exclusive expiry. Somebody typing at the last second should succeed,
        // and "expired" reading true at the stroke of the deadline makes the
        // advertised lifetime a lie by one second.
        try (Fixture f = fixture(backend)) {
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            f.clock().set(code.expiresAt());

            assertInstanceOf(
                    LinkingService.Result.Linked.class,
                    f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("one millisecond later it is expired, and says so")
    void expiredJustAfterTheBoundary(Backend backend) {
        try (Fixture f = fixture(backend)) {
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            f.clock().set(code.expiresAt().plusMillis(1));

            LinkingService.Result.Denied denied = assertInstanceOf(
                    LinkingService.Result.Denied.class,
                    f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", null));
            assertEquals(
                    LinkingService.Refusal.EXPIRED,
                    denied.refusal(),
                    "an expired code must say EXPIRED, not ALREADY_REDEEMED -- different "
                            + "problems with different fixes");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an expired code is not consumed, so the reason stays truthful")
    void expiredCodeIsNotClaimed(Backend backend) {
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            f.clock().set(code.expiresAt().plusSeconds(1));
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", null);

            assertFalse(
                    storage.linkCodes().find(code.code()).orElseThrow().isRedeemed(),
                    "an expired code that gets marked redeemed would report ALREADY_REDEEMED "
                            + "on the next attempt, sending the person to ask why somebody "
                            + "else used their code");
        }
    }

    // --- refusals that are their own reason -----------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a code that was never issued is unknown, not malformed")
    void unknownCode(Backend backend) {
        try (Fixture f = fixture(backend)) {
            LinkingService.Result.Denied denied = assertInstanceOf(
                    LinkingService.Result.Denied.class,
                    f.linking().redeem("conn-b", "BBBBBBBB", "kind-b", "acct-2", null));
            assertEquals(LinkingService.Refusal.UNKNOWN_CODE, denied.refusal());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a code outside the alphabet is rejected, never repaired into another code")
    void malformedCodeIsNotRepaired(Backend backend) {
        // Repairing would be worse than refusing: mapping O to 0 silently
        // redeems a DIFFERENT code, linking the wrong account with no error
        // anybody can see.
        try (Fixture f = fixture(backend)) {
            for (String typed : new String[] {"O0O0O0O0", "!!!!!!!!", "", "   "}) {
                LinkingService.Result.Denied denied = assertInstanceOf(
                        LinkingService.Result.Denied.class,
                        f.linking().redeem("conn-b", typed, "kind-b", "acct-2", null),
                        () -> "accepted '" + typed + "'");
                assertEquals(LinkingService.Refusal.UNKNOWN_CODE, denied.refusal());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("redeeming with the account the code was issued for is refused")
    void sameAccountRefused(Backend backend) {
        // It would create a subject with one identity and the appearance of a
        // completed link: the person believes they are linked and no gate agrees.
        try (Fixture f = fixture(backend)) {
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            LinkingService.Result.Denied denied = assertInstanceOf(
                    LinkingService.Result.Denied.class,
                    f.linking().redeem("conn-a", code.code(), "kind-a", "acct-1", null));
            assertEquals(LinkingService.Refusal.SAME_ACCOUNT, denied.refusal());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("linking two accounts that already belong to different people is refused")
    void alreadyLinkedRefused(Backend backend) {
        try (Fixture f = fixture(backend)) {
            LinkCodeRecord first = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            f.linking().redeem("conn-b", first.code(), "kind-b", "acct-2", null);

            LinkCodeRecord second = f.linking().issue("conn-a", "kind-a", "acct-3", null);
            f.linking().redeem("conn-b", second.code(), "kind-b", "acct-4", null);

            // Now try to bridge the two subjects.
            LinkCodeRecord bridge = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            LinkingService.Result.Denied denied = assertInstanceOf(
                    LinkingService.Result.Denied.class,
                    f.linking().redeem("conn-b", bridge.code(), "kind-b", "acct-4", null),
                    "merging two people is not offered, so this must refuse rather than guess");
            assertEquals(LinkingService.Refusal.ALREADY_LINKED, denied.refusal());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a third account joins an existing subject rather than starting a new one")
    void thirdAccountJoinsExistingSubject(Backend backend) {
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord first = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            LinkingService.Result.Linked linked = (LinkingService.Result.Linked)
                    f.linking().redeem("conn-b", first.code(), "kind-b", "acct-2", null);

            LinkCodeRecord second = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            LinkingService.Result.Linked third = (LinkingService.Result.Linked)
                    f.linking().redeem("conn-c", second.code(), "kind-c", "acct-3", null);

            assertEquals(
                    linked.subject().id(), third.subject().id(),
                    "a third platform account belongs to the same person, not a new one");
            assertEquals(3, storage.identities().identitiesOf(third.subject().id()).size());
        }
    }

    // --- unlink ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("unlink removes the identity but not the audit history")
    void unlinkIsHardForPolicyAndSoftForAudit(Backend backend) {
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", null);

            assertTrue(f.linking().unlink("conn-b", "kind-b", "acct-2"));

            // Hard for policy: gone, now, not marked inactive.
            assertTrue(
                    storage.identities().findIdentity("kind-b", "acct-2").isEmpty(),
                    "an unlink that left the row would leave policy unchanged, which is not "
                            + "an unlink");

            // Soft for audit: what happened still happened.
            assertTrue(
                    storage.audit().query(AuditQuery.recent(100)).stream()
                            .anyMatch(e -> "identity.linked".equals(e.action())),
                    "the link is still in the audit log");
            assertTrue(
                    storage.audit().query(AuditQuery.recent(100)).stream()
                            .anyMatch(e -> "identity.unlinked".equals(e.action())),
                    "and so is the unlink");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("re-linking creates a NEW identity rather than resurrecting the old one")
    void relinkCreatesNewIdentity(Backend backend) {
        // A resurrected row would silently carry its old verification date, and
        // policy asking "how long has this been proven" would get an answer
        // about an account that had been unlinked in between.
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            LinkingService.Result.Linked first = (LinkingService.Result.Linked)
                    f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", null);
            String originalIdentityId = first.redeemed().id();

            f.linking().unlink("conn-b", "kind-b", "acct-2");

            f.clock().set(T0.plusSeconds(3600));
            LinkCodeRecord again = f.linking().issue("conn-a", "kind-a", "acct-1", null);
            LinkingService.Result.Linked second = (LinkingService.Result.Linked)
                    f.linking().redeem("conn-b", again.code(), "kind-b", "acct-2", null);

            assertNotEquals(originalIdentityId, second.redeemed().id());
            assertEquals(
                    T0.plusSeconds(3600),
                    storage.identities().findIdentity("kind-b", "acct-2")
                            .orElseThrow().createdAt(),
                    "the new identity is new, including its dates");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("unlinking something that is not linked reports false rather than throwing")
    void unlinkAbsent(Backend backend) {
        try (Fixture f = fixture(backend)) {
            assertFalse(f.linking().unlink("conn-b", "kind-b", "nobody"));
        }
    }

    // --- audit ----------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the code itself is never written to the audit log")
    void codeIsNotAudited(Backend backend) {
        // Until it is redeemed or expires a code is a live secret. An audit log
        // readable by anyone holding config-management would otherwise be a
        // list of working codes.
        try (Fixture f = fixture(backend)) {
            Storage storage = f.storage();
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", null);

            String everything = storage.audit().query(AuditQuery.recent(100)).toString();
            assertFalse(
                    everything.contains(code.code()),
                    () -> "the audit log contains a live code: " + everything);
        }
    }
}
