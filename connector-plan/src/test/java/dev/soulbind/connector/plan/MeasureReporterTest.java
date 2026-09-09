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

import dev.soulbind.connector.plan.playtime.PlaytimeSource;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweep that keeps a trailing window current.
 *
 * <p>Two properties carry everything here, and both are about what the reporter
 * does when it cannot see.
 *
 * <p><b>An unreadable source is never reported as zero.</b> The failure being
 * prevented is not a missing grant — it is every holder dropping to zero at once
 * on the sweep after somebody renames a column, and the role coming off everyone
 * simultaneously.
 *
 * <p><b>The population drains.</b> Somebody who stops playing must be reported
 * at zero once, so core re-decides and the role comes off, and then never again.
 * Without the drain the set grows to the whole roster; without the zero the role
 * never comes off at all.
 */
class MeasureReporterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final Duration WEEK = Duration.ofDays(7);

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000a1e00");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000005a");

    /** A source whose answers the test states outright, including "I cannot say". */
    private static class FakeSource implements PlaytimeSource {
        private Optional<Set<UUID>> population = Optional.of(Set.of());
        private final Map<UUID, Optional<Duration>> answers = new LinkedHashMap<>();
        private final List<UUID> asked = new ArrayList<>();

        @Override
        public Optional<Duration> activeSince(UUID player, Instant since) {
            asked.add(player);
            return answers.getOrDefault(player, Optional.of(Duration.ZERO));
        }

        @Override
        public Optional<Set<UUID>> playersActiveSince(Instant since) {
            return population;
        }
    }

    private record Fixture(MeasureReporter reporter, FakeSource source, List<String> sent,
            List<String> logged) {}

    private Fixture fixture(boolean coreAccepts) {
        FakeSource source = new FakeSource();
        List<String> sent = new ArrayList<>();
        List<String> logged = new ArrayList<>();

        InMemoryTransport transport = new InMemoryTransport(request -> {
            sent.add(request);
            return coreAccepts
                    ? "{\"schema\":1,\"ok\":true,\"payload\":{\"recorded\":true}}"
                    : "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"invalid-request\","
                            + "\"message\":\"no\"}}";
        });

        SoulbindClient client =
                new SoulbindClient(transport, "cred", CLOCK, new DecisionCache());

        return new Fixture(
                new MeasureReporter(client, source, "game", "playtime", WEEK, CLOCK,
                        (message, cause) -> logged.add(message)),
                source, sent, logged);
    }

    private static long reportsFor(List<String> sent, UUID player) {
        return sent.stream().filter(r -> r.contains(player.toString())).count();
    }

    @Test
    @DisplayName("everyone active is measured and reported")
    void reportsTheActive() {
        Fixture f = fixture(true);
        f.source().population = Optional.of(Set.of(ALEX, SAM));
        f.source().answers.put(ALEX, Optional.of(Duration.ofHours(9)));
        f.source().answers.put(SAM, Optional.of(Duration.ofHours(1)));

        MeasureReporter.Swept swept = f.reporter().sweep();

        assertTrue(swept.ran());
        assertEquals(2, swept.reported());
        assertEquals(1, reportsFor(f.sent(), ALEX));
        assertTrue(f.sent().stream().anyMatch(r -> r.contains("32400")),
                () -> "nine hours was not reported as seconds: " + f.sent());
    }

    @Test
    @DisplayName("somebody who stops playing is reported at zero once, then dropped")
    void populationDrains() {
        Fixture f = fixture(true);
        f.source().population = Optional.of(Set.of(ALEX));
        f.source().answers.put(ALEX, Optional.of(Duration.ofHours(9)));
        f.reporter().sweep();

        // They stop. They are no longer "active", but they were reported
        // non-zero, so the sweep must still cover them -- otherwise their old
        // measurement stands and the role never comes off.
        f.source().population = Optional.of(Set.of());
        f.source().answers.put(ALEX, Optional.of(Duration.ZERO));
        f.sent().clear();

        MeasureReporter.Swept second = f.reporter().sweep();
        assertEquals(1, second.considered(),
                "a player who stopped playing dropped out of the sweep, so their last "
                        + "measurement would stand forever");
        assertEquals(1, reportsFor(f.sent(), ALEX));

        // And now they are gone from the carried set: zero is below every
        // threshold, so repeating it says nothing.
        f.sent().clear();
        MeasureReporter.Swept third = f.reporter().sweep();
        assertEquals(0, third.considered(),
                "the carried set never drains, so it grows to the whole roster");
        assertTrue(f.sent().isEmpty(), f.sent()::toString);
    }

    @Test
    @DisplayName("an unreadable population skips the sweep instead of zeroing everybody")
    void unreadablePopulationSkipsEverything() {
        Fixture f = fixture(true);
        f.source().population = Optional.of(Set.of(ALEX));
        f.source().answers.put(ALEX, Optional.of(Duration.ofHours(9)));
        f.reporter().sweep();
        f.sent().clear();

        // The query fails. Reporting zero for everybody carried over would take
        // the role off all of them on the strength of a query that did not run.
        f.source().population = Optional.empty();

        MeasureReporter.Swept swept = f.reporter().sweep();

        assertFalse(swept.ran());
        assertTrue(f.sent().isEmpty(),
                () -> "a failed population query still reported measurements: " + f.sent());
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("nothing already granted")),
                () -> "the outage was not reported in terms of what it does NOT do: "
                        + f.logged());
    }

    @Test
    @DisplayName("an unreadable player is skipped, and the rest of the sweep continues")
    void unreadablePlayerIsSkipped() {
        Fixture f = fixture(true);
        f.source().population = Optional.of(Set.of(ALEX, SAM));
        f.source().answers.put(ALEX, Optional.empty());
        f.source().answers.put(SAM, Optional.of(Duration.ofHours(3)));

        MeasureReporter.Swept swept = f.reporter().sweep();

        assertEquals(2, swept.considered());
        assertEquals(1, swept.reported(), "one unreadable player stopped the whole sweep");
        assertEquals(0, reportsFor(f.sent(), ALEX),
                "an unreadable player was reported anyway, which can only be as zero");
        assertEquals(1, reportsFor(f.sent(), SAM));
    }

    @Test
    @DisplayName("an outage is reported once, and so is its end")
    void outageIsLatched() {
        Fixture f = fixture(true);
        f.source().population = Optional.empty();

        f.reporter().sweep();
        f.reporter().sweep();
        f.reporter().sweep();
        assertEquals(1, f.logged().size(),
                () -> "an outage logged once per cycle is a log nobody reads: " + f.logged());

        f.source().population = Optional.of(Set.of());
        f.reporter().sweep();
        assertEquals(2, f.logged().size(), f.logged()::toString);
        assertTrue(f.logged().get(1).contains("resuming"), f.logged()::toString);
    }

    @Test
    @DisplayName("core refusing one player does not stop the others")
    void refusalIsPerPlayer() {
        Fixture f = fixture(false);
        f.source().population = Optional.of(Set.of(ALEX, SAM));
        f.source().answers.put(ALEX, Optional.of(Duration.ofHours(9)));
        f.source().answers.put(SAM, Optional.of(Duration.ofHours(3)));

        MeasureReporter.Swept swept = f.reporter().sweep();

        assertEquals(2, swept.considered(),
                "a refusal for one player abandoned the rest of the sweep");
        assertEquals(0, swept.reported());
        assertEquals(2, f.logged().size(), f.logged()::toString);
    }

    @Test
    @DisplayName("a refused report does not enter the carried set")
    void refusedReportsAreNotCarried() {
        // Otherwise a core that refuses everything grows the set forever while
        // reporting nothing, and the reporter's own memory becomes the leak.
        Fixture f = fixture(false);
        f.source().population = Optional.of(Set.of(ALEX));
        f.source().answers.put(ALEX, Optional.of(Duration.ofHours(9)));
        f.reporter().sweep();

        f.source().population = Optional.of(Set.of());
        assertEquals(0, f.reporter().sweep().considered(),
                "a player core refused was carried anyway");
    }

    @Test
    @DisplayName("the window is reported as configured, so a rule can match it exactly")
    void windowTravelsWithTheValue() {
        // A rule matches the window EXACTLY. If the reporter sent a different
        // one the rule would refuse everybody, and the symptom would be a role
        // nobody ever gets with nothing in any log.
        Fixture f = fixture(true);
        f.source().population = Optional.of(Set.of(ALEX));
        f.source().answers.put(ALEX, Optional.of(Duration.ofHours(9)));

        f.reporter().sweep();

        assertTrue(f.sent().stream().anyMatch(r -> r.contains("604800")),
                () -> "the seven-day window was not what got reported: " + f.sent());
    }

    @Test
    @DisplayName("nothing at all to do is not an error")
    void quietSweep() {
        Fixture f = fixture(true);
        f.source().population = Optional.of(Set.of());

        MeasureReporter.Swept swept = f.reporter().sweep();

        assertTrue(swept.ran());
        assertEquals(0, swept.considered());
        assertTrue(f.logged().isEmpty(), f.logged()::toString);
    }

    @Test
    @DisplayName("an Error out of a sweep is contained, not left to cancel the schedule")
    void sweepQuietlyContainsAnError() {
        // A cancelled scheduled task is silent: the connector stays up, keeps
        // rendering link state, and never reports another measurement -- so
        // every role drifts out of date with nothing in the log to say when it
        // stopped. Catching Throwable rather than RuntimeException is the point.
        FakeSource exploding = new FakeSource() {
            @Override
            public Optional<Set<UUID>> playersActiveSince(Instant since) {
                throw new NoClassDefFoundError("com/djrapitops/plan/query/QueryService");
            }
        };
        List<String> logged = new ArrayList<>();
        MeasureReporter reporter = new MeasureReporter(
                new SoulbindClient(InMemoryTransport.always("{}"), "cred", CLOCK,
                        new DecisionCache()),
                exploding, "game", "playtime", WEEK, CLOCK,
                (message, cause) -> logged.add(message));

        reporter.sweepQuietly();

        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("retried"), logged::toString);
    }
}
