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

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigKey;
import dev.soulbind.config.ConfigKey.Type;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.config.ConfigSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What this connector reads from its TOML file.
 *
 * <p>Its own schema rather than the proxy connector's, because the two are
 * separate principals with separate credentials and separate capabilities —
 * this one holds inspection and nothing else. Sharing a schema would make it
 * easy to share a credential, and a dashboard holding an enforcement
 * capability is a dashboard that could enforce.
 *
 * <p>The loader itself is the shared one in {@code config}, so there is one
 * TOML parser in this repository and one idea of what an unknown key means.
 */
public final class PlanConfig {

    private PlanConfig() {
        throw new AssertionError("no instances");
    }

    public static final ConfigKey CORE_URL = ConfigKey.required(
            "core.url", Type.STRING, "where this connector reaches soulbind core");

    public static final ConfigKey CREDENTIAL = ConfigKey.secret(
            "core.credential", false,
            "this connector's credential; prefer the environment override");

    /**
     * The platform kind this dashboard asks about.
     *
     * <p>Fixed per deployment rather than per query: a Plan installation reports
     * on one server's players, and letting a page choose the kind would be a
     * dashboard asking a question about a platform it does not host.
     */
    public static final ConfigKey PLATFORM_KIND = ConfigKey.optional(
            "plan.platformkind", Type.STRING,
            "the platform kind this dashboard's players belong to");

    /**
     * How long an answer is reused.
     *
     * <p>Tuned to Plan's refresh cadence (§10.5). Too short and every provider
     * on a page is its own round trip; too long and an operator watching
     * somebody link waits for a TTL to see it.
     */
    public static final ConfigKey CACHE_TTL_SECONDS = ConfigKey.optional(
            "plan.cachettlseconds", Type.INTEGER,
            "how long a player's link state is reused before core is asked again");

    /**
     * Whether the subject id appears on the page.
     *
     * <p>Off unless an operator says otherwise. It is an identifier correlating
     * a player across platforms, which is exactly what a dashboard should not
     * casually publish to everyone with panel access.
     */
    public static final ConfigKey SHOW_SUBJECT_ID = ConfigKey.optional(
            "plan.showsubjectid", Type.BOOLEAN,
            "whether to show the soulbind subject id on the player page");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            CORE_URL, CREDENTIAL, PLATFORM_KIND, CACHE_TTL_SECONDS, SHOW_SUBJECT_ID);

    public static Config load(Path file) {
        return ConfigLoader.load(file, SCHEMA);
    }

    /** Problems that should stop the connector starting, said all at once. */
    public static List<String> validate(Config config) {
        List<String> problems = new ArrayList<>();
        config.findString(CORE_URL)
                .filter(url -> !url.isBlank())
                .orElseGet(() -> {
                    problems.add("core.url is required: this connector has nowhere to ask");
                    return "";
                });
        return problems;
    }

    public static String platformKind(Config config) {
        return config.findString(PLATFORM_KIND).filter(s -> !s.isBlank()).orElse("game");
    }

    public static Duration cacheTtl(Config config) {
        return config.findInt(CACHE_TTL_SECONDS)
                .filter(seconds -> seconds > 0)
                .map(Duration::ofSeconds)
                .orElse(LinkDataSource.DEFAULT_TTL);
    }

    public static boolean showSubjectId(Config config) {
        return config.findBoolean(SHOW_SUBJECT_ID).orElse(false);
    }
}
