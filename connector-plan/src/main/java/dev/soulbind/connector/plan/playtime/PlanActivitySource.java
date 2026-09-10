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

package dev.soulbind.connector.plan.playtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Activity read from the dashboard: its own index for the number, and one query
 * of its tables for the roster.
 *
 * <p><b>The index is the host's, not ours.</b> An earlier version of this class
 * summed session lengths minus idle time over a trailing week and compared that
 * to a threshold in hours. It worked, and it measured the wrong thing: a single
 * long weekend outscored somebody who turned up every evening, and everyone's
 * number fell off a cliff when a seven-day window rolled past their last
 * session. The dashboard already computes something better — three separate
 * weeks, each curved so that returns diminish, then averaged — and it is the
 * number its own pages show, so a role granted on it agrees with what a player
 * can already see about themselves.
 *
 * <p><b>One query remains, and this package is still the exemption.</b> The
 * host's public API can answer "how active is this player" but has no way to
 * ask "which players are there" — no enumeration of any kind. So the roster is
 * still SQL. What went with the old implementation is the arithmetic: no more
 * summing, no more idle-time subtraction, no more clamping, no window to get
 * wrong. What is left reads one column.
 *
 * <p>The storage seam guard exempts exactly this package, and the reason is
 * unchanged: it protects <em>core's</em> storage backend so no caller can branch
 * on which database soulbind runs against. That property is untouched — soulbind
 * still opens no database of its own and holds no database credential. This
 * reads a foreign system's schema over that system's own pooled connection.
 *
 * <p>Every failure becomes an empty optional, never zero. A query that throws
 * because somebody renamed a column must not read as "nobody is active".
 */
public final class PlanActivitySource implements ActivitySource {

    /**
     * The seam over the host's query API.
     *
     * <p>An interface rather than direct calls to the host's statics, so the
     * sweep above it can be tested without a dashboard, a database or a proxy.
     */
    public interface Queries {

        /** The host's own activity index for a player, at an instant. */
        double activityIndex(UUID player, long epochMillis);

        /** Runs a statement over the host's pooled connection. */
        <T> T query(String sql, ThrowingFunction<PreparedStatement, T> body) throws SQLException;
    }

    /**
     * What the host's API hands a statement to.
     *
     * <p>Throws {@link SQLException} and not {@code Exception}, matching the
     * host's own functional interface exactly. A wider signature here would not
     * adapt to theirs, and widening theirs is not on offer.
     */
    @FunctionalInterface
    public interface ThrowingFunction<T, R> {
        R apply(T input) throws SQLException;
    }

    /**
     * Everyone with a session ending in the window.
     *
     * <p>Reads identifiers and nothing else — no arithmetic, no aggregation. The
     * number this roster is used to ask about comes from the host.
     */
    private static final String SEEN_SINCE =
            "SELECT DISTINCT u.uuid"
                    + " FROM plan_sessions s JOIN plan_users u ON u.id = s.user_id"
                    + " WHERE s.session_end > ?";

    private final Queries queries;
    private final BiConsumer<String, Throwable> log;

    public PlanActivitySource(Queries queries, BiConsumer<String, Throwable> log) {
        this.queries = queries;
        this.log = log;
    }

    @Override
    public OptionalDouble indexOf(UUID player, Instant at) {
        try {
            double index = queries.activityIndex(player, at.toEpochMilli());
            if (Double.isNaN(index) || Double.isInfinite(index)) {
                // Not a number this can compare. Saying nothing is right: a
                // rule refuses an absent measure, and refusing is what an
                // unreadable answer deserves.
                log.accept("the dashboard returned a non-finite activity index for " + player
                        + "; reporting nothing for them rather than a floor", null);
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(index);
        } catch (RuntimeException e) {
            log.accept("could not read the activity index for " + player
                    + "; reporting nothing for them rather than a floor", e);
            return OptionalDouble.empty();
        }
    }

    @Override
    public Optional<Set<UUID>> playersSeenSince(Instant since) {
        try {
            return queries.query(SEEN_SINCE, statement -> {
                statement.setLong(1, since.toEpochMilli());
                try (ResultSet rs = statement.executeQuery()) {
                    Set<UUID> players = new LinkedHashSet<>();
                    while (rs.next()) {
                        String raw = rs.getString(1);
                        try {
                            players.add(UUID.fromString(raw));
                        } catch (IllegalArgumentException e) {
                            // One unreadable row must not lose the rest. A row
                            // this connector cannot parse is a row it reports
                            // nothing about, not a sweep it abandons.
                            log.accept("skipping unreadable player identifier '" + raw + "'", null);
                        }
                    }
                    return Optional.of(Set.copyOf(players));
                }
            });
        } catch (SQLException | RuntimeException e) {
            log.accept("could not list recently seen players; skipping this sweep entirely "
                    + "rather than reporting a floor for everybody", e);
            return Optional.empty();
        }
    }
}
