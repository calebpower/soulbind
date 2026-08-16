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

import dev.soulbind.sdk.Payload;
import dev.soulbind.sdk.SoulbindClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns core's answers into what a dashboard shows.
 *
 * <p>Knows nothing about Plan. The annotated extension over this is a set of
 * one-line providers, which keeps every judgement here — where it is testable
 * without a Minecraft server, a Plan installation or a database.
 *
 * <p><b>Read-only, by construction.</b> This class calls two operations and
 * neither of them changes anything. A dashboard that could mutate the identity
 * graph would need a credential that could, and the connector is registered
 * without one: the plan's "mutations stay on the admin API" is enforced by the
 * capability grant, not by this class remembering to be careful.
 */
public final class LinkDataSource {

    /**
     * How long an answer is reused.
     *
     * <p>Plan refreshes a player page far more often than links change, and
     * every provider on the page would otherwise be its own round trip. Short
     * enough that an operator watching somebody link an account sees it within
     * a refresh or two.
     */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final SoulbindClient client;
    private final String platformKind;
    private final Duration ttl;
    private final Clock clock;
    private final boolean showSubjectId;

    private record Entry(PlayerLinkView view, long expiresAtMillis) {}

    /**
     * A whole-roster answer, kept so the four server-wide providers that make up
     * one dashboard render agree with each other.
     *
     * <p>Keyed on the roster itself: a different set of players is a different
     * question, and answering it from this would be worse than not caching.
     */
    private record SummarySnapshot(
            List<String> ids,
            Map<String, String> names,
            ServerLinkSummary summary,
            long expiresAtMillis) {}

    /**
     * The memo key: the roster as a set-like sorted list, plus the names.
     *
     * <p>Sorted because the caller builds the id list from a map's key set and
     * never promised an iteration order. Keying on the list as given meant a
     * roster that merely reordered between two of the four server-wide provider
     * calls missed the memo — and the four numbers came from four moments again,
     * which is the exact failure the memo exists to prevent.
     *
     * <p>Names are part of the key because they are part of the answer: a player
     * renamed with an unchanged roster would otherwise keep the old name in the
     * table for a full TTL.
     */
    private static List<String> distinctSorted(List<String> ids) {
        // A TreeSet, so the roster is both ordered and de-duplicated.
        //
        // Ordered because the caller builds this from a map's key set and never
        // promised an iteration order, and the memo is keyed on it. De-duplicated
        // because the same player listed twice is one player: counting them twice
        // makes the totals disagree with the roster, which is the very complaint
        // the separate `unknown` count exists to prevent.
        //
        // This is what the counting loop walks, not just what the key is built
        // from. De-duplicating only the key would have left the counts wrong
        // while making the memo look consistent.
        return List.copyOf(new TreeSet<>(ids));
    }

    /**
     * The point at which an expired-entry sweep is worth doing.
     *
     * <p>Not a capacity: entries are only ever dropped once expired, so this is
     * a "stop growing quietly" threshold rather than an eviction policy. A
     * roster is naturally bounded by the players a server has seen, but ids
     * churn, and a map that is only ever added to is one that is only ever
     * added to.
     */
    private static final int SWEEP_THRESHOLD = 4096;

    /**
     * Concurrent because Plan drives this from its own threads.
     *
     * <p>{@code callExtensionMethodsOn()} returns {@code PLAYER_LEAVE} and
     * {@code SERVER_PERIODICAL}, which are separate events Plan may run at the
     * same time — a player leaving while the periodical sweep is walking the
     * roster is the ordinary case, not a rare one. A plain map here is a data
     * race that shows up as a lost entry or a corrupted chain, far from the
     * dashboard that caused it.
     */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** Volatile rather than locked: publishing the latest whole answer is all
     * that is needed, and two threads racing to compute the same summary is a
     * duplicated read, not a wrong one. */
    private volatile SummarySnapshot summaries;

    public LinkDataSource(
            SoulbindClient client,
            String platformKind,
            Duration ttl,
            boolean showSubjectId,
            Clock clock) {
        this.client = client;
        this.platformKind = platformKind;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        this.showSubjectId = showSubjectId;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * What one player's links look like.
     *
     * <p>An outage returns {@link PlayerLinkView#unknown()}, never
     * {@code unlinked()}. The two render differently on purpose.
     */
    public PlayerLinkView player(String platformId) {
        long now = clock.millis();
        Entry cached = cache.get(platformId);
        if (cached != null) {
            if (cached.expiresAtMillis() > now) {
                return cached.view();
            }
            // Dropped on the way past. Checking expiry on read without ever
            // removing leaves every player ever asked about resident forever --
            // correct answers, unbounded memory.
            cache.remove(platformId, cached);
        }

        SoulbindClient.Outcome outcome = client.call(
                "subject.inspect",
                Map.of("platformKind", platformKind, "platformId", platformId));

        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            // NOT cached. An outage is a moment, not an answer, and caching it
            // would keep a dashboard reporting "unknown" for the whole TTL after
            // core came back.
            return PlayerLinkView.unknown();
        }

        PlayerLinkView view = read(ok.payload());
        if (cache.size() >= SWEEP_THRESHOLD) {
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        }
        cache.put(platformId, new Entry(view, now + ttl.toMillis()));
        return view;
    }

    /** How many answers are currently held. Exposed so a test can observe eviction. */
    int cachedEntries() {
        return cache.size();
    }

    private PlayerLinkView read(Payload payload) {
        if (!payload.flag("linked")) {
            return PlayerLinkView.unlinked();
        }

        // Sorted and deduplicated, because a dashboard column that reorders
        // between refreshes looks like data changing when nothing has.
        var kinds = new TreeSet<String>();
        var proofs = new TreeSet<String>();
        Long earliest = null;

        for (Payload identity : payload.items("identities")) {
            String kind = identity.text("platformKind");
            if (!kind.isBlank()) {
                kinds.add(kind);
            }
            String proof = identity.text("proofMethod");
            if (!proof.isBlank()) {
                proofs.add(proof);
            }
            if (identity.has("verifiedAtEpochSeconds")) {
                long at = identity.number("verifiedAtEpochSeconds");
                // The EARLIEST, which is when this subject first became linked.
                // The latest would move every time somebody adds a platform,
                // making "verified at" read as "last touched".
                if (at > 0 && (earliest == null || at < earliest)) {
                    earliest = at;
                }
            }
        }

        return new PlayerLinkView(
                true,
                true,
                // Only when the operator asked for it. A subject id is an
                // internal identifier that correlates a person across every
                // platform they have linked, and putting it on a page by
                // default publishes that correlation to everyone who can read
                // the page.
                showSubjectId && !payload.text("subjectId").isBlank()
                        ? Optional.of(payload.text("subjectId"))
                        : Optional.empty(),
                List.copyOf(kinds),
                List.copyOf(proofs),
                Optional.ofNullable(earliest));
    }

    /**
     * The server-wide picture for a set of players.
     *
     * <p>Takes the roster rather than asking core for one: core does not know
     * who is online, and a dashboard that listed every subject core has ever
     * seen would answer a different question from the one its page asks.
     */
    public ServerLinkSummary summary(List<String> platformIds, Map<String, String> names) {
        long now = clock.millis();
        List<String> ids = platformIds == null ? List.<String>of() : platformIds;

        Map<String, String> roster = names == null ? Map.<String, String>of() : names;
        List<String> key = distinctSorted(ids);

        SummarySnapshot snapshot = summaries;
        if (snapshot != null
                && snapshot.expiresAtMillis() > now
                && snapshot.ids().equals(key)
                && snapshot.names().equals(roster)) {
            return snapshot.summary();
        }

        int linked = 0;
        int unlinked = 0;
        int unknown = 0;
        List<String> unlinkedNames = new ArrayList<>();

        for (String id : key) {
            PlayerLinkView view = player(id);
            if (!view.known()) {
                unknown++;
            } else if (view.linked()) {
                linked++;
            } else {
                unlinked++;
                unlinkedNames.add(roster.getOrDefault(id, id));
            }
        }

        // Sorted for the same reason kinds and proofs are: the caller hands us a
        // roster whose iteration order it never promised -- a HashMap gives a
        // different one per JVM run -- and a table that reshuffles between
        // refreshes looks like data changing when nothing has.
        unlinkedNames.sort(Comparator.naturalOrder());

        ServerLinkSummary summary =
                new ServerLinkSummary(linked, unlinked, unknown, unlinkedNames);

        // Memoised only when nothing was unknown.
        //
        // Plan calls the four server-wide providers separately, so without this
        // one dashboard render walks the roster four times and the four numbers
        // come from four different moments -- which defeats the point of showing
        // `unknown` beside the other two, since they need not add up.
        //
        // Not memoised during an outage, for the reason player() does not cache
        // one: recovery must be visible on the next call rather than a TTL
        // later. The cost of that is real and stated -- while core is down, a
        // render is four roster walks, each round trip having to time out.
        if (unknown == 0) {
            summaries = new SummarySnapshot(key, Map.copyOf(roster), summary, now + ttl.toMillis());
        }
        // No else. Reaching here at all means the memo MISSED, so there is no
        // unexpired snapshot for this roster and these names to discard -- it
        // would have returned early. The branch that used to sit here could only
        // fire when the snapshot was already expired (where nulling is a no-op,
        // since expiry is checked on read) or when the names differed (where it
        // threw away an answer still valid for the old names). Dead in the case
        // its own comment described, and mutation-checked as dead: deleting it
        // changed nothing.
        return summary;
    }

    /** Forgets everything, for the rare case where an operator wants a fresh read. */
    public void invalidate() {
        cache.clear();
        summaries = null;
    }
}
