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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scripted surface itself, which is <b>shipped code</b>.
 *
 * <p>It is tempting to call this a test double and leave it alone. It is not:
 * {@code scripted-driver} is a launcher in the distribution, and the full-stack
 * tier runs it out of process as a real connector — `journeys` and the `up`
 * stage both drive a live core through it. Its behaviour is load-bearing for
 * every claim those stages make.
 *
 * <p>Which is why the mutants that survived here matter. A {@code clear()} that
 * stopped clearing, or a {@code registerCommands} that stopped recording, would
 * leave a harness stage asserting against state from a previous scenario — and
 * the stage would pass. Nothing in this module could see that, because the only
 * thing exercising these paths was a tier PIT cannot observe.
 */
class ScriptedSurfaceTest {

    private static final ChatSurface.Invoker WHO =
            new ChatSurface.Invoker("acct-1", "Alex", false);

    private static ChatSurface.Invocation invocation() {
        return new ChatSurface.Invocation("link", List.of(), WHO);
    }

    @Test
    @DisplayName("clearReplies() forgets the replies and keeps everything else")
    void clearRepliesForgetsOnlyReplies() {
        // Both halves asserted, because both are load-bearing. The replies must
        // go, or one scenario reads the previous one's output. The registered
        // command list must NOT, because the caller that drives every command in
        // turn is iterating that list while calling this between commands.
        //
        // This was called clear(), which reads as "reset", and nothing said
        // which it meant. Mutation reached it -- the call could be removed
        // entirely and no test noticed.
        ScriptedSurface surface = new ScriptedSurface();
        surface.registerCommands(List.of("link"));
        surface.grantRole("acct-1", "linked");
        surface.reply(invocation(), "something", true);
        assertFalse(surface.sent().isEmpty());

        surface.clearReplies();

        assertTrue(surface.sent().isEmpty(),
                "replies from a previous scenario survived, so the next one's assertions read"
                        + " stale output");
        assertEquals(List.of("link"), surface.registeredCommands(),
                "the registered command list was emptied; the caller iterating it while"
                        + " calling this would stop after the first command");
        assertTrue(surface.hasRole("acct-1", "linked"),
                "granted roles were forgotten, so a scenario that grants and then checks"
                        + " across a clear would silently see nothing");
    }

    @Test
    @DisplayName("registerCommands records what it was given")
    void registerCommandsRecords() {
        // The `registersItsCommands` test asserts the connector registers the
        // right set; it can only do that if the surface remembers them. A
        // surface that recorded nothing would make that assertion vacuous in
        // the direction nobody checks.
        ScriptedSurface surface = new ScriptedSurface();
        surface.registerCommands(List.of("link", "whoami"));

        assertEquals(List.of("link", "whoami"), surface.registeredCommands());
    }

    @Test
    @DisplayName("registering again replaces the set rather than appending to it")
    void registerCommandsReplaces() {
        // "Here is the set", not "add these". A connector started twice against
        // one surface would otherwise report every command twice, and the test
        // that compares the registered list to ChatConnector.COMMANDS would fail
        // for a reason that has nothing to do with what it is checking.
        ScriptedSurface surface = new ScriptedSurface();
        surface.registerCommands(List.of("link", "whoami"));
        surface.registerCommands(List.of("link"));

        assertEquals(List.of("link"), surface.registeredCommands(),
                "the second registration was appended to the first");
    }

    @Test
    @DisplayName("membersWithRole answers only the accounts that hold it")
    void membersWithRoleIsSelective() {
        // Reconciliation after a rule change walks this list and asks core
        // about each. A surface that returned everybody would have the
        // connector interrogating -- and potentially stripping roles from --
        // accounts that never had one.
        ScriptedSurface surface = new ScriptedSurface();
        surface.preexistingRole("acct-1", "linked");
        surface.preexistingRole("acct-2", "other");

        assertEquals(List.of("acct-1"), surface.membersWithRole("linked"));
        assertEquals(List.of("acct-2"), surface.membersWithRole("other"));
        assertTrue(surface.membersWithRole("nobody-has-this").isEmpty());
    }

    @Test
    @DisplayName("the scripting methods return the surface, so they can be chained")
    void scriptingMethodsChain() {
        // Every caller writes `new ScriptedSurface().preexistingRole(..)
        // .makeRoleUnavailable(..)`. Returning null instead of this would be a
        // NullPointerException at every one of those call sites -- which is
        // loud, but the mutants survived, meaning no test chains them and the
        // contract is unasserted.
        ScriptedSurface surface = new ScriptedSurface();

        assertSame(surface, surface.makeRoleUnavailable("linked"));
        assertSame(surface, surface.makeRoleAvailable("linked"));
        assertSame(surface, surface.makeRoleThrow("linked"));
        assertSame(surface, surface.stopThrowing("linked"));
        assertSame(surface, surface.preexistingRole("acct-1", "linked"));
    }

    @Test
    @DisplayName("a role made unavailable is refused, and made available again is not")
    void availabilityIsHonoured() {
        ScriptedSurface surface = new ScriptedSurface();

        surface.makeRoleUnavailable("linked");
        assertFalse(surface.grantRole("acct-1", "linked"),
                "the surface granted a role it was told the platform would refuse");

        surface.makeRoleAvailable("linked");
        assertTrue(surface.grantRole("acct-1", "linked"));
        assertTrue(surface.hasRole("acct-1", "linked"));
    }
}
