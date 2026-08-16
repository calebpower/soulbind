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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

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

    private final Map<String, Entry> cache = new LinkedHashMap<>();

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
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.view();
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
        cache.put(platformId, new Entry(view, now + ttl.toMillis()));
        return view;
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
        int linked = 0;
        int unlinked = 0;
        int unknown = 0;
        List<String> unlinkedNames = new ArrayList<>();

        for (String id : platformIds == null ? List.<String>of() : platformIds) {
            PlayerLinkView view = player(id);
            if (!view.known()) {
                unknown++;
            } else if (view.linked()) {
                linked++;
            } else {
                unlinked++;
                unlinkedNames.add(
                        names == null ? id : names.getOrDefault(id, id));
            }
        }

        return new ServerLinkSummary(linked, unlinked, unknown, unlinkedNames);
    }

    /** Forgets everything, for the rare case where an operator wants a fresh read. */
    public void invalidate() {
        cache.clear();
    }
}
