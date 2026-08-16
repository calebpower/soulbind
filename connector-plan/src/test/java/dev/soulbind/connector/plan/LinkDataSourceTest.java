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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
}
