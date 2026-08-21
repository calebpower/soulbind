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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Waiting for a configured guild to become visible.
 *
 * <p>Found live, not by reading. The connector's first run against a real
 * Discord registered its commands <em>globally</em> while {@code
 * platform.guild} named a server — the bot had not been invited to it yet, so
 * the guild was not visible, and the fallback was silent apart from one log
 * line. It left three global commands on the application: visible in every
 * server the bot is ever added to, up to an hour to propagate, and there until
 * somebody deletes them by hand.
 *
 * <p>The polling itself is what these cover. Whether the surrounding code then
 * refuses is asserted by the message it throws, which is not something a unit
 * test can reach without a JDA — so the decision was extracted to here, where
 * it can be.
 */
class GuildScopeTest {

    @Test
    @DisplayName("a guild that is already visible is not waited for")
    void alreadyVisible() {
        AtomicInteger pauses = new AtomicInteger();
        boolean found = JdaSurface.awaitGuild(() -> true, 20, pauses::incrementAndGet);

        assertTrue(found);
        assertEquals(0, pauses.get(),
                "the guild was visible on the first look and the connector slept anyway,"
                        + " which delays every start-up for nothing");
    }

    @Test
    @DisplayName("a guild that appears part way through is found, and waited for no longer")
    void appearsLater() {
        // The live case: JDA is connected, the guild arrives with a later
        // GUILD_CREATE. Three looks, then it is there.
        AtomicInteger looks = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();

        boolean found = JdaSurface.awaitGuild(
                () -> looks.incrementAndGet() > 3, 20, pauses::incrementAndGet);

        assertTrue(found, "the guild appeared within the budget and was not found");
        assertEquals(3, pauses.get(),
                "it should have paused exactly as many times as it looked and missed");
    }

    @Test
    @DisplayName("a guild that never appears gives up, having looked the stated number of times")
    void neverAppears() {
        // The typo case, and the not-invited case. Both must end here rather
        // than in a silent fallback to global registration.
        AtomicInteger looks = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();

        boolean found = JdaSurface.awaitGuild(
                () -> {
                    looks.incrementAndGet();
                    return false;
                }, 5, pauses::incrementAndGet);

        assertFalse(found, "a guild that is never visible was reported as found");
        assertEquals(5, looks.get(),
                "the budget is the number of LOOKS, and giving up early would refuse a"
                        + " start-up that was about to succeed");
        assertEquals(5, pauses.get());
    }

    @Test
    @DisplayName("a zero budget looks not at all, rather than looping forever")
    void zeroBudget() {
        AtomicInteger looks = new AtomicInteger();
        assertFalse(JdaSurface.awaitGuild(
                () -> {
                    looks.incrementAndGet();
                    return true;
                }, 0, () -> { }));
        assertEquals(0, looks.get());
    }

    @Test
    @DisplayName("a server that never appears REFUSES, rather than registering globally")
    void refusesRatherThanRegisteringGlobally() {
        // Global commands appear in every server the bot is ever added to, take
        // up to an hour to propagate, and stay until somebody deletes them. A
        // typo in the guild id would otherwise produce exactly that, silently.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> JdaSurface.requireGuildVisible(
                        "123456789", () -> false, 4, 500L, () -> { }));

        String message = thrown.getMessage();
        assertTrue(message.contains("123456789"),
                "the refusal does not repeat the id that was configured, which is the thing"
                        + " the operator has to go and check: " + message);
        assertTrue(message.contains("2s"),
                "the refusal does not say how long it waited, so an operator cannot tell a"
                        + " slow start from a wrong id: " + message);
        assertTrue(message.contains("platform.guild"),
                "the refusal does not name the setting to change: " + message);
    }

    @Test
    @DisplayName("a server that is visible passes without a word")
    void visibleServerPasses() {
        assertDoesNotThrow(() -> JdaSurface.requireGuildVisible(
                "123456789", () -> true, 4, 500L, () -> {
                    throw new AssertionError("waited for a server that was already there");
                }));
    }

    @Test
    @DisplayName("a reply with no pending interaction is logged, not thrown")
    void replyWithoutAPendingInteraction() {
        // The connector has already done its work by this point. A missing
        // callback is a bug here, not a reason to fail the operation that
        // succeeded -- and this path touches no library type at all, which is
        // why it can be exercised with no JDA in the room.
        List<String> logged = new ArrayList<>();
        JdaSurface surface = new JdaSurface(null, null, (message, cause) -> logged.add(message));

        surface.reply(
                new ChatSurface.Invocation(
                        "whoami", List.of(),
                        new ChatSurface.Invoker("acct-1", "Alex", false)),
                "anything", true);

        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("whoami"),
                "the line does not say which command had nowhere to reply: " + logged);
    }

    @Test
    @DisplayName("with no server configured, listing holders asks the platform nothing")
    void noGuildMeansNoHolders() {
        // guild() short-circuits on a blank id before touching the library, so
        // this runs with a null JDA. A deployment that has not set
        // platform.guild must not crash on reconciliation.
        JdaSurface surface = new JdaSurface(null, null, (message, cause) -> { });

        assertTrue(surface.membersWithRole("linked").isEmpty());
    }

    @Test
    @DisplayName("a BLANK guild id is as unset as an absent one")
    void blankGuildIdIsUnset() {
        // Both halves of `guildId == null || guildId.isBlank()`. A TOML file
        // with `guild = ""` is somebody who meant to leave it out, and treating
        // it as a server name would send every lookup after a guild called "".
        for (String blank : new String[] {"", "   "}) {
            List<String> logged = new ArrayList<>();
            JdaSurface surface = new JdaSurface(null, blank, (m, c) -> logged.add(m));

            assertTrue(surface.membersWithRole("linked").isEmpty());
            // And SILENTLY. A blank id that fell through to a real lookup would
            // fail somewhere inside the library and be reported as "could not
            // list holders", which describes the symptom and hides the cause.
            assertTrue(logged.isEmpty(),
                    "a blank guild id reached a real lookup: " + logged);
        }
    }

    @Test
    @DisplayName("no server configured is not an error, and says nothing")
    void noServerIsSilentForHolders() {
        // Reconciliation runs on a timer. A deployment that has not set
        // platform.guild would otherwise get a line every cycle for a condition
        // that is a choice, not a fault.
        List<String> logged = new ArrayList<>();
        JdaSurface surface = new JdaSurface(null, null, (m, c) -> logged.add(m));

        assertTrue(surface.membersWithRole("linked").isEmpty());
        assertTrue(logged.isEmpty(),
                "an unconfigured server produced a log line, which will repeat on every"
                        + " reconciliation for the life of the process: " + logged);
    }

    /** A platform that records which operation it was handed. */
    private static final class RecordingPlatform implements GuildRoles.Platform {
        private final List<String> calls = new ArrayList<>();
        private final boolean held;

        RecordingPlatform(boolean held) {
            this.held = held;
        }

        @Override
        public GuildRoles.Pair resolve(String platformId, String role) {
            return new GuildRoles.Pair(true, true, true, held);
        }

        @Override
        public void addRole(String platformId, String role) {
            calls.add("add");
        }

        @Override
        public void removeRole(String platformId, String role) {
            calls.add("remove");
        }

        @Override
        public List<String> holders(String role) {
            calls.add("holders");
            return List.of("acct-9");
        }
    }

    @Test
    @DisplayName("each role method routes to the operation of the same name")
    void roleMethodsRouteCorrectly() {
        // One line each, all four delegating. A grant wired to revoke would take
        // the role off everybody who earned it, and only a live Discord would
        // have said so.
        RecordingPlatform granting = new RecordingPlatform(false);
        assertTrue(new JdaSurface(null, "g", granting, (m, c) -> { })
                .grantRole("acct-1", "linked"));
        assertEquals(List.of("add"), granting.calls);

        RecordingPlatform revoking = new RecordingPlatform(true);
        assertTrue(new JdaSurface(null, "g", revoking, (m, c) -> { })
                .revokeRole("acct-1", "linked"));
        assertEquals(List.of("remove"), revoking.calls);

        RecordingPlatform reading = new RecordingPlatform(true);
        assertTrue(new JdaSurface(null, "g", reading, (m, c) -> { })
                .hasRole("acct-1", "linked"));
        assertTrue(reading.calls.isEmpty(), reading.calls::toString);

        RecordingPlatform listing = new RecordingPlatform(false);
        assertEquals(
                List.of("acct-9"),
                new JdaSurface(null, "g", listing, (m, c) -> { }).membersWithRole("linked"),
                "membersWithRole did not return what the platform reported");
    }

    /** A platform that has no server, so every role operation fails. */
    private static final class AbsentPlatform implements GuildRoles.Platform {
        @Override
        public GuildRoles.Pair resolve(String platformId, String role) {
            return new GuildRoles.Pair(false, false, false, false);
        }

        @Override
        public void addRole(String platformId, String role) { }

        @Override
        public void removeRole(String platformId, String role) { }

        @Override
        public List<String> holders(String role) {
            return List.of();
        }
    }

    @Test
    @DisplayName("a failed role operation is reported as failed, not swallowed into true")
    void roleMethodsReportFailure() {
        // The other half of routing. Each of these is `return roles.x(..)`, and
        // a version that returned true regardless would have the effector
        // acknowledge events it never applied -- the role never appears and the
        // event never comes back.
        JdaSurface surface = new JdaSurface(null, "g", new AbsentPlatform(), (m, c) -> { });

        assertFalse(surface.grantRole("acct-1", "linked"));
        assertFalse(surface.revokeRole("acct-1", "linked"));
        assertFalse(surface.hasRole("acct-1", "linked"));
    }
}
