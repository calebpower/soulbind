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

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code /link} says to a player.
 *
 * <p>The messages are the product here. Somebody mid-link reads one and decides
 * what to do next, and a message that says "that did not work" when the real
 * answer is "you typed your own code" sends them to ask for a new one and fail
 * again. They are also the part hardest to check by hand, which is why they are
 * asserted rather than eyeballed.
 */
class LinkCommandLogicTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final UUID PLAYER = UUID.randomUUID();

    private LinkCommandLogic logic(InMemoryTransport transport) {
        return new LinkCommandLogic(
                new SoulbindClient(transport, "cred", CLOCK, new DecisionCache()), "game");
    }

    private static String refusal(String message) {
        return "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"invalid-request\","
                + "\"message\":\"" + message + "\"}}";
    }

    @Test
    @DisplayName("/link returns a code and says when it expires")
    void issue() {
        long expires = Instant.now().getEpochSecond() + 600;
        LinkCommandLogic.Reply reply = logic(InMemoryTransport.always(
                "{\"schema\":1,\"ok\":true,\"payload\":{\"code\":\"BCDFGHJK\","
                        + "\"expiresAtEpochSeconds\":" + expires + "}}"))
                .issue(PLAYER, "Alex");

        assertTrue(reply.success());
        assertTrue(reply.message().contains("BCDFGHJK"), reply.message());
        assertTrue(reply.message().contains("minutes"), reply.message());
    }

    @Test
    @DisplayName("a code about to expire still reports at least one minute")
    void neverReportsZeroMinutes() {
        // "Expires in 0 minutes" tells somebody not to bother, when they have
        // most of a minute.
        long expires = Instant.now().getEpochSecond() + 30;
        LinkCommandLogic.Reply reply = logic(InMemoryTransport.always(
                "{\"schema\":1,\"ok\":true,\"payload\":{\"code\":\"BCDFGHJK\","
                        + "\"expiresAtEpochSeconds\":" + expires + "}}"))
                .issue(PLAYER, "Alex");

        assertTrue(reply.message().contains("1 minutes"), reply.message());
        assertFalse(reply.message().contains("0 minutes"), reply.message());
    }

    @Test
    @DisplayName("/link CODE reports how many accounts are now connected")
    void redeem() {
        LinkCommandLogic.Reply reply = logic(InMemoryTransport.always(
                "{\"schema\":1,\"ok\":true,\"payload\":{\"subjectId\":\"s1\","
                        + "\"identities\":[{},{}]}}"))
                .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertTrue(reply.success());
        assertTrue(reply.message().contains("Linked"), reply.message());
        // "1 other account." with the stop: "1 other account" is a PREFIX of
        // "1 other accounts", so the assertion that stood here passed whichever
        // branch ran.
        assertTrue(reply.message().contains("1 other account."), reply.message());
    }

    @Test
    @DisplayName("the plural agrees with the count, on both sides of the boundary")
    void pluralAgreesWithTheCount() {
        LinkCommandLogic.Reply three = logic(InMemoryTransport.always(
                "{\"schema\":1,\"ok\":true,\"payload\":{\"subjectId\":\"s1\","
                        + "\"identities\":[{},{},{}]}}"))
                .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertTrue(three.message().contains("2 other accounts."), three.message());
    }

    @Test
    @DisplayName("a refusal with no message at all still says something useful")
    void refusalWithNoMessage() {
        // Core can refuse with an empty message -- an older build, a proxy that
        // ate the body. Passing that through leaves the player staring at a
        // blank reply and no idea what to do next.
        LinkCommandLogic.Reply reply = logic(InMemoryTransport.always(refusal("")))
                .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertFalse(reply.success());
        assertTrue(reply.message().contains("Ask for a new one"),
                "an empty refusal produced an empty reply: '" + reply.message() + "'");
    }

    @Test
    @DisplayName("a refusal that BEGINS with the separator keeps its whole text")
    void refusalBeginningWithASeparator() {
        // The `colon > 0` boundary rather than `>= 0`. A colon at position zero
        // leaves no reason in front of it, so the whole message is the reason --
        // and under `>= 0` the player is shown a fragment of it instead.
        LinkCommandLogic.Reply reply =
                logic(InMemoryTransport.always(refusal(": no reason given")))
                        .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertTrue(reply.message().contains(": no reason given"),
                "the message was cut at a separator that had nothing before it: "
                        + reply.message());
    }

    @Test
    @DisplayName("an unknown refusal with no separator is shown whole")
    void unknownRefusalWithoutASeparator() {
        LinkCommandLogic.Reply reply =
                logic(InMemoryTransport.always(refusal("something odd happened")))
                        .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertEquals("something odd happened", reply.message(),
                "core's own words were altered on the way to the player");
    }

    @Test
    @DisplayName("/link with no code shows usage rather than calling core")
    void missingCode() {
        InMemoryTransport transport = InMemoryTransport.always(refusal("unknown-code: no"));
        LinkCommandLogic.Reply reply = logic(transport).redeem(PLAYER, "Alex", "  ");

        assertFalse(reply.success());
        assertTrue(reply.message().contains("Usage"), reply.message());
        assertEquals(0, transport.sendCount(), "an empty command should not reach core");
    }

    // --- the refusals a player can actually hit ---------------------------------

    @Test
    @DisplayName("each refusal produces its OWN advice, not a generic failure")
    void refusalsAreDistinct() {
        // Flattening these to "that did not work" sends somebody to ask for a
        // new code when their real problem is that they typed their own.
        record Case(String wire, String mustContain) {}

        for (Case c : new Case[] {
            new Case("unknown-code: no such code", "not one we issued"),
            new Case("expired: that code has expired", "expired"),
            new Case("already-redeemed: used", "already been used"),
            new Case("same-account: same", "OTHER platform"),
            new Case("already-linked: both", "already linked"),
        }) {
            LinkCommandLogic.Reply reply = logic(InMemoryTransport.always(refusal(c.wire())))
                    .redeem(PLAYER, "Alex", "BCDFGHJK");

            assertFalse(reply.success());
            assertTrue(
                    reply.message().toLowerCase(Locale.ROOT)
                            .contains(c.mustContain().toLowerCase(Locale.ROOT)),
                    () -> "'" + c.wire() + "' produced: " + reply.message());
        }
    }

    @Test
    @DisplayName("a refusal never shows the player its machine-readable prefix")
    void refusalPrefixIsStripped() {
        // A player does not want to read "same-account:".
        LinkCommandLogic.Reply reply =
                logic(InMemoryTransport.always(refusal("same-account: was issued for this")))
                        .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertFalse(reply.message().contains("same-account:"), reply.message());
    }

    @Test
    @DisplayName("an unknown refusal falls back to core's own words rather than swallowing them")
    void unknownRefusalPassesThrough() {
        // A core newer than this connector may refuse for a reason it has never
        // heard of. Replacing that with a generic message would hide the only
        // information anybody has.
        LinkCommandLogic.Reply reply = logic(
                InMemoryTransport.always(refusal("some-future-reason: the specifics")))
                .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertTrue(reply.message().contains("the specifics"), reply.message());
    }

    // --- outages ----------------------------------------------------------------

    @Test
    @DisplayName("an outage blames the system, on both halves of the command")
    void outageBlamesTheSystem() {
        InMemoryTransport down = InMemoryTransport.always("{}").goDown();

        LinkCommandLogic.Reply issued = logic(down).issue(PLAYER, "Alex");
        assertFalse(issued.success());
        assertTrue(issued.message().contains("our side, not yours"), issued.message());

        LinkCommandLogic.Reply redeemed = logic(down).redeem(PLAYER, "Alex", "BCDFGHJK");
        assertFalse(redeemed.success());
        assertTrue(redeemed.message().contains("our side, not yours"), redeemed.message());
    }

    @Test
    @DisplayName("a player can start OR finish here")
    void bothHalvesExist() {
        // The protocol is symmetric by construction, and a connector doing only
        // one half would quietly make the other platform the root of identity.
        InMemoryTransport transport = new InMemoryTransport(request ->
                request.contains("code.issue")
                        ? "{\"schema\":1,\"ok\":true,\"payload\":{\"code\":\"BCDFGHJK\","
                                + "\"expiresAtEpochSeconds\":"
                                + (Instant.now().getEpochSecond() + 600) + "}}"
                        : "{\"schema\":1,\"ok\":true,\"payload\":{\"subjectId\":\"s1\","
                                + "\"identities\":[{},{}]}}");

        LinkCommandLogic logic = logic(transport);
        assertTrue(logic.issue(PLAYER, "Alex").success());
        assertTrue(logic.redeem(PLAYER, "Alex", "BCDFGHJK").success());
    }

    @Test
    @DisplayName("an unknown refusal is shown EXACTLY the words after the separator")
    void unknownRefusalIsCutInTheRightPlace() {
        // `substring(colon + 2)`, off by two either way, still contains the
        // sentence -- it just carries two characters of the machine-readable
        // prefix in front of it, or eats two of the message. Only equality can
        // see the difference.
        LinkCommandLogic.Reply reply =
                logic(InMemoryTransport.always(refusal("some-future-reason: the specifics")))
                        .redeem(PLAYER, "Alex", "BCDFGHJK");

        assertEquals("the specifics", reply.message(),
                "the message was cut in the wrong place, so the player is shown a fragment or"
                        + " a fragment of the code");
    }
}
