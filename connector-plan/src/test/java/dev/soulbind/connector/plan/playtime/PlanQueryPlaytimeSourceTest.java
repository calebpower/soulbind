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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
 * The one place in this repository outside core's storage package that reads a
 * database — tested without one.
 *
 * <p>"Untestable without a database" was the easy reading, and it was wrong: the
 * seam over the host's query API was already there for the sweep above it, so
 * the same seam serves a fake statement here. What it buys is the arithmetic,
 * which is where this class can be quietly wrong: a SQL {@code SUM} over no rows
 * is <b>null, not zero</b>, and reading it as a long gives 0 with {@code wasNull}
 * set — indistinguishable from a real zero unless somebody asks.
 *
 * <p>The distinction that matters most is <b>zero versus unknown</b>. A real zero
 * means a player did not play; an unknown means this connector could not tell,
 * and reporting the first when it means the second takes a role away from
 * everybody who holds one.
 */
class PlanQueryPlaytimeSourceTest {

    private static final Instant SINCE = Instant.ofEpochSecond(1_700_000_000L);
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000a1e00");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000005a");

    private final List<String> logged = new ArrayList<>();

    /** Every parameter the source bound, in order, as "index=value". */
    private static final List<String> BOUND = new ArrayList<>();

    /**
     * A statement whose result set is the rows given, driven through a proxy.
     *
     * <p>{@link ResultSet} has upwards of two hundred methods and this needs
     * four of them. A proxy answering exactly those and refusing everything else
     * is smaller than a hand-written stub and cannot drift into pretending to
     * support something it does not.
     */
    private static PlanQueryPlaytimeSource.Queries rows(List<Map<String, Object>> rows) {
        return new PlanQueryPlaytimeSource.Queries() {
            @Override
            public <T> T query(
                    String sql, PlanQueryPlaytimeSource.ThrowingFunction<PreparedStatement, T> body)
                    throws SQLException {
                return body.apply(statement(rows));
            }
        };
    }

    private static PlanQueryPlaytimeSource.Queries failing(String message) {
        return new PlanQueryPlaytimeSource.Queries() {
            @Override
            public <T> T query(
                    String sql, PlanQueryPlaytimeSource.ThrowingFunction<PreparedStatement, T> body)
                    throws SQLException {
                throw new SQLException(message);
            }
        };
    }

    private static PreparedStatement statement(List<Map<String, Object>> rows) {
        int[] cursor = {-1};
        boolean[] wasNull = {false};

        InvocationHandler resultSet = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] < rows.size();
            case "getLong" -> {
                Object value = rows.get(cursor[0]).get("1");
                wasNull[0] = value == null;
                yield value == null ? 0L : ((Number) value).longValue();
            }
            case "getString" -> {
                Object value = rows.get(cursor[0]).get("1");
                wasNull[0] = value == null;
                yield value == null ? null : String.valueOf(value);
            }
            case "wasNull" -> wasNull[0];
            case "close" -> null;
            case "toString" -> "fake ResultSet";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                    "the fake result set does not implement " + method.getName());
        };

        ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                PlanQueryPlaytimeSourceTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                resultSet);

        InvocationHandler statement = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> rs;
            case "setString", "setLong" -> {
                // RECORDED, not ignored. A fake that swallows the bindings makes
                // "removed call to setString" a mutant nothing notices -- and
                // that mutant is the query asking about the wrong player, or
                // about all of time.
                BOUND.add(args[0] + "=" + args[1]);
                yield null;
            }
            case "close" -> null;
            case "toString" -> "fake PreparedStatement";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                    "the fake statement does not implement " + method.getName());
        };

        return (PreparedStatement) Proxy.newProxyInstance(
                PlanQueryPlaytimeSourceTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                statement);
    }

    private static Map<String, Object> row(Object first) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("1", first);
        return row;
    }

    private PlanQueryPlaytimeSource source(PlanQueryPlaytimeSource.Queries queries) {
        BOUND.clear();
        return new PlanQueryPlaytimeSource(queries, (message, cause) -> logged.add(message));
    }

    // --- one player -----------------------------------------------------------

    @Test
    @DisplayName("a summed total comes back as a duration")
    void totalIsRead() {
        Optional<Duration> measured =
                source(rows(List.of(row(25_200_000L)))).activeSince(ALEX, SINCE);

        assertEquals(Optional.of(Duration.ofHours(7)), measured,
                "the sum is milliseconds; reading it as seconds would be out by a thousand");

        // The query must actually be about THIS player and THIS window. Without
        // these the same total comes back whoever is asked about, which is a
        // role granted to everybody on the strength of one person's playing.
        assertEquals(List.of("1=" + ALEX, "2=" + SINCE.toEpochMilli()), BOUND,
                "the player and the window were not bound into the query");
    }

    @Test
    @DisplayName("a SUM over no rows is null, and that is a real zero")
    void nullSumIsZero() {
        // SQL returns NULL from SUM over an empty set, which getLong reads as 0
        // with wasNull set. It means the player exists and played nothing in the
        // window -- a real answer, and NOT the same as being unable to ask.
        assertEquals(Optional.of(Duration.ZERO),
                source(rows(List.of(row(null)))).activeSince(ALEX, SINCE));
        assertTrue(logged.isEmpty(), logged::toString);
    }

    @Test
    @DisplayName("no row at all is a zero too, not a failure")
    void noRowIsZero() {
        assertEquals(Optional.of(Duration.ZERO),
                source(rows(List.of())).activeSince(ALEX, SINCE));
    }

    @Test
    @DisplayName("a negative total is clamped rather than passed on")
    void negativeIsClamped() {
        // Idle time is recorded separately from the session it belongs to and
        // nothing guarantees it cannot exceed it. A negative duration reported
        // to core would be a number no threshold can sensibly compare, and would
        // read as an enormous one somewhere downstream.
        assertEquals(Optional.of(Duration.ZERO),
                source(rows(List.of(row(-5_000L)))).activeSince(ALEX, SINCE));
    }

    @Test
    @DisplayName("a failure is unknown, never zero")
    void failureIsUnknown() {
        // The distinction the whole class is built around. Zero would take the
        // role off everybody on the sweep after somebody renames a column.
        Optional<Duration> measured =
                source(failing("Unknown column 'afk_time'")).activeSince(ALEX, SINCE);

        assertTrue(measured.isEmpty(),
                "an unreadable player came back as zero, which is a mass revocation waiting");
        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("rather than zero"), logged::toString);
    }

    // --- the population -------------------------------------------------------

    @Test
    @DisplayName("recently active players are read back")
    void populationIsRead() {
        Optional<Set<UUID>> players = source(rows(List.of(row(ALEX.toString()), row(SAM.toString()))))
                .playersActiveSince(SINCE);

        assertEquals(Optional.of(Set.of(ALEX, SAM)), players);
        assertEquals(List.of("1=" + SINCE.toEpochMilli()), BOUND,
                "the window was not bound, so the population is everybody who ever played");
    }

    @Test
    @DisplayName("one unreadable identifier does not lose the rest of the population")
    void unreadableIdentifierIsSkipped() {
        // A row this connector cannot parse is a row it reports nothing about,
        // not a sweep it abandons -- abandoning would mean one malformed row
        // stopping every other player's role from moving.
        Optional<Set<UUID>> players = source(rows(List.of(
                row(ALEX.toString()), row("not-a-uuid"), row(SAM.toString()))))
                .playersActiveSince(SINCE);

        assertEquals(Optional.of(Set.of(ALEX, SAM)), players);
        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("not-a-uuid"), logged::toString);
    }

    @Test
    @DisplayName("nobody active is an empty answer, not a missing one")
    void emptyPopulationIsAnAnswer() {
        assertEquals(Optional.of(Set.of()), source(rows(List.of())).playersActiveSince(SINCE));
    }

    @Test
    @DisplayName("a failed population query is unknown, so the sweep can skip itself")
    void populationFailureIsUnknown() {
        Optional<Set<UUID>> players =
                source(failing("Table 'plan_sessions' doesn't exist")).playersActiveSince(SINCE);

        assertTrue(players.isEmpty());
        assertFalse(logged.isEmpty());
        assertTrue(logged.get(0).contains("skipping this sweep"), logged::toString);
    }

    @Test
    @DisplayName("the returned population cannot be edited by its caller")
    void populationIsCopied() {
        Set<UUID> players = source(rows(List.of(row(ALEX.toString()))))
                .playersActiveSince(SINCE).orElseThrow();

        assertEquals(Set.of(ALEX), players);
        assertTrue(players.getClass().getName().contains("Immutable")
                        || tryAddFails(players),
                "the population is handed out mutable, so a caller can change what was measured");
    }

    private static boolean tryAddFails(Set<UUID> players) {
        try {
            players.add(SAM);
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }
}
