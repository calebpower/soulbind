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

package dev.soulbind.connector.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a dashboard is told, and the one distinction it must not lose.
 *
 * <p>A read-only page has one way to be actively harmful: printing a confident
 * answer it does not have. "Not linked" during an outage sends an operator to
 * chase somebody whose links are fine, and the page gives no hint that anything
 * was wrong.
 */
class LinkDataSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private static String ok(String payload) {
        return "{\"schema\":1,\"ok\":true,\"payload\":" + payload + "}";
    }

    private LinkDataSource source(InMemoryTransport transport, boolean showSubjectId, Clock clock) {
        return new LinkDataSource(
                new SoulbindClient(transport, "cred", clock, new DecisionCache()),
                "game",
                Duration.ofSeconds(30),
                showSubjectId,
                clock);
    }

    private LinkDataSource source(InMemoryTransport transport) {
        return source(transport, false, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("it asks the read-side operation, not the admin one")
    void itAsksTheOperationThatNeedsNoAdminCapability() {
        // The whole security posture of this connector is one string.
        //
        // `subject.inspect` and `identity.describe` return the same thing --
        // core binds the same request type for both and its handler says so in
        // as many words. They differ only in which capability reaches them:
        // subject.inspect needs `config-management`, which also unlocks
        // rule.set, override.set, config.set, audit.query and identity.unlink.
        //
        // So a dashboard asking the wrong one holds a credential that can
        // rewrite every rule and unlink anybody, while every other assertion in
        // this file stays green -- the responses are identical. Nothing else
        // here can tell the difference, which is exactly why this exists.
        InMemoryTransport transport = InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}"));
        source(transport).player("p1");

        assertEquals(1, transport.sendCount(), "expected exactly one call to core");
        String request = transport.sent().get(0);

        assertTrue(
                request.contains("identity.describe"),
                () -> "the dashboard must ask identity.describe, which requires only "
                        + "link-state-reader -- the one capability that grants no mutation. "
                        + "Sent: " + request);
        assertFalse(
                request.contains("subject.inspect"),
                () -> "subject.inspect requires config-management -- an admin capability that "
                        + "also permits unlinking identities and rewriting every rule. A "
                        + "read-only dashboard must never need it. Sent: " + request);
    }

    @Test
    @DisplayName("an outage is UNKNOWN, never 'not linked'")
    void anOutageIsNotAnAnswer() {
        PlayerLinkView view = source(InMemoryTransport.always(ok("{}")).goDown()).player("p1");

        assertFalse(view.known(), "core did not answer, so nothing is known");
        assertFalse(view.linked());
        assertTrue(
                view.describe().contains("unknown"),
                () -> "a dashboard must not print a confident answer it does not have; got: "
                        + view.describe());
        assertFalse(
                view.describe().equals("not linked"),
                "'not linked' during an outage sends an operator to chase somebody whose "
                        + "links are perfectly fine, with nothing on the page to hint at it");
    }

    @Test
    @DisplayName("an outage is not cached, so recovery is visible immediately")
    void anOutageIsNotCached() {
        InMemoryTransport transport = InMemoryTransport.always(ok(
                "{\"linked\":true,\"subjectId\":\"s1\",\"identities\":["
                        + "{\"platformKind\":\"game\",\"proofMethod\":\"code\","
                        + "\"verifiedAtEpochSeconds\":100}]}"));
        LinkDataSource source = source(transport.goDown());

        assertFalse(source.player("p1").known());

        transport.comeBack();

        // Same instant on the clock: if the outage had been cached, this would
        // still be unknown for the whole TTL after core came back -- a
        // dashboard stuck reporting a problem that has ended.
        assertTrue(source.player("p1").known(), "the outage was cached and outlived itself");
    }

    @Test
    @DisplayName("a linked player's kinds and proofs are sorted and deduplicated")
    void kindsAndProofsAreStable() {
        PlayerLinkView view = source(InMemoryTransport.always(ok(
                "{\"linked\":true,\"subjectId\":\"s1\",\"identities\":["
                        + "{\"platformKind\":\"forum\",\"proofMethod\":\"code\"},"
                        + "{\"platformKind\":\"chat\",\"proofMethod\":\"code\"},"
                        + "{\"platformKind\":\"game\",\"proofMethod\":\"attestation\"}]}")))
                .player("p1");

        assertEquals(List.of("chat", "forum", "game"), view.kinds(),
                "an unsorted column reorders between refreshes and reads as data changing");
        assertEquals(List.of("attestation", "code"), view.proofMethods(),
                "the same proof method listed once per identity is noise, not information");
    }

    @Test
    @DisplayName("verified-at is the EARLIEST, so it does not move when a platform is added")
    void verifiedAtIsTheEarliest() {
        PlayerLinkView view = source(InMemoryTransport.always(ok(
                "{\"linked\":true,\"identities\":["
                        + "{\"platformKind\":\"game\",\"verifiedAtEpochSeconds\":500},"
                        + "{\"platformKind\":\"forum\",\"verifiedAtEpochSeconds\":100},"
                        + "{\"platformKind\":\"chat\",\"verifiedAtEpochSeconds\":900}]}")))
                .player("p1");

        assertEquals(100L, view.verifiedAtEpochSeconds().orElseThrow(),
                "the latest would make 'verified at' read as 'last touched', which is a "
                        + "different fact and a misleading one");
    }

    @Test
    @DisplayName("the subject id is withheld unless the operator asked for it")
    void theSubjectIdIsOptIn() {
        String body = ok("{\"linked\":true,\"subjectId\":\"s-secret\",\"identities\":[]}");

        assertTrue(
                source(InMemoryTransport.always(body)).player("p1").subjectId().isEmpty(),
                "a subject id correlates one person across every platform they have linked. "
                        + "On a page by default, that correlation is published to everyone who "
                        + "can read the page.");

        assertEquals(
                "s-secret",
                source(InMemoryTransport.always(body), true, Clock.fixed(NOW, ZoneOffset.UTC))
                        .player("p1").subjectId().orElseThrow(),
                "and when an operator does opt in, it must actually appear");
    }

    @Test
    @DisplayName("an answer is cached, and expires")
    void answersAreCachedAndExpire() {
        var counting = new java.util.concurrent.atomic.AtomicInteger();
        InMemoryTransport transport = new InMemoryTransport(request -> {
            counting.incrementAndGet();
            return ok("{\"linked\":false}");
        });

        var clock = new MutableClock(NOW);
        LinkDataSource source = new LinkDataSource(
                new SoulbindClient(transport, "cred", clock, new DecisionCache()),
                "game", Duration.ofSeconds(30), false, clock);

        source.player("p1");
        source.player("p1");
        source.player("p1");
        assertEquals(1, counting.get(),
                "Plan asks every provider on a page separately; one round trip per question "
                        + "would be a page load per question");

        clock.advance(Duration.ofSeconds(31));
        source.player("p1");
        assertEquals(2, counting.get(), "an expired answer must be re-asked");
    }

    @Test
    @DisplayName("the summary counts unknown separately from unlinked")
    void unknownIsNotFoldedIntoUnlinked() {
        // A REFUSAL for the third player, not a dropped connection.
        //
        // The first version returned null from the responder, meaning it to stand
        // for core not answering. TransportException is checked, so a Function
        // cannot throw it, and null simply became a null body -- the test failed
        // with "argument content is null", which is not an outage and not what it
        // claimed to assert.
        //
        // A refusal reaches the same branch honestly: anything that is not an Ok
        // leaves the dashboard unable to say, and unable to say is what `unknown`
        // means. A dropped connection is covered by its own test, which can use
        // goDown().
        InMemoryTransport transport = new InMemoryTransport(request ->
                request.contains("\"refused-one\"")
                        ? "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"missing-capability\",\"message\":\"no\"}}"
                        : ok(request.contains("\"linked-one\"")
                                ? "{\"linked\":true,\"identities\":[]}"
                                : "{\"linked\":false}"));

        LinkDataSource source = source(transport);
        ServerLinkSummary summary = source.summary(
                List.of("linked-one", "unlinked-one", "refused-one"),
                Map.of("unlinked-one", "Ann"));

        assertEquals(1, summary.linked());
        assertEquals(1, summary.unlinked());
        assertEquals(1, summary.unknown(),
                "a player core would not answer about is not an unlinked player. Folding "
                        + "them together makes an outage look like a collapse in linking, "
                        + "which is a thing an operator would go and investigate at length.");
        assertEquals(List.of("Ann"), summary.unlinkedNames(),
                "the table lists the unlinked by name, and must not list the unknown at all");
    }

    @Test
    @DisplayName("the linked fraction excludes the unanswerable from its denominator")
    void theFractionDoesNotMoveDuringAnOutage() {
        assertEquals(1.0, new ServerLinkSummary(4, 0, 6, List.of()).linkedFraction(),
                "six players core could not answer for must not drag a fully linked server "
                        + "down to 40%: a number that moves for a reason unrelated to linking "
                        + "is worse than no number");
        assertEquals(0.5, new ServerLinkSummary(2, 2, 0, List.of()).linkedFraction());
        assertEquals(0.0, new ServerLinkSummary(0, 0, 3, List.of()).linkedFraction(),
                "nothing answerable means no fraction to report, not a division by zero");
        assertEquals(10, new ServerLinkSummary(4, 0, 6, List.of()).total());
    }

    /** A clock a test can move, since the cache is the thing under test. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // --- concurrency and growth ------------------------------------------------

    @Test
    @DisplayName("the cache survives concurrent readers, because Plan uses its own threads")
    void concurrentReadersDoNotCorruptTheCache() throws Exception {
        // PLAYER_LEAVE and SERVER_PERIODICAL are separate Plan events that can
        // run at once. Against a plain LinkedHashMap this loses entries or spins
        // on a corrupted chain; the failure surfaces far from the dashboard that
        // caused it, which is why it is worth an explicit test.
        InMemoryTransport transport = InMemoryTransport.always(ok(
                "{\"linked\":true,\"subjectId\":\"s1\",\"identities\":["
                        + "{\"platformKind\":\"game\",\"proofMethod\":\"code\","
                        + "\"verifiedAtEpochSeconds\":100}]}"));
        LinkDataSource source = source(transport);

        // Every thread uses its OWN keys, so every single call is a cache
        // MISS and therefore a structural write. The first version shared keys
        // and warmed the cache in the first few iterations, after which almost
        // every call was a read -- the race window barely opened and swapping
        // ConcurrentHashMap back for a LinkedHashMap survived the test.
        int threads = 8;
        int perThread = 400;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                start.await();
                int seen = 0;
                for (int i = 0; i < perThread; i++) {
                    if (source.player("t" + id + "-p" + i).linked()) {
                        seen++;
                    }
                }
                return seen;
            }));
        }
        start.countDown();

        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // The TRANSPORT is asserted on, not only the cache.
        //
        // Without this line the test could not see a defect in InMemoryTransport
        // itself, and there was one: it recorded sends in a plain ArrayList, so
        // eight threads lost entries and occasionally threw from inside add().
        // Measured, on the broken version: 0 detections in 16 contended runs
        // through this test, and 3 in 5 once this assertion existed. The
        // difference is that everything else here reads LinkDataSource's cache,
        // which is correct even when the record of what was sent is not.
        //
        // A test that drives a double from eight threads and never checks the
        // double is a test that assumes the thing it is standing on.
        assertEquals(
                threads * perThread,
                transport.sendCount(),
                "the transport recorded fewer sends than were made, so the test double itself "
                        + "lost entries under concurrency -- every assertion about what a "
                        + "connector sent is then unreliable, and silently so");

        assertEquals(
                threads * perThread,
                total,
                "every read must return the linked answer");
        // threads * perThread must stay BELOW LinkDataSource.SWEEP_THRESHOLD
        // (4096), or the sweep fires mid-run and this count legitimately drops --
        // turning a real assertion into a confusing failure about the wrong thing.
        assertEquals(
                threads * perThread,
                source.cachedEntries(),
                "one entry per distinct player. Under concurrent writes an unsynchronized map "
                        + "loses entries during resize, so a count short of this is the race "
                        + "showing up as missing data rather than as an exception");
    }

    @Test
    @DisplayName("an expired entry is dropped even when the refresh cannot answer")
    void expiredEntriesAreEvicted() {
        // Deliberately NOT asserted by re-reading the same player while core is
        // up: put() overwrites the key, so the size is 1 whether or not the
        // expired entry was removed, and the assertion could never fail.
        //
        // The path where removal is observable is an expired entry whose refresh
        // fails -- there is no put() to paper over it, so a stale entry that was
        // never removed is still sitting in the map.
        MutableClock clock = new MutableClock(NOW);
        InMemoryTransport transport =
                InMemoryTransport.always(ok("{\"linked\":false,\"identities\":[]}"));
        LinkDataSource source = source(transport, false, clock);

        source.player("p1");
        assertEquals(1, source.cachedEntries());

        clock.advance(Duration.ofSeconds(31));
        transport.goDown();
        source.player("p1");

        assertEquals(
                0,
                source.cachedEntries(),
                "checking expiry on read without ever removing leaves every player the server "
                        + "has ever seen resident for the lifetime of the process");
    }

    // --- one render, one answer -------------------------------------------------

    @Test
    @DisplayName("the four server-wide numbers come from a single walk of the roster")
    void summaryIsMemoisedWithinTheTtl() {
        InMemoryTransport transport = InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}"));
        LinkDataSource source = source(transport);
        List<String> roster = List.of("p1", "p2", "p3");
        Map<String, String> names = Map.of("p1", "A", "p2", "B", "p3", "C");

        ServerLinkSummary first = source.summary(roster, names);
        ServerLinkSummary second = source.summary(roster, names);

        // assertSame, not assertEquals. player() already caches, so a re-walk
        // costs no round trips and an equality check cannot tell the two apart
        // -- it would pass with the memo removed. Identity is the thing the memo
        // actually guarantees: Plan calls the four server-wide providers
        // separately, and this is what makes all four read one snapshot rather
        // than four moments that need not agree.
        assertSame(
                first,
                second,
                "the four server-wide providers that make up one render must see one answer; "
                        + "without a memo each takes its own walk and the numbers they print "
                        + "need not add up to the roster");
    }

    @Test
    @DisplayName("an outage on one roster does not discard a good answer for another")
    void anOutageElsewhereKeepsAValidSnapshot() {
        // The rule is "do not cache an OUTAGE", not "throw away answers you
        // already have". A still-valid, unexpired summary for a different roster
        // says nothing about the roster core just failed to answer for, and
        // discarding it only buys an extra walk -- against a core that is down,
        // where every one of those calls must time out.
        InMemoryTransport transport = InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}"));
        LinkDataSource source = source(transport);
        Map<String, String> names = Map.of("p1", "A", "p2", "B");

        ServerLinkSummary first = source.summary(List.of("p1"), names);

        transport.goDown();
        assertEquals(1, source.summary(List.of("p2"), names).unknown());

        assertSame(
                first,
                source.summary(List.of("p1"), names),
                "the first roster's answer was still within its TTL and unaffected by a failure "
                        + "to answer about somebody else");
    }

    @Test
    @DisplayName("a player listed twice is still one player")
    void aDuplicatedRosterEntryIsCountedOnce() {
        LinkDataSource source = source(InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}")));

        ServerLinkSummary summary = source.summary(
                List.of("p1", "p2", "p1"), Map.of("p1", "Bob", "p2", "Alice"));

        assertEquals(
                2,
                summary.total(),
                "counting a repeated id twice makes the totals disagree with the roster, which "
                        + "is the same complaint the separate unknown count exists to prevent");
        assertEquals(List.of("Alice", "Bob"), summary.unlinkedNames(), "sorted by name, not by id");
    }

    @Test
    @DisplayName("the memo survives a roster that merely reordered")
    void memoIsNotOrderSensitive() {
        // The caller builds the id list from a map's key set, which promises no
        // iteration order. Keyed on the list as given, a roster that reordered
        // between two of the four server-wide provider calls missed the memo --
        // and the four numbers came from four moments again, which is the exact
        // thing the memo exists to prevent.
        InMemoryTransport transport = InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}"));
        LinkDataSource source = source(transport);
        Map<String, String> names = Map.of("p1", "A", "p2", "B", "p3", "C");

        ServerLinkSummary first = source.summary(List.of("p1", "p2", "p3"), names);
        ServerLinkSummary reordered = source.summary(List.of("p3", "p1", "p2"), names);

        assertSame(
                first,
                reordered,
                "the same players in a different order are the same question");
    }

    @Test
    @DisplayName("a rename is not served from the memo")
    void memoIsKeyedOnNamesToo() {
        InMemoryTransport transport = InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}"));
        LinkDataSource source = source(transport);
        List<String> roster = List.of("p1");

        assertEquals(List.of("Alice"), source.summary(roster, Map.of("p1", "Alice")).unlinkedNames());
        assertEquals(
                List.of("Alberta"),
                source.summary(roster, Map.of("p1", "Alberta")).unlinkedNames(),
                "the names are part of the answer, so a rename with an unchanged roster must "
                        + "not be served the old table for a full TTL");
    }

    @Test
    @DisplayName("an outage is not memoised either, so recovery is immediate")
    void anOutageSummaryIsNotMemoised() {
        InMemoryTransport transport = InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}"));
        LinkDataSource source = source(transport.goDown());
        List<String> roster = List.of("p1", "p2");

        assertEquals(2, source.summary(roster, Map.of()).unknown());

        transport.comeBack();
        assertEquals(
                2,
                source.summary(roster, Map.of()).unlinked(),
                "a memoised outage would keep the server page reporting unknown for a whole "
                        + "TTL after core came back");
    }

    @Test
    @DisplayName("the summary memo expires, rather than serving one answer forever")
    void theSummaryMemoExpires() {
        // Without this, deleting the expiry check from the memo guard leaves the
        // whole suite green -- and the defect it hides is a dashboard serving a
        // stale roster answer for the life of the process.
        MutableClock clock = new MutableClock(NOW);
        LinkDataSource source = source(
                InMemoryTransport.always(ok("{\"linked\":false,\"identities\":[]}")),
                false,
                clock);
        List<String> roster = List.of("p1", "p2");
        Map<String, String> names = Map.of("p1", "A", "p2", "B");

        ServerLinkSummary first = source.summary(roster, names);
        assertSame(first, source.summary(roster, names), "still inside the TTL");

        clock.advance(Duration.ofSeconds(31));

        assertNotSame(
                first,
                source.summary(roster, names),
                "past the TTL the roster must be walked again, not served from the memo");
    }

    @Test
    @DisplayName("the cache sweeps expired entries once it grows past its threshold")
    void theSweepBoundsGrowth() {
        // The growth bound its comment justifies was never exercised: the
        // concurrency test deliberately stays below the threshold, so deleting
        // the sweep entirely left the suite green.
        MutableClock clock = new MutableClock(NOW);
        LinkDataSource source = source(
                InMemoryTransport.always(ok("{\"linked\":false,\"identities\":[]}")),
                false,
                clock);

        int threshold = 4096;
        for (int i = 0; i < threshold; i++) {
            source.player("p" + i);
        }
        assertEquals(threshold, source.cachedEntries());

        // Everything above is now expired, so the next insert should find the
        // map at its threshold and clear what is dead rather than growing.
        clock.advance(Duration.ofSeconds(31));
        source.player("fresh");

        assertEquals(
                1,
                source.cachedEntries(),
                "a map that is only ever added to is one that is only ever added to; the sweep "
                        + "is what stops a long-lived server holding every player it ever saw");
    }

    @Test
    @DisplayName("invalidate forgets the players and the roster answer alike")
    void invalidateClearsBothCaches() {
        // invalidate() has no caller in the repository yet, so nothing else
        // exercises it -- including the summary memo it must also clear, which
        // was added later and would otherwise survive a deliberate flush.
        LinkDataSource source = source(InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}")));
        List<String> roster = List.of("p1");
        Map<String, String> names = Map.of("p1", "A");

        ServerLinkSummary before = source.summary(roster, names);
        assertEquals(1, source.cachedEntries());

        source.invalidate();

        assertEquals(0, source.cachedEntries(), "the per-player answers are gone");
        assertNotSame(
                before,
                source.summary(roster, names),
                "and so is the roster answer -- a flush that left the memo behind would keep "
                        + "serving the very numbers the operator asked to refresh");
    }

    @Test
    @DisplayName("unlinked names are sorted, not left in the roster's order")
    void unlinkedNamesAreSorted() {
        LinkDataSource source = source(InMemoryTransport.always(
                ok("{\"linked\":false,\"identities\":[]}")));

        // Names deliberately run OPPOSITE to id order. Mapped p1->Alice,
        // p2->Bob, p3->Carol the two orders agree, the output is already sorted
        // before the sort runs, and deleting the sort leaves this test green --
        // which is exactly what it did until a battery mutation-checked it.
        ServerLinkSummary summary = source.summary(
                List.of("p3", "p1", "p2"),
                Map.of("p1", "Carol", "p2", "Bob", "p3", "Alice"));

        assertEquals(
                List.of("Alice", "Bob", "Carol"),
                summary.unlinkedNames(),
                "the caller's roster promises no iteration order -- a HashMap gives a "
                        + "different one per JVM run -- and a table that reshuffles between "
                        + "refreshes looks like data changing when nothing has");
    }

    // --- the TTL, and the boundaries around it --------------------------------

    @Test
    @DisplayName("a TTL that is null, negative or zero falls back to the default")
    void ttlFallback() {
        // Three separate conditions and three separate mutants. Zero or
        // negative would mean every question is a round trip, which is a page
        // load per provider per player -- Plan renders eight of them.
        for (Duration bad : new Duration[] {null, Duration.ofSeconds(-1), Duration.ZERO}) {
            var counting = new java.util.concurrent.atomic.AtomicInteger();
            InMemoryTransport transport = new InMemoryTransport(request -> {
                counting.incrementAndGet();
                return ok("{\"linked\":false}");
            });
            var clock = new MutableClock(NOW);
            LinkDataSource source = new LinkDataSource(
                    new SoulbindClient(transport, "cred", clock, new DecisionCache()),
                    "game", bad, false, clock);

            source.player("p1");
            source.player("p1");

            assertEquals(1, counting.get(),
                    () -> "a TTL of " + bad + " produced no caching at all, so every provider"
                            + " on every page is its own round trip");
        }
    }

    @Test
    @DisplayName("an answer expiring exactly now is re-asked, not served")
    void expiryBoundary() {
        // `> now`, not `>= now`. An entry whose deadline is this instant has
        // expired; serving it means the TTL an operator configured is one
        // millisecond longer than they asked for, forever.
        var counting = new java.util.concurrent.atomic.AtomicInteger();
        InMemoryTransport transport = new InMemoryTransport(request -> {
            counting.incrementAndGet();
            return ok("{\"linked\":false}");
        });
        var clock = new MutableClock(NOW);
        LinkDataSource source = new LinkDataSource(
                new SoulbindClient(transport, "cred", clock, new DecisionCache()),
                "game", Duration.ofSeconds(30), false, clock);

        source.player("p1");
        clock.advance(Duration.ofSeconds(30));
        source.player("p1");

        assertEquals(2, counting.get(),
                "an entry that expired exactly now was served from the cache");
    }

    @Test
    @DisplayName("the cache sweeps expired entries rather than growing forever")
    void expiredEntriesAreSwept() {
        // Correct answers, unbounded memory: checking expiry on read without
        // ever removing leaves every player ever asked about resident for the
        // life of the process. A dashboard runs for months.
        InMemoryTransport transport = InMemoryTransport.always(ok("{\"linked\":false}"));
        var clock = new MutableClock(NOW);
        LinkDataSource source = new LinkDataSource(
                new SoulbindClient(transport, "cred", clock, new DecisionCache()),
                "game", Duration.ofSeconds(30), false, clock);

        // Past SWEEP_THRESHOLD, which is 4096: the sweep is deliberately rare,
        // because doing it on every read would walk the whole map for every
        // provider on every page.
        for (int i = 0; i < 4100; i++) {
            source.player("player-" + i);
        }
        int before = source.cachedEntries();
        assertTrue(before > 0, "nothing was cached at all");

        // Everything above is now stale -- at EXACTLY the deadline, because the
        // sweep's predicate is `<= now`. An entry whose expiry is this instant
        // has expired, and sweeping only what is strictly older leaves one
        // generation of dead entries behind on every pass.
        clock.advance(Duration.ofSeconds(30));
        source.player("the-one-that-sweeps");

        assertTrue(source.cachedEntries() < before,
                () -> "expired entries survived the sweep: " + before + " -> "
                        + source.cachedEntries());
    }

    @Test
    @DisplayName("a verification time of zero is not treated as the earliest")
    void zeroVerificationIsNotEarliest() {
        // `at > 0`, and the boundary matters: an identity core has not proven
        // carries zero, and taking that as the earliest would render "linked
        // since 1 January 1970" on somebody's page.
        InMemoryTransport transport = InMemoryTransport.always(ok(
                "{\"linked\":true,\"identities\":["
                        + "{\"platformKind\":\"game\",\"verifiedAtEpochSeconds\":0},"
                        + "{\"platformKind\":\"chat\",\"verifiedAtEpochSeconds\":1700000000}]}"));

        PlayerLinkView view = source(transport).player("p1");

        assertEquals(java.util.Optional.of(1700000000L), view.verifiedAtEpochSeconds(),
                "an unproven identity's zero was taken as the earliest verification, so the"
                        + " page reports a date in 1970");
    }

    @Test
    @DisplayName("a summary expiring exactly now is recomputed, not served")
    void summaryExpiryBoundary() {
        // Same boundary as the per-player cache, on the other cache. A server
        // page that serves a summary one tick past its deadline reports a
        // roster that has already changed -- and the number an operator is
        // looking at is the one thing that page is for.
        var counting = new java.util.concurrent.atomic.AtomicInteger();
        InMemoryTransport transport = new InMemoryTransport(request -> {
            counting.incrementAndGet();
            return ok("{\"linked\":false}");
        });
        var clock = new MutableClock(NOW);
        LinkDataSource source = new LinkDataSource(
                new SoulbindClient(transport, "cred", clock, new DecisionCache()),
                "game", Duration.ofSeconds(30), false, clock);

        source.summary(List.of("p1"), Map.of("p1", "Alex"));
        int afterFirst = counting.get();
        source.summary(List.of("p1"), Map.of("p1", "Alex"));
        assertEquals(afterFirst, counting.get(), "the summary was not cached at all");

        clock.advance(Duration.ofSeconds(30));
        source.summary(List.of("p1"), Map.of("p1", "Alex"));

        assertTrue(counting.get() > afterFirst,
                "a summary that expired exactly now was served from the cache");
    }
}
