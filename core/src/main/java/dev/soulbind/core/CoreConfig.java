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

package dev.soulbind.core;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigKey;
import dev.soulbind.config.ConfigKey.Type;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.config.ConfigSchema;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Every configuration key core understands.
 *
 * <p>Declared in one place so the loader can reject anything else. A key that
 * is not here is a typo, and saying so at start-up is far cheaper than the
 * alternative: the setting appears present, the default is used, and the
 * symptom appears somewhere unrelated weeks later.
 *
 * <p>Notice what is <em>not</em> here — no platform names, and no backend
 * names. Core learns platform kinds at runtime from connector registration,
 * and the set of storage backends is the storage package's business. Both are
 * asserted by guards rather than left to discipline.
 */
public final class CoreConfig {

    private CoreConfig() {
        throw new AssertionError("no instances");
    }

    /** Address core binds its connector transport to. */
    public static final ConfigKey SERVER_HOST = ConfigKey.optional(
            "server.host", Type.STRING,
            "address to bind the connector transport to; defaults to loopback");

    /** Port core binds its connector transport to. */
    public static final ConfigKey SERVER_PORT = ConfigKey.required(
            "server.port", Type.INTEGER, "port to bind the connector transport to");

    /**
     * Which storage backend to use.
     *
     * <p>The permitted values live with the backends themselves, not here: a
     * list in this file would be a second copy that drifts, and would put a
     * backend name outside the storage package, which a guard forbids.
     */
    public static final ConfigKey STORAGE_BACKEND = ConfigKey.required(
            "storage.backend", Type.STRING, "which storage backend to use");

    /** JDBC URL for the chosen backend. */
    public static final ConfigKey STORAGE_URL = ConfigKey.required(
            "storage.url", Type.STRING, "connection URL for the chosen storage backend");

    /** Storage user, where the backend has one. */
    public static final ConfigKey STORAGE_USER = ConfigKey.optional(
            "storage.user", Type.STRING, "storage username, where the backend requires one");

    /**
     * Storage password.
     *
     * <p>A secret: redacted wherever configuration is printed, and normally
     * supplied through {@code SOULBIND_STORAGE_PASSWORD} rather than written
     * into a file that gets committed by accident.
     */
    public static final ConfigKey STORAGE_PASSWORD = ConfigKey.secret(
            "storage.password", false, "storage password; prefer the environment override");

    /**
     * How long a signed request stays acceptable.
     *
     * <p>Bounded on both sides at load time. A window of zero refuses every
     * request including correct ones, and an unbounded window makes replay
     * protection depend entirely on the nonce store never losing an entry.
     */
    public static final ConfigKey SIGNATURE_WINDOW_SECONDS = ConfigKey.optional(
            "protocol.signaturewindowseconds", Type.INTEGER,
            "how many seconds a signed request stays acceptable");

    /** The whole schema. */
    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            SERVER_HOST,
            SERVER_PORT,
            STORAGE_BACKEND,
            STORAGE_URL,
            STORAGE_USER,
            STORAGE_PASSWORD,
            SIGNATURE_WINDOW_SECONDS);

    /** Loads core's configuration from a file, with the process environment. */
    public static Config load(Path file) {
        return ConfigLoader.load(file, SCHEMA);
    }

    /** Loads core's configuration with an explicit environment, for tests. */
    public static Config load(Path file, Map<String, String> env) {
        return ConfigLoader.load(file, SCHEMA, env);
    }

    /** The bind address, or loopback when unset. */
    public static String host(Config config) {
        // A default that opens a socket to the whole network is not a default
        // anybody should get by omission.
        return config.findString(SERVER_HOST).orElse("127.0.0.1");
    }

    /** The signature window, or the default, in seconds. */
    public static int signatureWindowSeconds(Config config) {
        return config.findInt(SIGNATURE_WINDOW_SECONDS).orElse(300);
    }

    /**
     * Checks the values the schema cannot express, and returns every complaint.
     *
     * <p>Type and presence are the loader's job; range and cross-key coherence
     * are core's. Returned rather than thrown so a caller — {@code soulbind
     * doctor} in particular — can report all of them at once, the same way the
     * loader does.
     */
    public static java.util.List<String> validate(Config config) {
        java.util.List<String> problems = new java.util.ArrayList<>();

        int port = config.getInt(SERVER_PORT);
        if (port < 1 || port > 65535) {
            problems.add("server.port must be between 1 and 65535, was " + port);
        }

        Optional<Integer> window = config.findInt(SIGNATURE_WINDOW_SECONDS);
        if (window.isPresent() && (window.get() < 1 || window.get() > 3600)) {
            problems.add("protocol.signaturewindowseconds must be between 1 and 3600, was "
                    + window.get()
                    + ". Zero refuses every request including correct ones; an unbounded "
                    + "window makes replay protection depend entirely on the nonce store "
                    + "never losing an entry.");
        }

        return problems;
    }
}
