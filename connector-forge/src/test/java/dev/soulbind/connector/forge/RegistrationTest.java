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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Admission to the forge, exercised above the transport seam and without a
 * forge.
 *
 * <p>The claim under test throughout is the one the whole architecture rests
 * on: <b>refused and unavailable are different answers.</b> Somebody who has
 * not linked a forum account must be told what to go and do; somebody refused
 * because core is unreachable must be told it is our fault. Collapsing them
 * turns "you may not" into "try again later" in one direction, and in the other
 * hands a retry loop a way past a genuine denial.
 *
 * <p>The second claim is about order: the gate is asked before an account
 * exists. Several tests below assert a call that must NOT have happened, which
 * is only observable because {@link ScriptedSurface} counts.
 */
class RegistrationTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final char Q = '"';
    private static final String KIND = "forge";
    private static final String GATE = "forge.register";

    private static final ForgeSurface.Account ACCOUNT =
            new ForgeSurface.Account("ada", "ada@example.invalid", "a-passphrase");

    private static String allow() {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"allow\","
                + "\"reason\":\"requirements-met\",\"detail\":\"ok\",\"ttlSeconds\":60,"
                + "\"missingKinds\":[]}}";
    }

    private static String deny() {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"deny\","
                + "\"reason\":\"missing-kinds\",\"detail\":\"link your forum account\","
                + "\"ttlSeconds\":60,\"missingKinds\":[\"forum\"]}}";
    }

    private static String redeemed() {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"subjectId\":\"s-1\",\"identities\":[]}}";
    }

    private static String refusal(String code) {
        return "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"no\"}}";
    }

    private static Registration registration(InMemoryTransport transport, ForgeSurface forge) {
        return registration(transport, forge, new DecisionCache());
    }

    private static Registration registration(
            InMemoryTransport transport, ForgeSurface forge, DecisionCache cache) {
        return new Registration(
                new SoulbindClient(transport, "a-credential", CLOCK, cache), forge, KIND, GATE);
    }

    // --- refused is not unavailable --------------------------------------------

    @Test
    @DisplayName("the gate refusing is a refusal, and says what is missing")
    void gateRefusalIsARefusal() {
        ScriptedSurface forge = new ScriptedSurface();

        Registration.Outcome outcome =
                registration(InMemoryTransport.always(deny()), forge).register(ACCOUNT);

        Registration.Outcome.Refused refused =
                assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        assertEquals(List.of("forum"), refused.missingKinds(),
                "a refusal that does not say which identity is missing leaves somebody with "
                        + "nothing to act on");
        assertEquals("link your forum account", refused.detail());
    }

    @Test
    @DisplayName("core being unreachable is NOT a refusal, and blames the system")
    void outageIsNotARefusal() {
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = InMemoryTransport.always(allow()).goDown();

        Registration.Outcome outcome = registration(transport, forge).register(ACCOUNT);

        Registration.Outcome.Unavailable unavailable =
                assertInstanceOf(Registration.Outcome.Unavailable.class, outcome);
        assertEquals(DecisionCache.FAIL_CLOSED_MESSAGE, unavailable.message(),
                "the fail-mode wording is shared across every connector on purpose: one "
                        + "person refused on the forum and another on the forge, in the same "
                        + "outage, must not be told different things");
        assertTrue(unavailable.message().toLowerCase().contains("our side"),
                () -> "a fail-mode denial must blame the system, not the person: "
                        + unavailable.message());
    }

    @Test
    @DisplayName("being refused permission to ask is not permission to proceed")
    void refusedTheAskCreatesNothing() {
        // A credential without enforcement-point. The SDK turns this into a
        // denial rather than an outage, and the account must not appear.
        ScriptedSurface forge = new ScriptedSurface();

        Registration.Outcome outcome = registration(
                InMemoryTransport.always(refusal("missing-capability")), forge).register(ACCOUNT);

        assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        assertEquals(0, forge.createCalls(),
                "an account was created even though core refused to answer the question");
    }

    // --- order -----------------------------------------------------------------

    @Test
    @DisplayName("a denied gate creates nothing at all")
    void denialCreatesNothing() {
        ScriptedSurface forge = new ScriptedSurface();

        registration(InMemoryTransport.always(deny()), forge).register(ACCOUNT);

        assertEquals(0, forge.createCalls(),
                "the gate must be asked before the account exists; creating first and deleting "
                        + "on refusal leaves the forge holding accounts whenever the delete is "
                        + "the half that fails");
        assertFalse(forge.exists("ada"));
    }

    @Test
    @DisplayName("an allowed gate creates the account, active")
    void allowedCreates() {
        ScriptedSurface forge = new ScriptedSurface();

        Registration.Outcome outcome =
                registration(InMemoryTransport.always(allow()), forge).register(ACCOUNT);

        assertEquals(new Registration.Outcome.Created("ada"), outcome);
        assertEquals(List.of("ada"), forge.created());
        assertTrue(forge.isActive("ada"));
    }

    // --- the forge's own state -------------------------------------------------

    @Test
    @DisplayName("a name already in use is refused, never taken over")
    void takenNameIsRefused() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        Registration.Outcome outcome =
                registration(InMemoryTransport.always(allow()), forge).register(ACCOUNT);

        Registration.Outcome.Refused refused =
                assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        // A denial is never wordless. Asserting only the TYPE let an empty
        // message through, and an empty message reaches somebody as a blank
        // page where a reason should be.
        assertFalse(refused.detail().isBlank(), "the refusal said nothing");
        assertTrue(refused.detail().contains("ada"),
                () -> "a refusal about a name that does not name it: " + refused.detail());
        assertEquals(0, forge.createCalls());
    }

    @Test
    @DisplayName("not knowing whether a name is free is not the same as free")
    void unknownPresenceCreatesNothing() {
        ScriptedSurface forge = new ScriptedSurface().goDown();

        Registration.Outcome outcome =
                registration(InMemoryTransport.always(allow()), forge).register(ACCOUNT);

        Registration.Outcome.Unavailable unavailable =
                assertInstanceOf(Registration.Outcome.Unavailable.class, outcome);
        assertTrue(unavailable.message().toLowerCase().contains("our side"), unavailable::message);
        assertEquals(0, forge.createCalls(),
                "an unreachable forge read as a free name, which is how an account lands on "
                        + "top of somebody else's");
    }

    @Test
    @DisplayName("a forge that fails the creation itself is an outage, not a refusal")
    void creationFailureIsAnOutage() {
        // Presence answers, creation does not -- a state ScriptedSurface cannot
        // express, and one a real host reaches the moment its token loses the
        // right to create while keeping the right to read.
        ForgeSurface halfBroken = new ForgeSurface() {
            @Override
            public Role role(String username) {
                return Role.ORDINARY;
            }

            @Override
            public Presence presence(String username) {
                return Presence.ABSENT;
            }

            @Override
            public Creation create(Account account) {
                return Creation.FAILED;
            }

            @Override
            public boolean setActive(String username, boolean active) {
                return false;
            }
        };

        Registration.Outcome outcome =
                registration(InMemoryTransport.always(allow()), halfBroken).register(ACCOUNT);

        Registration.Outcome.Unavailable unavailable =
                assertInstanceOf(Registration.Outcome.Unavailable.class, outcome);
        assertTrue(unavailable.message().toLowerCase().contains("our side"), unavailable::message);
    }

    // --- linking ---------------------------------------------------------------

    @Test
    @DisplayName("a code is normalised before it travels, with the shared implementation")
    void codeIsNormalisedLocally() {
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = InMemoryTransport.always(redeemed());

        Registration.Outcome outcome =
                registration(transport, forge).link(" bc23-dfg4 ", "ada");

        assertEquals(new Registration.Outcome.Linked("s-1"), outcome);
        assertTrue(transport.sent().get(0).contains("BC23DFG4"),
                () -> "core received something other than the canonical code: "
                        + transport.sent());
    }

    @Test
    @DisplayName("a code that cannot be a code never reaches core")
    void unnormalisableCodeIsRejectedLocally() {
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = InMemoryTransport.always(redeemed());

        // O, L and 1 are not in the alphabet, precisely so no code reads as a
        // word and no word reads as a code.
        Registration.Outcome outcome = registration(transport, forge).link("HELLO-W1", "ada");

        assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        assertEquals(0, transport.sendCount(),
                "input that could never match was sent to core anyway");
    }

    @Test
    @DisplayName("core refusing a redemption is a refusal, carrying core's own reason")
    void redeemRefusalIsReported() {
        ScriptedSurface forge = new ScriptedSurface();

        Registration.Outcome outcome = registration(
                InMemoryTransport.always(refusal("invalid-request")), forge)
                .link("BC23DFG4", "ada");

        Registration.Outcome.Refused refused =
                assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        assertFalse(refused.detail().isBlank(), "core's reason was dropped on the way out");
    }

    @Test
    @DisplayName("an unreachable core makes linking unavailable, not refused")
    void linkOutageIsNotARefusal() {
        // The half of link() that mutation coverage found nothing executing.
        // Linking is where somebody arrives with a code they were just given,
        // so telling them it is invalid when the truth is that core is down is
        // the worst available answer.
        ScriptedSurface forge = new ScriptedSurface();

        Registration.Outcome outcome = registration(
                InMemoryTransport.always(redeemed()).goDown(), forge).link("BC23DFG4", "ada");

        Registration.Outcome.Unavailable unavailable =
                assertInstanceOf(Registration.Outcome.Unavailable.class, outcome);
        assertEquals(DecisionCache.FAIL_CLOSED_MESSAGE, unavailable.message());
    }

    @Test
    @DisplayName("an unnormalisable code is refused with words, not just a type")
    void badCodeSaysWhy() {
        Registration.Outcome outcome = registration(
                InMemoryTransport.always(redeemed()), new ScriptedSurface())
                .link("HELLO-W1", "ada");

        Registration.Outcome.Refused refused =
                assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        assertFalse(refused.detail().isBlank());
    }

    @Test
    @DisplayName("the platform kind this connector speaks for is its own, and it travels")
    void platformKindTravels() {
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = InMemoryTransport.always(redeemed());

        registration(transport, forge).link("BC23DFG4", "ada");

        assertTrue(transport.sent().get(0).contains("\"platformKind\":\"forge\""),
                () -> transport.sent().toString());
    }

    @Test
    @DisplayName("linking to a name already in use is refused before the code is spent")
    void linkToTakenNameSpendsNothing() {
        // Both halves check the name, and this is why: a code redeemed against
        // a name somebody else owns is a code spent for nothing, and core does
        // not give it back.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        InMemoryTransport transport = InMemoryTransport.always(redeemed());

        Registration.Outcome outcome = registration(transport, forge).link("BC23DFG4", "ada");

        assertInstanceOf(Registration.Outcome.Refused.class, outcome);
        assertEquals(0, transport.sendCount(), "the code was redeemed against a taken name");
    }

    @Test
    @DisplayName("linking stops when the forge cannot say whether the name is free")
    void linkWithUnknownPresenceSpendsNothing() {
        ScriptedSurface forge = new ScriptedSurface().goDown();
        InMemoryTransport transport = InMemoryTransport.always(redeemed());

        Registration.Outcome outcome = registration(transport, forge).link("BC23DFG4", "ada");

        assertInstanceOf(Registration.Outcome.Unavailable.class, outcome);
        assertEquals(0, transport.sendCount());
    }

    // --- coming back without a code -------------------------------------------

    @Test
    @DisplayName("a name already linked reports which subject it belongs to")
    void linkStateBound() {
        String described = "{" + Q + "schema" + Q + ":1," + Q + "ok" + Q + ":true," + Q
                + "payload" + Q + ":{" + Q + "linked" + Q + ":true," + Q + "subjectId" + Q + ":"
                + Q + "s-1" + Q + "}}";

        Registration.Link link = registration(InMemoryTransport.always(described),
                new ScriptedSurface()).linkState("ada");

        assertEquals(new Registration.Link.Bound("s-1"), link);
    }

    @Test
    @DisplayName("a name linked to nothing reports exactly that")
    void linkStateUnbound() {
        String described = "{" + Q + "schema" + Q + ":1," + Q + "ok" + Q + ":true," + Q
                + "payload" + Q + ":{" + Q + "linked" + Q + ":false}}";

        assertEquals(new Registration.Link.Unbound(),
                registration(InMemoryTransport.always(described), new ScriptedSurface())
                        .linkState("ada"));
    }

    @Test
    @DisplayName("being refused the question is UNKNOWN, never unbound")
    void linkStateRefusalIsUnknown() {
        // Almost always a missing link-state-reader. Treating a permissions
        // problem as "not linked" would ask everybody for a code forever and
        // look from the outside like it was working.
        Registration.Link link = registration(
                InMemoryTransport.always(refusal("missing-capability")), new ScriptedSurface())
                .linkState("ada");

        Registration.Link.Unknown unknown =
                assertInstanceOf(Registration.Link.Unknown.class, link);
        assertTrue(unknown.message().toLowerCase().contains("our side"), unknown::message);
        assertTrue(unknown.message().contains("missing-capability"), unknown::message);
    }

    @Test
    @DisplayName("an unreachable core is UNKNOWN too, with the shared wording")
    void linkStateOutageIsUnknown() {
        Registration.Link link = registration(
                InMemoryTransport.always(redeemed()).goDown(), new ScriptedSurface())
                .linkState("ada");

        assertEquals(new Registration.Link.Unknown(DecisionCache.FAIL_CLOSED_MESSAGE), link);
    }

    @Test
    @DisplayName("the question names this connector's own platform kind")
    void linkStateAsksAboutOurKind() {
        InMemoryTransport transport = InMemoryTransport.always(
                "{" + Q + "schema" + Q + ":1," + Q + "ok" + Q + ":true," + Q + "payload" + Q
                        + ":{" + Q + "linked" + Q + ":false}}");

        registration(transport, new ScriptedSurface()).linkState("ada");

        assertTrue(transport.sent().get(0).contains(Q + "platformKind" + Q + ":" + Q + "forge"),
                () -> transport.sent().toString());
        assertTrue(transport.sent().get(0).contains("identity.describe"),
                () -> transport.sent().toString());
    }

    // --- retry -----------------------------------------------------------------

    @Test
    @DisplayName("registering consumes nothing, so a refusal can be waited out")
    void registrationIsRetryable() {
        // The reason link and register are separate calls. Core burns the code
        // at redemption; if one call did both, somebody refused for want of a
        // second identity would need a fresh code to try again.
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = InMemoryTransport.always(deny());
        Registration registration = registration(transport, forge);

        assertInstanceOf(
                Registration.Outcome.Refused.class, registration.register(ACCOUNT));

        transport.respondWith(request -> allow());

        assertEquals(new Registration.Outcome.Created("ada"), registration.register(ACCOUNT));
    }

    @Test
    @DisplayName("fail-open creates, because that is what the operator asked for")
    void failOpenIsHonoured() {
        // Not a recommendation. The fail mode's contract is that only the exact
        // word `open` opens it, and honouring that is the contract -- but on
        // this connector it means an unreachable core creates accounts, which
        // is why the default is closed and why this is asserted rather than
        // left to be discovered.
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = InMemoryTransport.always(allow()).goDown();

        Registration.Outcome outcome = registration(
                transport, forge, new DecisionCache(DecisionCache.FailMode.OPEN))
                .register(ACCOUNT);

        assertEquals(new Registration.Outcome.Created("ada"), outcome);
    }

}
