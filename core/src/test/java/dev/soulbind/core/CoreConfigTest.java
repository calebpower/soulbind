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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigException;
import dev.soulbind.config.ConfigKey;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.core.storage.Backend;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T1 — core's configuration schema and the checks the schema cannot express. */
class CoreConfigTest {

    private static final String MINIMAL = """
            [server]
            port = 7000

            [storage]
            backend = "%s"
            url = "jdbc:example:memory"
            """;

    private static Config load(String toml, Map<String, String> env) {
        return ConfigLoader.parse(toml, "test", CoreConfig.SCHEMA, env);
    }

    private static Config minimal() {
        return load(MINIMAL.formatted(Backend.values()[0].configName()), Map.of());
    }

    @Test
    @DisplayName("a minimal configuration loads")
    void minimalLoads() {
        Config config = minimal();
        assertEquals(7000, config.getInt(CoreConfig.SERVER_PORT));
        assertTrue(CoreConfig.validate(config).isEmpty(), CoreConfig.validate(config)::toString);
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    @DisplayName("every backend the storage package offers is configurable")
    void everyBackendIsConfigurable(Backend backend) {
        // Parameterised over the enum rather than over a list written here: a
        // backend added to the storage package must be reachable from
        // configuration, and a hand-written list would let one arrive unwired.
        Config config = load(MINIMAL.formatted(backend.configName()), Map.of());
        assertEquals(
                backend,
                Backend.fromConfigName(config.getString(CoreConfig.STORAGE_BACKEND)).orElseThrow());
    }

    @Test
    @DisplayName("the bind address defaults to loopback, never to the whole network")
    void defaultHostIsLoopback() {
        // A default that opens a socket to every interface is not a default
        // anybody should receive by omission.
        assertEquals("127.0.0.1", CoreConfig.host(minimal()));
    }

    @Test
    @DisplayName("an explicit bind address is honoured")
    void explicitHost() {
        Config config = load("""
                [server]
                host = "0.0.0.0"
                port = 7000

                [storage]
                backend = "%s"
                url = "jdbc:example:memory"
                """.formatted(Backend.values()[0].configName()), Map.of());
        assertEquals("0.0.0.0", CoreConfig.host(config));
    }

    @Test
    @DisplayName("a missing required key names its environment override")
    void missingRequired() {
        ConfigException e = assertThrows(
                ConfigException.class, () -> load("[server]\nport = 1\n", Map.of()));
        assertTrue(e.getMessage().contains("storage.backend"), e.getMessage());
        assertTrue(e.getMessage().contains("SOULBIND_STORAGE_BACKEND"), e.getMessage());
    }

    @Test
    @DisplayName("an unknown key is rejected rather than ignored")
    void unknownKeyRejected() {
        ConfigException e = assertThrows(
                ConfigException.class,
                () -> load("""
                        [server]
                        port = 7000

                        [storage]
                        backend = "%s"
                        url = "jdbc:example:memory"
                        pasword = "x"
                        """.formatted(Backend.values()[0].configName()), Map.of()));
        assertTrue(e.getMessage().contains("unknown key"), e.getMessage());
        assertTrue(
                e.getMessage().contains("did you mean 'storage.password'"),
                () -> "a near-miss on a SECRET is the one most worth catching: " + e.getMessage());
    }

    @Test
    @DisplayName("the password is supplied by the environment and never printed")
    void passwordFromEnvironmentIsRedacted() {
        Config config = load(
                MINIMAL.formatted(Backend.values()[0].configName()),
                Map.of("SOULBIND_STORAGE_PASSWORD", "hunter2"));
        assertEquals("hunter2", config.getString(CoreConfig.STORAGE_PASSWORD));
        assertFalse(config.toString().contains("hunter2"), config.toString());
        assertEquals("(redacted)", config.describe().get("storage.password"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 99999})
    @DisplayName("an out-of-range port is refused with the range named")
    void portRange(int port) {
        Config config = load(
                MINIMAL.formatted(Backend.values()[0].configName()),
                Map.of("SOULBIND_SERVER_PORT", String.valueOf(port)));
        List<String> problems = CoreConfig.validate(config);
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("1 and 65535"), problems::toString);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 80, 7000, 65535})
    @DisplayName("a valid port passes")
    void portInRange(int port) {
        Config config = load(
                MINIMAL.formatted(Backend.values()[0].configName()),
                Map.of("SOULBIND_SERVER_PORT", String.valueOf(port)));
        assertTrue(CoreConfig.validate(config).isEmpty(), CoreConfig.validate(config)::toString);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 3601})
    @DisplayName("a signature window outside its bounds is refused, at both ends")
    void signatureWindowBounds(int seconds) {
        // Zero refuses every request including correct ones. An unbounded window
        // makes replay protection depend entirely on the nonce store never
        // losing an entry, which is not a property anybody can promise.
        Config config = load(
                MINIMAL.formatted(Backend.values()[0].configName()),
                Map.of("SOULBIND_PROTOCOL_SIGNATUREWINDOWSECONDS", String.valueOf(seconds)));
        assertEquals(1, CoreConfig.validate(config).size());
    }

    @Test
    @DisplayName("the signature window has a default, and it is bounded")
    void signatureWindowDefault() {
        int seconds = CoreConfig.signatureWindowSeconds(minimal());
        assertTrue(seconds >= 1 && seconds <= 3600, () -> "default out of its own bounds: " + seconds);
    }

    @Test
    @DisplayName("every problem is reported together, not one per restart")
    void validateReportsAll() {
        Config config = load(
                MINIMAL.formatted(Backend.values()[0].configName()),
                Map.of(
                        "SOULBIND_SERVER_PORT", "0",
                        "SOULBIND_PROTOCOL_SIGNATUREWINDOWSECONDS", "0"));
        assertEquals(2, CoreConfig.validate(config).size(), CoreConfig.validate(config)::toString);
    }

    @Test
    @DisplayName("no key is declared twice or ambiguously -- the schema builds")
    void schemaIsWellFormed() {
        // ConfigSchema.of throws on a duplicate or an environment-name clash, so
        // merely touching SCHEMA proves it. Named as a test so the failure is
        // reported here rather than as a class-initialisation error in whichever
        // unrelated test happened to run first.
        assertFalse(CoreConfig.SCHEMA.keys().isEmpty());
        for (ConfigKey key : CoreConfig.SCHEMA.keys()) {
            assertTrue(key.envName().startsWith("SOULBIND_"), key::toString);
        }
    }
}
