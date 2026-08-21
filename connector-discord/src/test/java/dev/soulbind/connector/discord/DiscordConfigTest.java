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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigLoader;
import dev.soulbind.sdk.DecisionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * This connector's configuration, and the checks its schema cannot express.
 *
 * <p><b>Nothing executed any of this.</b> Every mutant in {@code DiscordConfig}
 * came back "no coverage" — its defaults, its fail-mode reading and both of its
 * validations were reachable only by starting the connector for real. A default
 * that silently changes is the kind of defect an operator discovers in
 * production, from behaviour, with nothing to grep for.
 */
class DiscordConfigTest {

    private static final String MINIMAL = """
            [core]
            url = "http://127.0.0.1:7000"
            """;

    private static Config load(String toml) {
        return ConfigLoader.parse(toml, "test", DiscordConfig.SCHEMA, Map.of());
    }

    @Test
    @DisplayName("load() reads a real file through this connector's schema")
    void loadReadsAFile(@TempDir Path dir) throws Exception {
        // parse() is what every other test here uses, which left the shipped
        // entry point -- the one Main actually calls -- executed by nothing.
        Path file = dir.resolve("soulbind-discord.toml");
        Files.writeString(file, MINIMAL + """
                [platform]
                kind = "discord"
                """);

        Config config = DiscordConfig.load(file);

        assertEquals("discord", DiscordConfig.platformKind(config));
    }

    @Test
    @DisplayName("a minimal configuration loads and validates")
    void minimalLoads() {
        Config config = load(MINIMAL);
        assertTrue(DiscordConfig.validate(config).isEmpty(),
                DiscordConfig.validate(config)::toString);
    }

    @Test
    @DisplayName("the defaults are the documented ones, asserted rather than assumed")
    void defaults() {
        Config config = load(MINIMAL);

        // Each of these is a value somebody would only discover by running the
        // connector and watching it behave. Stated here so a change to one is a
        // change somebody made on purpose.
        assertEquals("chat", DiscordConfig.platformKind(config));
        assertEquals(15, DiscordConfig.pollSeconds(config));
        assertEquals(DecisionCache.FailMode.CLOSED, DiscordConfig.failMode(config),
                "an unset fail mode did not default to CLOSED; a connector that fails OPEN by"
                        + " accident admits everybody the moment core is unreachable");
    }

    @Test
    @DisplayName("what is configured wins over the default")
    void overridesApply() {
        Config config = load(MINIMAL + """
                [platform]
                kind = "discord"

                [events]
                pollseconds = 30

                [gate]
                failmode = "open"
                """);

        assertEquals("discord", DiscordConfig.platformKind(config));
        assertEquals(30, DiscordConfig.pollSeconds(config));
        assertEquals(DecisionCache.FailMode.OPEN, DiscordConfig.failMode(config));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3601, 86400})
    @DisplayName("a poll interval outside the band is refused, and says why")
    void pollIntervalIsBounded(int seconds) {
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [events]
                pollseconds = %d
                """.formatted(seconds)));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("events.pollseconds"), problems::toString);
        assertTrue(problems.get(0).contains(Integer.toString(seconds)),
                "the message does not repeat the value that was rejected, so an operator has to"
                        + " go and look it up: " + problems);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 3600})
    @DisplayName("the ends of the band are inside it")
    void theBoundsThemselvesAreAllowed(int seconds) {
        // The boundary in both directions. `< 1` and `> 3600` rather than `<= 1`
        // and `>= 3600`, so one second and one hour are legal -- and a mutant
        // moving either edge inward would otherwise go unnoticed.
        assertTrue(
                DiscordConfig.validate(load(MINIMAL + """
                        [events]
                        pollseconds = %d
                        """.formatted(seconds))).isEmpty(),
                "a legal poll interval was refused: " + seconds);
    }

    @Test
    @DisplayName("a role with no gate is refused, because nothing would ever grant it")
    void roleWithoutGate() {
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [effector]
                role = "linked"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("effector.gate"), problems::toString);
    }

    @Test
    @DisplayName("a gate with no role is refused too, and it is the same mistake")
    void gateWithoutRole() {
        // Both directions, because the check is an inequality between two
        // presences: a mutant that compared them the other way round, or always
        // one way, would survive a test that only ever set the role.
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [effector]
                gate = "chat.member"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("effector.role"), problems::toString);
    }

    @Test
    @DisplayName("both together are fine, and neither is fine")
    void bothOrNeither() {
        assertTrue(DiscordConfig.validate(load(MINIMAL + """
                [effector]
                role = "linked"
                gate = "chat.member"
                """)).isEmpty());

        assertTrue(DiscordConfig.validate(load(MINIMAL)).isEmpty());
    }

    @Test
    @DisplayName("problems are returned together, not one at a time")
    void problemsAccumulate() {
        // An operator fixing a configuration one refusal per restart is an
        // operator restarting four times. Both faults, one pass.
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [effector]
                role = "linked"

                [events]
                pollseconds = 0
                """));

        assertEquals(2, problems.size(), problems::toString);
        assertFalse(problems.get(0).equals(problems.get(1)), problems::toString);
    }
}
