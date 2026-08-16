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
package dev.soulbind.connector.velocity;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigKey;
import dev.soulbind.config.ConfigKey.Type;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.config.ConfigSchema;
import dev.soulbind.sdk.DecisionCache;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The plugin's configuration.
 *
 * <p>TOML through the shared loader, which is also Velocity's own native
 * format — so an operator editing this file is editing something that looks
 * like everything else in the directory.
 *
 * <p>Unknown keys are rejected, as everywhere: a misspelt key that is silently
 * ignored is the most expensive configuration bug there is, and on a proxy it
 * means a gate an operator believes is enforced and is not.
 */
public final class VelocityConfig {

    private VelocityConfig() {
        throw new AssertionError("no instances");
    }

    public static final ConfigKey CORE_URL = ConfigKey.required(
            "core.url", Type.STRING, "where this connector reaches soulbind core");

    public static final ConfigKey CREDENTIAL = ConfigKey.secret(
            "core.credential", false,
            "this connector's credential; prefer the environment override");

    /**
     * The gate consulted at join.
     *
     * <p>Optional. A deployment that only wants {@code /link} and no enforcement
     * leaves it unset, and the plugin then never blocks anybody — which must be
     * expressible, because turning enforcement on before the community has
     * linked is how an operator locks out their own players.
     */
    public static final ConfigKey JOIN_GATE = ConfigKey.optional(
            "gate.join", Type.STRING, "gate consulted when a player connects; unset disables it");

    public static final ConfigKey KICK_MESSAGE = ConfigKey.optional(
            "gate.kickmessage", Type.STRING, "shown to a player the join gate denies");

    /**
     * What to do when core cannot be reached.
     *
     * <p>Defaults to closed, and only the exact word {@code open} changes it.
     */
    public static final ConfigKey FAIL_MODE = ConfigKey.optional(
            "gate.failmode", Type.STRING, "closed (default) or open, when core is unreachable");

    /**
     * How long a join may wait for a decision.
     *
     * <p>Bounded, and bounded tightly. A join event that waits on a network
     * round trip holds a proxy thread, and a proxy that stops accepting
     * connections because one backend service is slow is a worse outcome than
     * any single decision. When the wait expires the fail mode decides, exactly
     * as if core were down — because from this player's perspective it was.
     */
    public static final ConfigKey DECISION_TIMEOUT_MILLIS = ConfigKey.optional(
            "gate.decisiontimeoutmillis", Type.INTEGER,
            "how long a join may wait for a decision before the fail mode decides");

    /** The platform kind this connector reports. */
    public static final ConfigKey PLATFORM_KIND = ConfigKey.optional(
            "platform.kind", Type.STRING, "the platform kind this connector speaks for");

    /**
     * The Bedrock name prefix, if the deployment configures one.
     *
     * <p>Stripped for display only. The identity is always the UUID — a prefix
     * is configurable and can be turned off, and treating it as an identifier
     * is how a rename reassigns an entitlement.
     */
    public static final ConfigKey BEDROCK_PREFIX = ConfigKey.optional(
            "platform.bedrockprefix", Type.STRING,
            "name prefix Bedrock players carry, stripped for display only");

    /** The permission group granted when a subject satisfies the gate. */
    public static final ConfigKey EFFECTOR_GROUP = ConfigKey.optional(
            "effector.group", Type.STRING,
            "permission group granted when a subject satisfies the join gate");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            CORE_URL,
            CREDENTIAL,
            JOIN_GATE,
            KICK_MESSAGE,
            FAIL_MODE,
            DECISION_TIMEOUT_MILLIS,
            PLATFORM_KIND,
            BEDROCK_PREFIX,
            EFFECTOR_GROUP);

    public static Config load(Path file) {
        return ConfigLoader.load(file, SCHEMA);
    }

    /** The default shown to a denied player, when the operator sets none. */
    public static final String DEFAULT_KICK_MESSAGE =
            "You need to link your account before joining. Use /link to start.";

    public static String kickMessage(Config config) {
        return config.findString(KICK_MESSAGE).orElse(DEFAULT_KICK_MESSAGE);
    }

    public static DecisionCache.FailMode failMode(Config config) {
        return DecisionCache.FailMode.fromConfigName(config.findString(FAIL_MODE).orElse(null));
    }

    public static Duration decisionTimeout(Config config) {
        return Duration.ofMillis(config.findInt(DECISION_TIMEOUT_MILLIS).orElse(1500));
    }

    public static String platformKind(Config config) {
        return config.findString(PLATFORM_KIND).orElse("game");
    }

    /**
     * Range and coherence checks the schema cannot express.
     *
     * <p>Returned rather than thrown, so an operator sees every complaint at
     * once instead of one per restart.
     */
    public static List<String> validate(Config config) {
        List<String> problems = new ArrayList<>();

        int timeout = config.findInt(DECISION_TIMEOUT_MILLIS).orElse(1500);
        if (timeout < 50 || timeout > 10_000) {
            problems.add("gate.decisiontimeoutmillis must be between 50 and 10000, was "
                    + timeout + ". Below 50 no round trip completes and every join falls to "
                    + "the fail mode; above 10000 a slow core holds proxy threads long enough "
                    + "to stop the proxy accepting connections at all.");
        }

        if (config.findString(JOIN_GATE).isEmpty()
                && config.findString(EFFECTOR_GROUP).isPresent()) {
            // Not fatal, but almost certainly not what was meant: a group that
            // is granted on an event nobody is watching for.
            problems.add("effector.group is set but gate.join is not, so nothing will ever "
                    + "grant it. Set gate.join, or remove effector.group.");
        }

        return problems;
    }
}
