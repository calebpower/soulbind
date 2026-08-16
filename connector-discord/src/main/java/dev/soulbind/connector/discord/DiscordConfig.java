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
package dev.soulbind.connector.discord;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigKey;
import dev.soulbind.config.ConfigKey.Type;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.config.ConfigSchema;
import dev.soulbind.sdk.DecisionCache;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Everything this connector reads from configuration. */
public final class DiscordConfig {

    private DiscordConfig() {
        throw new AssertionError("no instances");
    }

    public static final ConfigKey CORE_URL = ConfigKey.required(
            "core.url", Type.STRING, "where this connector reaches soulbind core");

    public static final ConfigKey CREDENTIAL = ConfigKey.secret(
            "core.credential", false,
            "this connector's credential; prefer the environment override");

    /**
     * The chat platform's bot token.
     *
     * <p>A secret, and one that grants far more than soulbind needs — it is the
     * whole bot. Redacted wherever configuration is printed, and the doctor
     * warns when it is written into a file rather than supplied through the
     * environment.
     */
    public static final ConfigKey BOT_TOKEN = ConfigKey.secret(
            "platform.token", false, "the bot token; prefer the environment override");

    public static final ConfigKey GUILD_ID = ConfigKey.optional(
            "platform.guild", Type.STRING,
            "the server whose commands are registered; unset registers globally");

    public static final ConfigKey PLATFORM_KIND = ConfigKey.optional(
            "platform.kind", Type.STRING, "the platform kind this connector speaks for");

    /** The role granted when a subject satisfies the gate. */
    public static final ConfigKey LINKED_ROLE = ConfigKey.optional(
            "effector.role", Type.STRING,
            "role granted when a subject satisfies the configured gate");

    public static final ConfigKey GATE = ConfigKey.optional(
            "effector.gate", Type.STRING, "the gate whose events drive the role");

    public static final ConfigKey FAIL_MODE = ConfigKey.optional(
            "gate.failmode", Type.STRING, "closed (default) or open, when core is unreachable");

    /** How often to poll for events, in seconds. */
    public static final ConfigKey POLL_SECONDS = ConfigKey.optional(
            "events.pollseconds", Type.INTEGER, "how often to poll core for events");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            CORE_URL, CREDENTIAL, BOT_TOKEN, GUILD_ID, PLATFORM_KIND,
            LINKED_ROLE, GATE, FAIL_MODE, POLL_SECONDS);

    public static Config load(Path file) {
        return ConfigLoader.load(file, SCHEMA);
    }

    public static String platformKind(Config config) {
        return config.findString(PLATFORM_KIND).orElse("chat");
    }

    public static DecisionCache.FailMode failMode(Config config) {
        return DecisionCache.FailMode.fromConfigName(config.findString(FAIL_MODE).orElse(null));
    }

    public static int pollSeconds(Config config) {
        return config.findInt(POLL_SECONDS).orElse(15);
    }

    /** Checks the schema cannot express, returned together. */
    public static List<String> validate(Config config) {
        List<String> problems = new ArrayList<>();

        int poll = pollSeconds(config);
        if (poll < 1 || poll > 3600) {
            problems.add("events.pollseconds must be between 1 and 3600, was " + poll
                    + ". Below 1 this polls core continuously; above an hour a role arrives so "
                    + "long after the link that somebody will have asked why it did not work.");
        }

        boolean hasRole = config.findString(LINKED_ROLE).isPresent();
        boolean hasGate = config.findString(GATE).isPresent();
        if (hasRole != hasGate) {
            // Either alone does nothing, and silently: a role nothing grants,
            // or a gate whose events nobody acts on.
            problems.add("effector.role and effector.gate go together: one without the other "
                    + "is a role nothing will ever grant, or a gate whose events nothing acts "
                    + "on. Set both, or neither.");
        }

        return problems;
    }
}
