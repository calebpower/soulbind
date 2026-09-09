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
 * separate principals with separate credentials and separate capabilities.
 * Sharing a schema would make it easy to share a credential, and a dashboard
 * holding an enforcement capability is a dashboard that could enforce.
 *
 * <p><b>TWO credentials, not one.</b> {@code core.credential} reads link state
 * and can mutate nothing; {@code measure.credential} reports measurements and
 * can do nothing else. They are separate keys because they are separate grants:
 * a connector that may say what it measured must not thereby be able to read
 * everybody's measurements, and the read-only one must not gain the ability to
 * manufacture entitlement. An earlier version of this comment said this
 * connector "holds inspection and nothing else", which stopped being true when
 * reporting landed.
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

    public static final ConfigKey MEASURE_ENABLED = ConfigKey.optional(
            "measure.enabled", Type.BOOLEAN,
            "whether to report playtime to core; off unless an operator turns it on");

    public static final ConfigKey MEASURE_NAME = ConfigKey.optional(
            "measure.name", Type.STRING,
            "what to call the measure; a rule's threshold names the same string");

    public static final ConfigKey MEASURE_WINDOW_SECONDS = ConfigKey.optional(
            "measure.windowseconds", Type.INTEGER,
            "the trailing window to measure over; a rule must ask for exactly this");

    public static final ConfigKey MEASURE_SWEEP_SECONDS = ConfigKey.optional(
            "measure.sweepseconds", Type.INTEGER,
            "how often to re-measure and report");

    public static final ConfigKey MEASURE_CREDENTIAL = ConfigKey.secret(
            "measure.credential", false,
            "the reporting credential, holding measure-source; prefer the environment override");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            CORE_URL, CREDENTIAL, PLATFORM_KIND, CACHE_TTL_SECONDS, SHOW_SUBJECT_ID,
            MEASURE_ENABLED, MEASURE_NAME, MEASURE_WINDOW_SECONDS, MEASURE_SWEEP_SECONDS,
            MEASURE_CREDENTIAL);

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
        if (measureEnabled(config)) {
            long window = measureWindow(config).toSeconds();
            long sweep = measureSweep(config).toSeconds();
            if (window <= 0) {
                problems.add("measure.windowseconds must be positive");
            }
            if (sweep <= 0) {
                problems.add("measure.sweepseconds must be positive");
            }
            // The narrowing from 11.1 is that a role granted on a measure
            // survives the reporter going away: the observation goes stale,
            // decide refuses, and nothing emits. The mitigation is that a
            // reporter refreshes well inside the age a rule will accept -- so a
            // cadence at or above the window is a reporter that can never keep
            // an observation fresh for a rule asking about that window.
            if (window > 0 && sweep >= window) {
                problems.add("measure.sweepseconds (" + sweep + ") must be well below "
                        + "measure.windowseconds (" + window + "). A reporter that measures no "
                        + "more often than the window it measures leaves every observation "
                        + "stale, and a stale observation refuses without telling anybody.");
            }
            if (config.findString(MEASURE_CREDENTIAL).filter(c -> !c.isBlank()).isEmpty()) {
                problems.add("measure.enabled is set but measure.credential is not. Reporting "
                        + "needs its own credential holding measure-source: the read-only one "
                        + "this connector already has cannot write, and sharing them would give "
                        + "the dashboard's credential the ability to manufacture entitlement.");
            }
        }

        return problems;
    }

    public static boolean measureEnabled(Config config) {
        return config.findBoolean(MEASURE_ENABLED).orElse(false);
    }

    public static String measureName(Config config) {
        return config.findString(MEASURE_NAME).filter(s -> !s.isBlank()).orElse("playtime");
    }

    /** The trailing window. Seven days by default, matching the rule it is written for. */
    public static Duration measureWindow(Config config) {
        return Duration.ofSeconds(
                config.findInt(MEASURE_WINDOW_SECONDS).map(Integer::longValue).orElse(604_800L));
    }

    /** How often to re-measure. Fifteen minutes by default. */
    public static Duration measureSweep(Config config) {
        return Duration.ofSeconds(
                config.findInt(MEASURE_SWEEP_SECONDS).map(Integer::longValue).orElse(900L));
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
