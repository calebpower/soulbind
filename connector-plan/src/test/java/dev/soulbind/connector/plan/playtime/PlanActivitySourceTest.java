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
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one place outside core's storage package that reads a database — tested
 * without one.
 *
 * <p>Far less is asserted here than in the version this replaces, and that is
 * the point: the arithmetic went away. There is no window sum, no idle-time
 * subtraction and no clamping to get wrong, because the number now comes from
 * the dashboard's own index. What is left is a roster query and the distinction
 * that actually matters — <b>a floor versus an unknown</b>. A real floor means
 * somebody has stopped playing; an unknown means this connector could not tell,
 * and reporting the first when it means the second takes a role away from
 * everybody who holds one.
 */
class PlanActivitySourceTest {

    private static final Instant AT = Instant.ofEpochSecond(1_700_000_000L);
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000a1e00");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000005a");

    private final List<String> logged = new ArrayList<>();
    private final List<String> bound = new ArrayList<>();

    /** A statement whose result set is the identifiers given. */
    private PreparedStatement statement(List<String> ids) {
        int[] cursor = {-1};
        InvocationHandler rows = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] < ids.size();
            case "getString" -> ids.get(cursor[0]);
            case "close" -> null;
            case "toString" -> "fake ResultSet";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ResultSet.class}, rows);

        InvocationHandler ps = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> rs;
            case "setLong", "setString" -> {
                // Recorded, not ignored: a fake that swallows the binding makes
                // "removed call to setLong" a mutant nothing notices, and that
                // mutant is a roster covering all of time.
                bound.add(args[0] + "=" + args[1]);
                yield null;
            }
            case "close" -> null;
            case "toString" -> "fake PreparedStatement";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, ps);
    }

    /** A source whose index and roster the test states outright. */
    private PlanActivitySource source(double index, List<String> ids) {
        return sourceThrowing(index, ids, null, null);
    }

    private PlanActivitySource sourceThrowing(
            double index, List<String> ids, RuntimeException indexFault, SQLException rosterFault) {
        PlanActivitySource.Queries queries = new PlanActivitySource.Queries() {
            @Override
            public double activityIndex(UUID player, long epochMillis) {
                if (indexFault != null) {
                    throw indexFault;
                }
                bound.add("index=" + player + "@" + epochMillis);
                return index;
            }

            @Override
            public <T> T query(String sql, PlanActivitySource.ThrowingFunction<PreparedStatement, T> body)
                    throws SQLException {
                if (rosterFault != null) {
                    throw rosterFault;
                }
                return body.apply(statement(ids));
            }
        };
        return new PlanActivitySource(queries, (message, cause) -> logged.add(message));
    }

    // --- the index ------------------------------------------------------------

    @Test
    @DisplayName("the host's index is passed through untouched, and asked about the right moment")
    void indexIsPassedThrough() {
        // Untouched: no rounding, no scaling, no reinterpretation. Scaling is the
        // reporter's, and doing it in two places is how two answers appear.
        assertEquals(OptionalDouble.of(3.2857), source(3.2857, List.of()).indexOf(ALEX, AT));
        assertEquals(List.of("index=" + ALEX + "@" + AT.toEpochMilli()), bound);
    }

    @Test
    @DisplayName("a genuine floor is an answer, not a failure")
    void zeroIsAnAnswer() {
        assertEquals(OptionalDouble.of(0.0), source(0.0, List.of()).indexOf(ALEX, AT));
        assertTrue(logged.isEmpty(), logged::toString);
    }

    @Test
    @DisplayName("a failure is unknown, never a floor")
    void indexFailureIsUnknown() {
        // The distinction the whole class is built around. A floor would take the
        // role off everybody on the sweep after somebody renames a column.
        OptionalDouble measured = sourceThrowing(
                0, List.of(), new IllegalStateException("Plan is not enabled"), null)
                .indexOf(ALEX, AT);

        assertTrue(measured.isEmpty(),
                "an unreadable player came back as a floor, which is a mass revocation waiting");
        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("rather than a floor"), logged::toString);
    }

    @Test
    @DisplayName("a non-finite index is refused rather than compared")
    void nonFiniteIsRefused() {
        // A NaN compares false against every threshold, so it would silently
        // deny; an infinity would satisfy every one. Neither is a number a rule
        // should be handed.
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            logged.clear();
            assertTrue(source(bad, List.of()).indexOf(ALEX, AT).isEmpty(),
                    () -> "accepted a non-finite index: " + bad);
            assertEquals(1, logged.size(), logged::toString);
        }
    }

    // --- the roster -----------------------------------------------------------

    @Test
    @DisplayName("recently seen players are read back, and the window is bound")
    void rosterIsRead() {
        Optional<Set<UUID>> players =
                source(0, List.of(ALEX.toString(), SAM.toString())).playersSeenSince(AT);

        assertEquals(Optional.of(Set.of(ALEX, SAM)), players);
        assertEquals(List.of("1=" + AT.toEpochMilli()), bound,
                "the window was not bound, so the roster is everybody who ever played");
    }

    @Test
    @DisplayName("one unreadable identifier does not lose the rest of the roster")
    void unreadableIdentifierIsSkipped() {
        Optional<Set<UUID>> players = source(0,
                List.of(ALEX.toString(), "not-a-uuid", SAM.toString())).playersSeenSince(AT);

        assertEquals(Optional.of(Set.of(ALEX, SAM)), players);
        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("not-a-uuid"), logged::toString);
    }

    @Test
    @DisplayName("nobody seen is an empty answer, not a missing one")
    void emptyRosterIsAnAnswer() {
        assertEquals(Optional.of(Set.of()), source(0, List.of()).playersSeenSince(AT));
        assertTrue(logged.isEmpty(), logged::toString);
    }

    @Test
    @DisplayName("a failed roster query is unknown, so the sweep can skip itself")
    void rosterFailureIsUnknown() {
        Optional<Set<UUID>> players = sourceThrowing(
                0, List.of(), null, new SQLException("Table 'plan_sessions' doesn't exist"))
                .playersSeenSince(AT);

        assertTrue(players.isEmpty());
        assertFalse(logged.isEmpty());
        assertTrue(logged.get(0).contains("skipping this sweep"), logged::toString);
    }

    @Test
    @DisplayName("the roster cannot be edited by its caller")
    void rosterIsCopied() {
        Set<UUID> players =
                source(0, List.of(ALEX.toString())).playersSeenSince(AT).orElseThrow();
        assertEquals(Set.of(ALEX), players);
        try {
            players.add(SAM);
            throw new AssertionError("the roster is handed out mutable");
        } catch (UnsupportedOperationException expected) {
            // what should happen
        }
    }
}
