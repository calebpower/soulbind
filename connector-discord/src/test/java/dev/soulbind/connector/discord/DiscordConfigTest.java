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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigException;
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
    @DisplayName("a binding missing a required field is refused by the loader")
    void bindingMissingAField() {
        // The paired effector.role/effector.gate check this replaces asserted
        // the same thing a weaker way: that neither half is useful alone. Now
        // they are fields of one element, so the LOADER enforces it and the
        // message names the element.
        ConfigException e = assertThrows(ConfigException.class, () -> load(MINIMAL + """
                [[effector.roles]]
                role = "GameLinked"
                """));
        assertTrue(e.getMessage().contains("effector.roles[0].gate"), e.getMessage());
    }

    @Test
    @DisplayName("several bindings are read in order, with their modes")
    void severalBindings() {
        Config config = load(MINIMAL + """
                [[effector.roles]]
                gate = "chat.gamelinked"
                role = "GameLinked"

                [[effector.roles]]
                gate = "chat.forumslinked"
                role = "ForumsLinked"

                [[effector.roles]]
                gate = "activity.meeper.grant"
                role = "Meeper"
                mode = "grant"

                [[effector.roles]]
                gate = "activity.meeper.keep"
                role = "Meeper"
                mode = "revoke"
                """);

        assertTrue(DiscordConfig.validate(config).isEmpty(),
                () -> DiscordConfig.validate(config).toString());

        List<RoleBinding> bindings = DiscordConfig.bindings(config);
        assertEquals(4, bindings.size());
        assertEquals("chat.gamelinked", bindings.get(0).gate());
        assertEquals(RoleBinding.Mode.BOTH, bindings.get(0).mode(),
                "an unset mode is both, so a single-threshold role needs no ceremony");
        assertEquals(RoleBinding.Mode.GRANT, bindings.get(2).mode());
        assertEquals(RoleBinding.Mode.REVOKE, bindings.get(3).mode());
    }

    @Test
    @DisplayName("no bindings at all is fine -- it is the inert posture")
    void noBindingsIsFine() {
        Config config = load(MINIMAL);
        assertTrue(DiscordConfig.validate(config).isEmpty());
        assertEquals(List.of(), DiscordConfig.bindings(config));
    }

    @Test
    @DisplayName("a role that can be granted but never removed is refused")
    void grantWithoutRevoke() {
        // Half a hysteresis pair: the grant gate configured, the keep gate
        // forgotten. The role would go on and never come off, diverging from
        // core's answer permanently -- which is the divergence the met/lost
        // pair exists to prevent.
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "activity.meeper.grant"
                role = "Meeper"
                mode = "grant"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("never come off"), problems::toString);
        assertTrue(problems.get(0).contains("Meeper"), problems::toString);
    }

    @Test
    @DisplayName("revoke-only is allowed, because it removes rather than grants")
    void revokeWithoutGrantIsFine() {
        // The asymmetry is deliberate and worth pinning: a binding that can only
        // TAKE a role away cannot leave anybody holding something they should
        // not, so it needs no partner. Asserting only the grant direction would
        // let a mutant that refused both survive.
        assertTrue(DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "activity.meeper.keep"
                role = "Meeper"
                mode = "revoke"
                """)).isEmpty());
    }

    @Test
    @DisplayName("an unrecognised mode is named, not silently treated as both")
    void badMode() {
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "g"
                role = "R"
                mode = "sometimes"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("sometimes"), problems::toString);
    }

    @Test
    @DisplayName("a blank gate or role is refused, because it looks configured")
    void blankFields() {
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "  "
                role = "R"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("must not be blank"), problems::toString);
    }

    @Test
    @DisplayName("the same binding twice is refused")
    void duplicateBinding() {
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "chat.gamelinked"
                role = "GameLinked"

                [[effector.roles]]
                gate = "chat.gamelinked"
                role = "GameLinked"
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("duplicate"), problems::toString);
    }

    @Test
    @DisplayName("two roles on one gate is not a duplicate")
    void twoRolesOneGate() {
        assertTrue(DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "chat.gamelinked"
                role = "GameLinked"

                [[effector.roles]]
                gate = "chat.gamelinked"
                role = "Verified"
                """)).isEmpty(),
                "one requirement may legitimately carry more than one role");
    }

    @Test
    @DisplayName("problems are returned together, not one at a time")
    void problemsAccumulate() {
        // An operator fixing a configuration one refusal per restart is an
        // operator restarting four times. Both faults, one pass.
        List<String> problems = DiscordConfig.validate(load(MINIMAL + """
                [[effector.roles]]
                gate = "g"
                role = "R"
                mode = "sideways"

                [events]
                pollseconds = 0
                """));

        assertEquals(2, problems.size(), problems::toString);
        assertFalse(problems.get(0).equals(problems.get(1)), problems::toString);
    }
}
