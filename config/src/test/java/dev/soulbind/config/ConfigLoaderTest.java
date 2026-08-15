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

package dev.soulbind.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.ConfigKey.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T1 — the shared TOML configuration loader. */
class ConfigLoaderTest {

    private static final ConfigKey HOST =
            ConfigKey.required("server.host", Type.STRING, "address to bind");
    private static final ConfigKey PORT =
            ConfigKey.required("server.port", Type.INTEGER, "port to bind");
    private static final ConfigKey TLS =
            ConfigKey.optional("server.tls", Type.BOOLEAN, "terminate TLS directly");
    private static final ConfigKey PASSWORD =
            ConfigKey.secret("storage.password", false, "database password");

    private static final ConfigSchema SCHEMA = ConfigSchema.of(HOST, PORT, TLS, PASSWORD);

    private static Config parse(String toml, Map<String, String> env) {
        return ConfigLoader.parse(toml, "test", SCHEMA, env);
    }

    private static Config parse(String toml) {
        return parse(toml, Map.of());
    }

    @Nested
    @DisplayName("key declaration")
    class KeyDeclaration {

        @ParameterizedTest
        @ValueSource(strings = {
            "server_host",      // underscore: would collide with server.host
            "server.my-key",    // hyphen: same hazard
            "Server.host",      // uppercase
            "server..host",     // empty segment
            ".host",
            "host.",
            "9lives.host",      // leading digit
            "",
        })
        @DisplayName("an ambiguous or malformed key path is refused")
        void refusesBadPaths(String path) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigKey.required(path, Type.STRING, "x"),
                    () -> "accepted '" + path + "'");
        }

        @Test
        @DisplayName("a key with no description is refused")
        void refusesUndescribedKey() {
            // A key nobody described is a key nobody can set correctly, and
            // `soulbind doctor` has nothing to print beside it.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigKey.required("a.b", Type.STRING, "  "));
        }

        @ParameterizedTest
        @CsvSource({
            "server.host,SOULBIND_SERVER_HOST",
            "port,SOULBIND_PORT",
            "a.b.c,SOULBIND_A_B_C",
        })
        @DisplayName("maps to exactly one environment variable")
        void envName(String path, String expected) {
            assertEquals(expected, ConfigKey.required(path, Type.STRING, "x").envName());
        }

        @Test
        @DisplayName("the path rule makes the environment mapping injective")
        void envMappingIsInjective() {
            // The rule and the property it exists for, asserted together. If the
            // path rule is ever relaxed to allow underscores, `a.b_c` and
            // `a_b.c` would both become SOULBIND_A_B_C -- an operator setting a
            // secret would silently configure a different key. This test is what
            // makes that relaxation fail rather than ship.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigKey.required("a.b_c", Type.STRING, "x"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigKey.required("a_b.c", Type.STRING, "x"));
        }

        @Test
        @DisplayName("a schema with a duplicated key is refused")
        void refusesDuplicateKeys() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigSchema.of(
                            ConfigKey.required("a.b", Type.STRING, "one"),
                            ConfigKey.optional("a.b", Type.INTEGER, "two")));
        }
    }

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("reads values of every declared type")
        void readsValues() {
            Config config = parse("""
                    [server]
                    host = "0.0.0.0"
                    port = 8080
                    tls = true
                    """);
            assertEquals("0.0.0.0", config.getString(HOST));
            assertEquals(8080, config.getInt(PORT));
            assertTrue(config.getBoolean(TLS));
        }

        @Test
        @DisplayName("an absent optional key reads as absent, not as a default nobody chose")
        void absentOptional() {
            Config config = parse("""
                    [server]
                    host = "h"
                    port = 1
                    """);
            assertTrue(config.findBoolean(TLS).isEmpty());
            // Reading it as required is a programming error, and says so rather
            // than handing back `false` -- which an operator never wrote.
            assertThrows(IllegalStateException.class, () -> config.getBoolean(TLS));
        }

        @Test
        @DisplayName("a missing required key is reported, naming its override variable")
        void missingRequired() {
            ConfigException e = assertThrows(
                    ConfigException.class, () -> parse("[server]\nhost = \"h\"\n"));
            assertEquals(1, e.problems().size());
            assertTrue(e.getMessage().contains("server.port"), e.getMessage());
            assertTrue(
                    e.getMessage().contains("SOULBIND_SERVER_PORT"),
                    () -> "the message must say how to supply it: " + e.getMessage());
        }

        @Test
        @DisplayName("an unknown key is rejected -- a typo must not read as a default")
        void unknownKeyRejected() {
            ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                    [server]
                    host = "h"
                    port = 1
                    prot = 2
                    """));
            assertTrue(e.getMessage().contains("unknown key 'server.prot'"), e.getMessage());
        }

        @Test
        @DisplayName("a near-miss key is named in the suggestion")
        void suggestsNearMiss() {
            ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                    [server]
                    hosts = "h"
                    port = 1
                    """));
            assertTrue(
                    e.getMessage().contains("did you mean 'server.host'"),
                    () -> "expected a suggestion: " + e.getMessage());
        }

        @Test
        @DisplayName("an unrelated unknown key gets no confident suggestion")
        void doesNotGuessWildly() {
            // Suggesting an unrelated key is worse than suggesting nothing: it
            // sends the operator to change something that was already correct.
            ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                    [server]
                    host = "h"
                    port = 1
                    quixotic = 3
                    """));
            assertFalse(e.getMessage().contains("did you mean"), e.getMessage());
        }

        @Test
        @DisplayName("a value of the wrong type is reported with both types")
        void wrongType() {
            ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                    [server]
                    host = "h"
                    port = "8080"
                    """));
            assertTrue(e.getMessage().contains("server.port"), e.getMessage());
            assertTrue(e.getMessage().contains("integer"), e.getMessage());
            assertTrue(e.getMessage().contains("found a string"), e.getMessage());
        }

        @Test
        @DisplayName("malformed TOML is reported with a line number")
        void malformedToml() {
            ConfigException e = assertThrows(
                    ConfigException.class, () -> parse("[server\nhost = \"h\"\n"));
            assertTrue(e.getMessage().contains("line"), e.getMessage());
        }

        @Test
        @DisplayName("every problem is reported at once, not one per restart")
        void reportsAllProblems() {
            // An operator who has to restart the service to discover the next
            // error stops reading the message and starts guessing.
            ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                    [server]
                    port = "not-a-number"
                    """));
            assertEquals(
                    2,
                    e.problems().size(),
                    () -> "expected the missing host AND the bad port: " + e.problems());
            assertTrue(
                    e.problems().stream().anyMatch(p -> p.contains("missing required key")
                            && p.contains("server.host")),
                    () -> e.problems().toString());
            assertTrue(
                    e.problems().stream().anyMatch(p -> p.contains("server.port")
                            && p.contains("must be an integer")),
                    () -> e.problems().toString());
        }

        @Test
        @DisplayName("a present-but-wrong-typed key is not ALSO reported as missing")
        void wrongTypeIsNotAlsoMissing() {
            // It was found by the "report everything" test above: a required key
            // with a bad value produced both "must be an integer" and "missing
            // required key". Telling an operator to add a key they can see in
            // the file sends them looking in the wrong place entirely.
            ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                    [server]
                    host = "h"
                    port = "8080"
                    """));
            assertEquals(1, e.problems().size(), () -> e.problems().toString());
            assertFalse(e.getMessage().contains("missing"), e.getMessage());
        }

        @Test
        @DisplayName("a required key supplied only through a bad override is not called missing")
        void badOverrideIsNotMissing() {
            ConfigException e = assertThrows(
                    ConfigException.class,
                    () -> parse("[server]\nhost = \"h\"\n",
                            Map.of("SOULBIND_SERVER_PORT", "eighty")));
            assertEquals(1, e.problems().size(), () -> e.problems().toString());
            assertTrue(e.getMessage().contains("not an integer"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("environment overrides")
    class EnvironmentOverrides {

        @Test
        @DisplayName("the environment wins over the file")
        void envWins() {
            Config config = parse("""
                    [server]
                    host = "from-file"
                    port = 1
                    """, Map.of("SOULBIND_SERVER_HOST", "from-env"));
            assertEquals("from-env", config.getString(HOST));
        }

        @Test
        @DisplayName("the environment can satisfy a required key absent from the file")
        void envSatisfiesRequired() {
            // This is the point of the mechanism: a deployment that supplies a
            // secret should not also have to template a file around it.
            Config config = parse(
                    "[server]\nhost = \"h\"\n", Map.of("SOULBIND_SERVER_PORT", "9999"));
            assertEquals(9999, config.getInt(PORT));
        }

        @Test
        @DisplayName("an unrelated variable is ignored")
        void ignoresUnrelated() {
            Config config = parse("""
                    [server]
                    host = "h"
                    port = 1
                    """, Map.of("PATH", "/usr/bin", "SOULBIND_NOT_A_KEY", "x"));
            assertEquals("h", config.getString(HOST));
        }

        @ParameterizedTest
        @CsvSource({"true,true", "TRUE,true", "  false  ,false", "False,false"})
        @DisplayName("booleans parse case-insensitively, after trimming")
        void booleanParsing(String raw, boolean expected) {
            Config config = parse(
                    "[server]\nhost = \"h\"\nport = 1\n",
                    Map.of("SOULBIND_SERVER_TLS", raw));
            assertEquals(expected, config.getBoolean(TLS));
        }

        @ParameterizedTest
        @ValueSource(strings = {"yes", "1", "on", "y", "", "truthy"})
        @DisplayName("anything else is refused, never quietly treated as false")
        void refusesNonBooleans(String raw) {
            // Boolean.parseBoolean maps every non-"true" string to false, which
            // would turn `SOULBIND_SERVER_TLS=yes` into TLS silently disabled --
            // the precise class of bug this loader exists to prevent.
            ConfigException e = assertThrows(
                    ConfigException.class,
                    () -> parse("[server]\nhost = \"h\"\nport = 1\n",
                            Map.of("SOULBIND_SERVER_TLS", raw)));
            assertTrue(e.getMessage().contains("SOULBIND_SERVER_TLS"), e.getMessage());
            assertTrue(e.getMessage().contains("exactly true or false"), e.getMessage());
        }

        @Test
        @DisplayName("a non-numeric integer override is refused")
        void refusesNonInteger() {
            ConfigException e = assertThrows(
                    ConfigException.class,
                    () -> parse("[server]\nhost = \"h\"\nport = 1\n",
                            Map.of("SOULBIND_SERVER_PORT", "eighty")));
            assertTrue(e.getMessage().contains("not an integer"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("secrets")
    class Secrets {

        private Config withPassword() {
            return parse("[server]\nhost = \"h\"\nport = 1\n",
                    Map.of("SOULBIND_STORAGE_PASSWORD", "hunter2"));
        }

        @Test
        @DisplayName("are readable by the code that needs them")
        void readable() {
            assertEquals("hunter2", withPassword().getString(PASSWORD));
        }

        @Test
        @DisplayName("are redacted by describe(), which doctor and start-up logging use")
        void describeRedacts() {
            Map<String, String> described = withPassword().describe();
            assertEquals("(redacted)", described.get("storage.password"));
            assertEquals("h", described.get("server.host"), "non-secrets stay visible");
            assertEquals("(unset)", described.get("server.tls"));
        }

        @Test
        @DisplayName("are redacted by toString(), which nobody calls deliberately")
        void toStringRedacts() {
            // The dangerous path is the accidental one: a log line, a debugger
            // transcript, an exception message that interpolates the object.
            String rendered = withPassword().toString();
            assertFalse(rendered.contains("hunter2"), rendered);
            assertTrue(rendered.contains("(redacted)"), rendered);
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        private final Config config = parse("[server]\nhost = \"h\"\nport = 1\n");

        @Test
        @DisplayName("a key the schema does not declare cannot be read")
        void undeclaredKey() {
            // The same rule the file is held to, applied to the code, so the two
            // cannot disagree about what exists.
            ConfigKey foreign = ConfigKey.required("other.thing", Type.STRING, "x");
            assertThrows(IllegalArgumentException.class, () -> config.getString(foreign));
        }

        @Test
        @DisplayName("a key redeclared with a different meaning cannot be read")
        void redeclaredKey() {
            // Same path, different declaration: two components disagree about
            // what the key means, and whichever loaded first would win silently.
            ConfigKey shadowed = ConfigKey.optional("server.port", Type.INTEGER, "different");
            assertThrows(IllegalArgumentException.class, () -> config.getInt(shadowed));
        }

        @Test
        @DisplayName("reading a key at the wrong type is a programming error")
        void wrongAccessor() {
            assertThrows(IllegalArgumentException.class, () -> config.getBoolean(PORT));
        }
    }

    @Nested
    @DisplayName("schema composition")
    class SchemaComposition {

        @Test
        @DisplayName("merging keeps every key from both")
        void merge() {
            ConfigSchema merged = ConfigSchema.of(HOST).merge(ConfigSchema.of(PORT));
            assertEquals(List.of("server.host", "server.port"), List.copyOf(merged.paths()));
        }

        @Test
        @DisplayName("merging schemas that disagree about a key is refused")
        void mergeConflict() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConfigSchema.of(HOST).merge(
                            ConfigSchema.of(ConfigKey.optional("server.host", Type.INTEGER, "x"))));
        }
    }
}
