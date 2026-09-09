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
import java.sql.SQLException;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Playtime read from the dashboard's own tables, through the dashboard's own
 * pooled query API.
 *
 * <p><b>This package is the one place in the repository outside
 * {@code core/storage} that names a table or holds a JDBC type, and the storage
 * seam guard exempts exactly this package and nothing else.</b> The reason the
 * guard exists does not apply here: it protects <em>core's</em> storage backend,
 * so that no caller can branch on which database soulbind runs against. This
 * reads a <em>foreign</em> system's schema, over a connection that system owns
 * and pools, and soulbind opens no database of its own to do it — which is the
 * property that keeps its own state a file on disk.
 *
 * <p>The alternative was a second connection to the dashboard's database with a
 * second copy of its credentials in a soulbind config file. That is strictly
 * worse and would have tripped the same guard anyway.
 *
 * <p><b>The dashboard's API has no idle-aware total</b>, so the window sum is
 * spelled out here. It matters more than it looks: over a week, one player can
 * differ six-fold between wall-clock connected time and time actually doing
 * anything, and a role that means "active" must not be earnable by leaving a
 * client running.
 *
 * <p>Every failure becomes {@link Optional#empty()}, never zero. A query that
 * throws because somebody renamed a column must not read as "nobody played".
 */
public final class PlanQueryPlaytimeSource implements PlaytimeSource {

    /**
     * The seam over the host's query API.
     *
     * <p>An interface rather than a direct call to the dashboard's static
     * accessor, so the sweep above it can be tested without a dashboard, a
     * database or a proxy.
     */
    @FunctionalInterface
    public interface Queries {
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

    private static final String ACTIVE_FOR_ONE =
            "SELECT SUM(s.session_end - s.session_start - s.afk_time)"
                    + " FROM plan_sessions s JOIN plan_users u ON u.id = s.user_id"
                    + " WHERE u.uuid = ? AND s.session_end > ?";

    private static final String ACTIVE_PLAYERS =
            "SELECT DISTINCT u.uuid"
                    + " FROM plan_sessions s JOIN plan_users u ON u.id = s.user_id"
                    + " WHERE s.session_end > ?";

    private final Queries queries;
    private final BiConsumer<String, Throwable> log;

    public PlanQueryPlaytimeSource(Queries queries, BiConsumer<String, Throwable> log) {
        this.queries = queries;
        this.log = log;
    }

    @Override
    public Optional<Duration> activeSince(UUID player, Instant since) {
        try {
            return queries.query(ACTIVE_FOR_ONE, statement -> {
                statement.setString(1, player.toString());
                statement.setLong(2, since.toEpochMilli());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.of(Duration.ZERO);
                    }
                    long millis = rs.getLong(1);
                    // SUM over no rows is NULL, which reads as 0 with wasNull
                    // set. That is a real zero -- the player exists and played
                    // nothing in the window -- and is not the same as a failure.
                    if (rs.wasNull()) {
                        return Optional.of(Duration.ZERO);
                    }
                    // Clamped at zero. Idle time is recorded separately and
                    // nothing guarantees it cannot exceed the session it
                    // belongs to; a negative total would report as an enormous
                    // unsigned number somewhere downstream.
                    return Optional.of(Duration.ofMillis(Math.max(0L, millis)));
                }
            });
        } catch (SQLException | RuntimeException e) {
            log.accept("could not read playtime for " + player + "; reporting nothing for them "
                    + "rather than zero", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Set<UUID>> playersActiveSince(Instant since) {
        try {
            return queries.query(ACTIVE_PLAYERS, statement -> {
                statement.setLong(1, since.toEpochMilli());
                try (ResultSet rs = statement.executeQuery()) {
                    Set<UUID> players = new LinkedHashSet<>();
                    while (rs.next()) {
                        String raw = rs.getString(1);
                        try {
                            players.add(UUID.fromString(raw));
                        } catch (IllegalArgumentException e) {
                            // One unparseable row must not lose the rest. A
                            // dashboard row this connector cannot read is a row
                            // it reports nothing about, not a sweep it abandons.
                            log.accept("skipping unreadable player identifier '" + raw + "'", null);
                        }
                    }
                    return Optional.of(Set.copyOf(players));
                }
            });
        } catch (SQLException | RuntimeException e) {
            log.accept("could not list recently active players; skipping this sweep entirely "
                    + "rather than reporting zero for everybody", e);
            return Optional.empty();
        }
    }
}
