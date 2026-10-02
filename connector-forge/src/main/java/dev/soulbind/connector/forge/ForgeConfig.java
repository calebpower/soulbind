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
package dev.soulbind.connector.forge;

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
public final class ForgeConfig {

    private ForgeConfig() {
        throw new AssertionError("no instances");
    }

    public static final ConfigKey CORE_URL = ConfigKey.required(
            "core.url", Type.STRING, "where this connector reaches soulbind core");

    public static final ConfigKey CREDENTIAL = ConfigKey.secret(
            "core.credential", false,
            "this connector's credential; prefer the environment override");

    /**
     * The platform kind this connector speaks for.
     *
     * <p>An operator may rename it; a caller may not. That distinction is the
     * point — a connector that took its kind from a request could assert
     * identities on any platform, which is the whole authority the capability
     * model exists to divide up.
     */
    public static final ConfigKey PLATFORM_KIND = ConfigKey.optional(
            "platform.kind", Type.STRING, "the platform kind this connector speaks for");

    /**
     * The gate asked before an account is created.
     *
     * <p>Configurable because the gate name is policy, not code, and the
     * operator who writes the rule is the one who should choose what it is
     * called. It deliberately does <em>not</em> default to {@code register}:
     * that gate belongs to the forum connector, and a rule written against it
     * would govern forum signup as well as this.
     */
    public static final ConfigKey GATE = ConfigKey.optional(
            "gate.name", Type.STRING, "the gate asked before an account is created");

    public static final ConfigKey FAIL_MODE = ConfigKey.optional(
            "gate.failmode", Type.STRING, "closed (default) or open, when core is unreachable");

    /** How often to poll core for events, in seconds. */
    public static final ConfigKey POLL_SECONDS = ConfigKey.optional(
            "events.pollseconds", Type.INTEGER, "how often to poll core for events");

    public static final ConfigKey HOST_URL = ConfigKey.required(
            "host.url", Type.STRING, "the forge's own administrative interface");

    /**
     * The forge's administrative token.
     *
     * <p>A secret, and one that grants far more than soulbind needs — it can
     * create and deactivate any account on the host. Redacted wherever
     * configuration is printed, and better supplied through the environment
     * than written into a file.
     */
    public static final ConfigKey HOST_TOKEN = ConfigKey.secret(
            "host.token", false, "the forge's admin token; prefer the environment override");

    public static final ConfigKey HOST_TIMEOUT_MS = ConfigKey.optional(
            "host.timeoutms", Type.INTEGER, "how long to wait on the forge before giving up");

    /**
     * Where the signup form listens.
     *
     * <p>Loopback by default, because the reverse proxy in front of the forge
     * already terminates TLS for the name people type and a second listener on
     * a public interface would be a second thing to get right. An operator who
     * genuinely wants it exposed can say so.
     */
    public static final ConfigKey SIGNUP_BIND = ConfigKey.optional(
            "signup.bind", Type.STRING, "the interface the signup form listens on");

    public static final ConfigKey SIGNUP_PORT = ConfigKey.optional(
            "signup.port", Type.INTEGER, "the port the signup form listens on");

    /**
     * The path the proxy routes to this connector.
     *
     * <p>Configurable because it has to match the proxy's rule, and a mismatch
     * between the two is the kind of thing that produces a form whose submit
     * button 404s.
     */
    public static final ConfigKey SIGNUP_PATH = ConfigKey.optional(
            "signup.path", Type.STRING, "the path the signup form is served at");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            CORE_URL, CREDENTIAL, PLATFORM_KIND, GATE, FAIL_MODE,
            POLL_SECONDS, HOST_URL, HOST_TOKEN, HOST_TIMEOUT_MS,
            SIGNUP_BIND, SIGNUP_PORT, SIGNUP_PATH);

    public static Config load(Path file) {
        return ConfigLoader.load(file, SCHEMA);
    }

    public static String platformKind(Config config) {
        return config.findString(PLATFORM_KIND).orElse("forge");
    }

    public static String gate(Config config) {
        return config.findString(GATE).orElse("forge.register");
    }

    public static DecisionCache.FailMode failMode(Config config) {
        return DecisionCache.FailMode.fromConfigName(config.findString(FAIL_MODE).orElse(null));
    }

    public static int pollSeconds(Config config) {
        return config.findInt(POLL_SECONDS).orElse(15);
    }

    public static int timeoutMs(Config config) {
        return config.findInt(HOST_TIMEOUT_MS).orElse(5_000);
    }

    public static String signupBind(Config config) {
        return config.findString(SIGNUP_BIND).orElse("127.0.0.1");
    }

    public static int signupPort(Config config) {
        return config.findInt(SIGNUP_PORT).orElse(7191);
    }

    public static String signupPath(Config config) {
        return config.findString(SIGNUP_PATH).orElse("/soulbind/signup");
    }

    /** Checks the schema cannot express, returned together. */
    public static List<String> validate(Config config) {
        List<String> problems = new ArrayList<>();

        int poll = pollSeconds(config);
        if (poll < 1 || poll > 3600) {
            problems.add("events.pollseconds must be between 1 and 3600, was " + poll
                    + ". Below 1 this polls core continuously; above an hour an account stays "
                    + "live so long after its requirements lapsed that the gate stops meaning "
                    + "anything.");
        }

        int timeout = timeoutMs(config);
        if (timeout < 250 || timeout > 60_000) {
            problems.add("host.timeoutms must be between 250 and 60000, was " + timeout
                    + ". Below 250ms a healthy forge under load reads as an outage and the fail "
                    + "mode denies people for no reason; above a minute somebody waits out a "
                    + "dead host instead of being told.");
        }

        // Blank is not absent. Absent is caught by the loader as a missing
        // required key; a quoted empty string looks configured and is not.
        blankCheck(problems, "core.url", config.getString(CORE_URL));
        blankCheck(problems, "host.url", config.getString(HOST_URL));
        blankCheck(problems, "platform.kind", platformKind(config));
        blankCheck(problems, "gate.name", gate(config));
        blankCheck(problems, "signup.bind", signupBind(config));

        int port = signupPort(config);
        if (port < 1 || port > 65_535) {
            problems.add("signup.port must be between 1 and 65535, was " + port + ".");
        }

        String path = signupPath(config);
        if (!path.startsWith("/")) {
            // A path the proxy cannot match is a form whose submit button 404s,
            // and the proxy will not tell anybody why.
            problems.add("signup.path must begin with '/', was '" + path + "'.");
        }

        return problems;
    }

    private static void blankCheck(List<String> problems, String path, String value) {
        if (value.isBlank()) {
            problems.add(path + " must not be blank. Leave it out to take the default, or set "
                    + "it to something — a quoted empty string reads as configured and is not.");
        }
    }
}
