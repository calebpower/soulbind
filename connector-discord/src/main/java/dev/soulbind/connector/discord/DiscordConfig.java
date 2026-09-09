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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    /**
     * One binding's fields -- the element schema for {@link #ROLES}.
     *
     * <p>These paths are relative to their element, so they are bare names
     * rather than dotted: {@code gate}, not {@code effector.roles.gate}.
     */
    public static final ConfigKey BINDING_GATE = ConfigKey.required(
            "gate", Type.STRING, "the gate whose events drive this role");

    public static final ConfigKey BINDING_ROLE = ConfigKey.required(
            "role", Type.STRING, "the role granted or removed");

    public static final ConfigKey BINDING_MODE = ConfigKey.optional(
            "mode", Type.STRING,
            "grant, revoke, or both (the default) -- which half of the stream to act on");

    public static final ConfigSchema BINDING =
            ConfigSchema.of(BINDING_GATE, BINDING_ROLE, BINDING_MODE);

    /**
     * The roles this connector maintains.
     *
     * <p>Replaces the single {@code effector.role} / {@code effector.gate} pair,
     * which could express exactly one role. Absent or empty means the connector
     * displays and accepts codes but changes nothing -- a reasonable first
     * deployment, and the posture the estate has been running in.
     */
    public static final ConfigKey ROLES = ConfigKey.tables(
            "effector.roles", BINDING,
            "the gate-to-role bindings this connector maintains");

    public static final ConfigKey FAIL_MODE = ConfigKey.optional(
            "gate.failmode", Type.STRING, "closed (default) or open, when core is unreachable");

    /** How often to poll for events, in seconds. */
    public static final ConfigKey POLL_SECONDS = ConfigKey.optional(
            "events.pollseconds", Type.INTEGER, "how often to poll core for events");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            CORE_URL, CREDENTIAL, BOT_TOKEN, GUILD_ID, PLATFORM_KIND,
            ROLES, FAIL_MODE, POLL_SECONDS);

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

        problems.addAll(bindingProblems(config));

        return problems;
    }

    /**
     * The bindings, as configured.
     *
     * <p>Assumes {@link #validate} found no problems: an unparseable mode reads
     * as {@code BOTH} here and is reported there, because a loader that threw
     * would report one problem where the contract is to report them all.
     */
    public static List<RoleBinding> bindings(Config config) {
        List<RoleBinding> bindings = new ArrayList<>();
        for (Config entry : config.getTables(ROLES)) {
            RoleBinding.Mode mode =
                    RoleBinding.Mode.fromConfigName(entry.findString(BINDING_MODE).orElse(null));
            bindings.add(new RoleBinding(
                    entry.getString(BINDING_GATE),
                    entry.getString(BINDING_ROLE),
                    mode == null ? RoleBinding.Mode.BOTH : mode));
        }
        return List.copyOf(bindings);
    }

    private static List<String> bindingProblems(Config config) {
        List<String> problems = new ArrayList<>();
        List<Config> entries = config.getTables(ROLES);
        Set<List<String>> seen = new HashSet<>();
        Set<String> granted = new HashSet<>();
        Set<String> revocable = new HashSet<>();

        for (int i = 0; i < entries.size(); i++) {
            Config entry = entries.get(i);
            String where = "effector.roles[" + i + "]";
            String gate = entry.getString(BINDING_GATE).strip();
            String role = entry.getString(BINDING_ROLE).strip();

            if (gate.isEmpty() || role.isEmpty()) {
                // Blank is not absent. Absent is caught by the loader as a
                // missing required key; a quoted empty string looks configured
                // and binds nothing.
                problems.add(where + ": gate and role must not be blank. Leave the whole "
                        + "[[effector.roles]] entry out to bind nothing.");
                continue;
            }

            String raw = entry.findString(BINDING_MODE).orElse(null);
            RoleBinding.Mode mode = RoleBinding.Mode.fromConfigName(raw);
            if (mode == null) {
                problems.add(where + ": mode '" + raw + "' is not grant, revoke or both");
                continue;
            }

            if (!seen.add(List.of(gate, role, mode.name()))) {
                problems.add(where + ": duplicate binding of gate '" + gate + "' to role '"
                        + role + "'. It would apply the same change twice for every event.");
            }
            if (mode.grants()) {
                granted.add(role);
            }
            if (mode.revokes()) {
                revocable.add(role);
            }
        }

        for (String role : granted) {
            if (!revocable.contains(role)) {
                // A role this connector can only ever add diverges from core's
                // answer permanently, which is the divergence the met/lost pair
                // exists to prevent. The usual cause is half a hysteresis pair:
                // the grant gate configured, the keep gate forgotten, and a
                // role nothing will ever take back.
                problems.add("role '" + role + "' is granted by a binding but no binding can "
                        + "remove it, so it will never come off. Add a binding with "
                        + "mode = \"revoke\" naming the gate that should keep it, or use "
                        + "mode = \"both\" on a single gate.");
            }
        }
        return problems;
    }
}
