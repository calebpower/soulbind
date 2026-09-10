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
import static org.junit.jupiter.api.Assertions.assertSame;

import com.djrapitops.plan.query.CommonQueries;
import com.djrapitops.plan.query.QueryService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Three lines of forwarding to the host dashboard, tested.
 *
 * <p>This used to be untestable and was baselined as such, on the reasoning
 * that it needs a running dashboard. Only half of that was true: the
 * {@code QueryService.getInstance()} lookup needs one, and the forwarding
 * around it does not. Folding the two together let a mutant that returns zero
 * from the activity index sit in the no-coverage column -- and a zero index is
 * every player below every threshold, which is a mass revocation.
 *
 * <p>The host's API is two interfaces, so a proxy is enough. Nothing here needs
 * a database, a proxy server or a dashboard.
 */
class HostQueriesTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000a1e00");

    private final List<String> calls = new ArrayList<>();

    /** A dashboard whose answers this test states outright. */
    private QueryService service(double index, Object queryResult) {
        InvocationHandler common = (proxy, method, args) -> switch (method.getName()) {
            case "fetchActivityIndexOf" -> {
                calls.add("fetchActivityIndexOf(" + args[0] + "," + args[1] + ")");
                yield index;
            }
            case "toString" -> "fake CommonQueries";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        CommonQueries queries = (CommonQueries) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CommonQueries.class}, common);

        InvocationHandler svc = (proxy, method, args) -> switch (method.getName()) {
            case "getCommonQueries" -> queries;
            case "query" -> {
                calls.add("query(" + args[0] + ")");
                yield queryResult;
            }
            case "toString" -> "fake QueryService";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (QueryService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {QueryService.class}, svc);
    }

    @Test
    @DisplayName("the activity index is the host's own number, for the player and moment asked")
    void indexIsForwarded() {
        // Not rounded, not scaled, not defaulted. Scaling belongs to the
        // reporter; doing it here as well is how two different answers appear
        // for one player.
        PlanActivitySource.Queries adapter = HostQueries.of(service(3.2857, null));

        assertEquals(3.2857, adapter.activityIndex(ALEX, 1_700_000_000_000L));
        assertEquals(List.of("fetchActivityIndexOf(" + ALEX + ",1700000000000)"), calls,
                "the player or the instant was not passed through as given");
    }

    @Test
    @DisplayName("a zero index is forwarded as zero rather than mistaken for an absence")
    void zeroIsForwarded() {
        // The value a broken adapter returns by accident, and it must mean what
        // the dashboard said rather than what a stub returned. PlanActivitySource
        // is where an unreadable answer becomes an absence; this layer does not
        // get an opinion.
        assertEquals(0.0, HostQueries.of(service(0.0, null)).activityIndex(ALEX, 1L));
        assertEquals(1, calls.size(), calls::toString);
    }

    @Test
    @DisplayName("a statement is handed to the host's own pool, and its result comes back")
    void queryIsForwarded() throws java.sql.SQLException {
        Object result = new Object();
        PlanActivitySource.Queries adapter = HostQueries.of(service(0, result));

        assertSame(result, adapter.query("SELECT 1", statement -> null),
                "the host's result was swallowed");
        assertEquals(List.of("query(SELECT 1)"), calls,
                "the SQL reaching the host was not the SQL given");
    }
}
