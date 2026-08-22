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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.sdk.DecisionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The proxy plugin's configuration, none of which was executed by anything.
 *
 * <p>All twelve mutants came back "no coverage" — the defaults, the timeout
 * band, and the check that catches a group nothing will ever grant. The last of
 * those is worth the whole file on its own: it is the warning that would have
 * told somebody the effector was inert, and DECISIONS 10.23 records that the
 * effector WAS inert for an entire phase.
 */
class VelocityConfigTest {

    @TempDir
    Path tempDir;

    private static final String MINIMAL = """
            [core]
            url = "http://127.0.0.1:7000"
            """;

    private static Config load(String toml) {
        return ConfigLoader.parse(toml, "test", VelocityConfig.SCHEMA, Map.of());
    }

    @Test
    @DisplayName("the defaults are the documented ones")
    void defaults() {
        Config config = load(MINIMAL);

        assertEquals("game", VelocityConfig.platformKind(config));
        assertEquals(Duration.ofMillis(1500), VelocityConfig.decisionTimeout(config));
        assertEquals(VelocityConfig.DEFAULT_KICK_MESSAGE, VelocityConfig.kickMessage(config));
        assertEquals(DecisionCache.FailMode.CLOSED, VelocityConfig.failMode(config),
                "an unset fail mode did not default to CLOSED. A proxy that fails OPEN by"
                        + " accident admits everybody the moment core is unreachable, which is"
                        + " the gate not existing at exactly the moment it matters");
    }

    @Test
    @DisplayName("what is configured wins over the default")
    void overridesApply() {
        Config config = load(MINIMAL + """
                [gate]
                join = "game.join"
                kickmessage = "go away"
                failmode = "open"
                decisiontimeoutmillis = 2000

                [platform]
                kind = "proxy"
                """);

        assertEquals("game.join", config.findString(VelocityConfig.JOIN_GATE).orElseThrow());
        assertEquals("go away", VelocityConfig.kickMessage(config));
        assertEquals(DecisionCache.FailMode.OPEN, VelocityConfig.failMode(config));
        assertEquals(Duration.ofMillis(2000), VelocityConfig.decisionTimeout(config));
        assertEquals("proxy", VelocityConfig.platformKind(config));
    }

    @Test
    @DisplayName("a minimal configuration has nothing to complain about")
    void minimalValidates() {
        assertTrue(VelocityConfig.validate(load(MINIMAL)).isEmpty(),
                VelocityConfig.validate(load(MINIMAL))::toString);
    }

    @ParameterizedTest
    @ValueSource(ints = {49, 0, -1, 10_001, 60_000})
    @DisplayName("a decision timeout outside the band is refused, and says why on both sides")
    void timeoutBandIsEnforced(int millis) {
        List<String> problems = VelocityConfig.validate(load(MINIMAL + """
                [gate]
                decisiontimeoutmillis = %d
                """.formatted(millis)));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains(Integer.toString(millis)),
                "the message does not repeat the value that was rejected: " + problems);
        assertTrue(problems.get(0).contains("fail mode") || problems.get(0).contains("accepting"),
                "the message says the number is wrong without saying what goes wrong: "
                        + problems);
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 1500, 10_000})
    @DisplayName("the ends of the band are inside it")
    void bandEndsAreLegal(int millis) {
        // `< 50` and `> 10000`, not `<=` and `>=`. Moving either edge inward
        // refuses a value the message itself calls legal.
        assertTrue(
                VelocityConfig.validate(load(MINIMAL + """
                        [gate]
                        decisiontimeoutmillis = %d
                        """.formatted(millis))).isEmpty(),
                "a legal decision timeout was refused: " + millis);
    }

    @Test
    @DisplayName("a group with no gate is reported, because nothing would ever grant it")
    void groupWithoutAGate() {
        // The warning that would have caught DECISIONS 10.23 if anyone had run
        // it: an effector configured to grant a group on an event nobody is
        // watching for. It reads as working and does nothing.
        List<String> problems = VelocityConfig.validate(load(MINIMAL + """
                [effector]
                group = "soulbind-linked"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("effector.group"), problems::toString);
        assertTrue(problems.get(0).contains("gate.join"),
                "the complaint does not name the setting that would fix it: " + problems);
    }

    @Test
    @DisplayName("a gate with no group is fine, because a gate alone is a whole feature")
    void gateWithoutAGroupIsFine() {
        // Deliberately NOT symmetric with the check above, and the asymmetry is
        // the point: enforcing a join gate without granting any group is an
        // ordinary deployment. Warning about it would train an operator to
        // ignore this check.
        assertTrue(
                VelocityConfig.validate(load(MINIMAL + """
                        [gate]
                        join = "game.join"
                        """)).isEmpty(),
                "a gate configured without a group was reported as a problem");
    }

    @Test
    @DisplayName("problems are returned together, not one restart at a time")
    void problemsAccumulate() {
        List<String> problems = VelocityConfig.validate(load(MINIMAL + """
                [gate]
                decisiontimeoutmillis = 5

                [effector]
                group = "soulbind-linked"
                """));

        assertEquals(2, problems.size(), problems::toString);
        assertFalse(problems.get(0).equals(problems.get(1)), problems::toString);
    }

    @Test
    @DisplayName("load() reads a real file through this connector's schema")
    void loadReadsAFile() throws Exception {
        Path file = tempDir.resolve("soulbind.toml");
        Files.writeString(file, MINIMAL + """
                [platform]
                kind = "proxy"
                """);

        assertEquals("proxy", VelocityConfig.platformKind(VelocityConfig.load(file)));
    }
}
