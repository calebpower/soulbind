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

import com.djrapitops.plan.query.QueryService;

/**
 * Adapts the host dashboard's query API to {@link PlanQueryPlaytimeSource.Queries}.
 *
 * <p>Here rather than in the plugin that calls it, and the reason is the storage
 * seam guard: the adapter necessarily names a JDBC type, and putting it beside
 * the plugin would mean the guard's exemption covered two places instead of one.
 * An exemption that grows is an exemption nobody can state the boundary of.
 *
 * <p>So the rule stays exactly as narrow as it reads: <b>this package, and
 * nothing else, knows that the dashboard has a database.</b>
 */
public final class HostQueries {

    private HostQueries() {
        throw new AssertionError("no instances");
    }

    /**
     * The host's live query service, wrapped.
     *
     * @throws IllegalStateException if the dashboard is not far enough along to
     *     answer queries, which the caller reports rather than retrying
     */
    public static PlanQueryPlaytimeSource.Queries live() {
        QueryService service = QueryService.getInstance();
        return new PlanQueryPlaytimeSource.Queries() {
            @Override
            public <T> T query(
                    String sql, PlanQueryPlaytimeSource.ThrowingFunction<
                            java.sql.PreparedStatement, T> body) {
                return service.query(sql, body::apply);
            }
        };
    }
}
