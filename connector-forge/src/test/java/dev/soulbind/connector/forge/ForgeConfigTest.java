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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigException;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.sdk.DecisionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * This connector's configuration, and the checks its schema cannot express.
 *
 * <p>Every default and every validation here is reachable only by starting the
 * connector for real, which is exactly the shape of code that goes untested and
 * then changes without anybody noticing. A default that silently moves is a
 * defect an operator discovers from behaviour, in production, with nothing to
 * grep for — so each one is stated here rather than left implicit in an
 * {@code orElse}.
 *
 * <p><b>What mutation testing does not vouch for here.</b> PIT kills every
 * mutant in {@code ForgeConfig}, but not through the assertions in
 * {@link #defaults()}. Its mutators replace a return with {@code 0}, {@code ""}
 * or {@code null} rather than perturbing a constant to a neighbouring value, and
 * each of those lands outside a validation band or trips {@code blankCheck} — so
 * the cross-checks kill them and the stated defaults are never the oracle. Two
 * attempts to prove otherwise, by deleting the assertions pinning the poll and
 * timeout defaults, left the score untouched; that is how this was found rather
 * than assumed. Those assertions still earn their place: they are what turns a
 * deliberate edit of 15 to 30 red. They are simply not what the mutation score
 * is measuring.
 */
class ForgeConfigTest {

    /**
     * A document with every table declared exactly once.
     *
     * <p>Composed rather than concatenated: TOML refuses a table defined twice,
     * so appending a second {@code [host]} block to a fixture that already has
     * one fails in the parser and says nothing about the code under test. Extra
     * keys go INSIDE their table, which is what these three parameters are for.
     */
    private static String conf(String coreExtra, String hostExtra, String rest) {
        return "[core]\nurl = \"http://127.0.0.1:7180\"\n" + coreExtra
                + "\n[host]\nurl = \"https://forge.example.invalid\"\n" + hostExtra
                + rest;
    }

    private static final String MINIMAL = conf("", "", "");

    private static Config load(String toml) {
        return ConfigLoader.parse(toml, "test", ForgeConfig.SCHEMA, Map.of());
    }

    @Test
    @DisplayName("load() reads a real file through this connector's schema")
    void loadReadsAFile(@TempDir Path dir) throws Exception {
        // parse() is what every other test here uses, which would leave the
        // shipped entry point -- the one the daemon will call -- run by nothing.
        Path file = dir.resolve("soulbind-forge.toml");
        Files.writeString(file, MINIMAL + """
                [platform]
                kind = "forge"
                """);

        assertEquals("forge", ForgeConfig.platformKind(ForgeConfig.load(file)));
    }

    @Test
    @DisplayName("a minimal configuration loads and validates")
    void minimalLoads() {
        Config config = load(MINIMAL);
        assertTrue(ForgeConfig.validate(config).isEmpty(), ForgeConfig.validate(config)::toString);
    }

    @Test
    @DisplayName("the defaults are the documented ones, asserted rather than assumed")
    void defaults() {
        Config config = load(MINIMAL);

        assertEquals("forge", ForgeConfig.platformKind(config));
        assertEquals("forge.register", ForgeConfig.gate(config));
        assertEquals(15, ForgeConfig.pollSeconds(config));
        assertEquals(5_000, ForgeConfig.timeoutMs(config));
        assertEquals(DecisionCache.FailMode.CLOSED, ForgeConfig.failMode(config),
                "an unset fail mode did not default to CLOSED; a connector that fails OPEN by"
                        + " accident creates accounts for everybody the moment core is"
                        + " unreachable");
    }

    @Test
    @DisplayName("the default gate is NOT the forum connector's register gate")
    void theGateIsNotTheForumsGate() {
        // `register` is owned by the forum connector. A rule written against it
        // governs forum signup, so defaulting to it here would silently couple
        // two platforms' admission policy and the coupling would be invisible
        // until somebody changed one of them.
        assertFalse("register".equals(ForgeConfig.gate(load(MINIMAL))),
                "this connector must not default to a gate another connector owns");
    }

    @Test
    @DisplayName("what is configured wins over the default")
    void overridesApply() {
        Config config = load(conf("", "timeoutms = 1200\n", """

                [platform]
                kind = "sourcehut"

                [gate]
                name = "forge.join"

                [events]
                pollseconds = 30
                """));

        assertEquals("sourcehut", ForgeConfig.platformKind(config));
        assertEquals("forge.join", ForgeConfig.gate(config));
        assertEquals(30, ForgeConfig.pollSeconds(config));
        assertEquals(1200, ForgeConfig.timeoutMs(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"opne", "OPEN_", "openish", "true", "1", "yes", "allow", "", " ",
            "closed", "CLOSED"})
    @DisplayName("only the exact word 'open' opens this gate")
    void failModeIsClosedUnlessExactlyOpen(String written) {
        Config config = load(MINIMAL + "\n[gate]\nfailmode = \"" + written + "\"\n");

        assertEquals(DecisionCache.FailMode.CLOSED, ForgeConfig.failMode(config),
                () -> "'" + written + "' opened the gate. A typo must never be the thing that"
                        + " admits everybody");
    }

    @ParameterizedTest
    // The last entry is doubled on purpose: the TOML source must contain the
    // two-character escapes \t and \n for the parser to decode, where a literal
    // tab and newline inside a basic string are a parse error and would test
    // the parser rather than the fail mode.
    @ValueSource(strings = {"open", "OPEN", " Open ", "\\topen\\n"})
    @DisplayName("and it does open for that word, however it is spaced or cased")
    void failModeOpensForTheWord(String written) {
        // The positive control. Without it, a fail mode hard-wired to CLOSED
        // would pass every case above and this file would assert nothing.
        Config config = load(MINIMAL + "\n[gate]\nfailmode = \"" + written + "\"\n");

        assertEquals(DecisionCache.FailMode.OPEN, ForgeConfig.failMode(config));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3601, 86400})
    @DisplayName("a poll interval outside the band is refused, and says why")
    void pollIntervalIsBounded(int seconds) {
        List<String> problems = ForgeConfig.validate(
                load(MINIMAL + "\n[events]\npollseconds = " + seconds + "\n"));

        assertTrue(problems.stream().anyMatch(p -> p.contains("events.pollseconds")),
                () -> "a poll interval of " + seconds + " was accepted: " + problems);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3600})
    @DisplayName("the ends of the poll band are inside it")
    void pollBoundsThemselvesAreAllowed(int seconds) {
        List<String> problems = ForgeConfig.validate(
                load(MINIMAL + "\n[events]\npollseconds = " + seconds + "\n"));

        assertTrue(problems.isEmpty(), problems::toString);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 249, 60_001})
    @DisplayName("the forge timeout is bounded at both ends")
    void timeoutIsBoundedAtBothEnds(int ms) {
        List<String> problems =
                ForgeConfig.validate(load(conf("", "timeoutms = " + ms + "\n", "")));

        assertTrue(problems.stream().anyMatch(p -> p.contains("host.timeoutms")),
                () -> "a timeout of " + ms + "ms was accepted: " + problems);
    }

    @ParameterizedTest
    @ValueSource(ints = {250, 60_000})
    @DisplayName("the ends of the timeout band are inside it")
    void timeoutBoundsThemselvesAreAllowed(int ms) {
        List<String> problems =
                ForgeConfig.validate(load(conf("", "timeoutms = " + ms + "\n", "")));

        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a blank value is refused, because it reads as configured")
    void blankIsRefused() {
        Config config = ConfigLoader.parse("""
                [core]
                url = ""

                [host]
                url = "  "

                [platform]
                kind = ""

                [gate]
                name = " "
                """, "test", ForgeConfig.SCHEMA, Map.of());

        List<String> problems = ForgeConfig.validate(config);

        for (String path : List.of("core.url", "host.url", "platform.kind", "gate.name")) {
            assertTrue(problems.stream().anyMatch(p -> p.startsWith(path)),
                    () -> "a blank " + path + " was accepted: " + problems);
        }
    }

    @Test
    @DisplayName("problems are returned together, not one at a time")
    void problemsAccumulate() {
        // An operator fixing configuration one error per run is an operator who
        // restarts four times to learn four things.
        List<String> problems = ForgeConfig.validate(load(conf("", "timeoutms = 1\n", """

                [events]
                pollseconds = 0
                """)));

        assertEquals(2, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("the environment overrides the file, which is how secrets stay out of it")
    void theEnvironmentOverridesTheFile() {
        // Duplicated deliberately rather than read from ConfigKey: a test that
        // derived the variable name from the thing under test would agree with a
        // wrong derivation.
        assertEquals("SOULBIND_CORE_CREDENTIAL", ForgeConfig.CREDENTIAL.envName());

        Config config = ConfigLoader.parse(
                conf("credential = \"from-the-file\"\n", "", ""),
                "test", ForgeConfig.SCHEMA,
                Map.of("SOULBIND_CORE_CREDENTIAL", "from-the-environment"));

        assertEquals("from-the-environment", config.getString(ForgeConfig.CREDENTIAL));
    }

    @Test
    @DisplayName("a credential is passed through exactly as written")
    void theCredentialIsNotAltered() {
        // An opaque token. Trimming one is a silent corruption that presents as
        // `unknown-credential` from core, which reads as the wrong credential
        // rather than a mangled one.
        String odd = "  tok en-with spaces  ";
        Config config = ConfigLoader.parse(MINIMAL, "test", ForgeConfig.SCHEMA,
                Map.of("SOULBIND_CORE_CREDENTIAL", odd));

        assertEquals(odd, config.getString(ForgeConfig.CREDENTIAL));
    }

    @Test
    @DisplayName("an unknown key is refused rather than ignored")
    void unknownKeyIsRefused() {
        // A misspelled key that loads silently is a setting the operator
        // believes is in force and is not.
        assertThrows(ConfigException.class,
                () -> load(MINIMAL + "\n[gate]\nfailmoed = \"open\"\n"));
    }
}
