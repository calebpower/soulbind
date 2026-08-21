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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * The connector's logic, entirely against the scripted surface.
 *
 * <p>No platform, no network. Every behaviour worth asserting — what a person is
 * told, whether they were told privately, which of two gates refused them, and
 * whether a role application is idempotent — runs here in milliseconds.
 *
 * <p>What these do NOT prove: that the real client library is wired to this
 * correctly. That claim needs the platform, and it is a named manual smoke
 * rather than a tier, because a test that needs somebody to create a throwaway
 * account is not a test that runs.
 */
class ChatConnectorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    private static final ChatSurface.Invoker MEMBER =
            new ChatSurface.Invoker("acct-1", "Alex", false);
    private static final ChatSurface.Invoker ADMIN =
            new ChatSurface.Invoker("acct-admin", "Root", true);

    private record Fixture(ChatConnector connector, ScriptedSurface surface) {}

    private Fixture fixture(InMemoryTransport transport) {
        ScriptedSurface surface = new ScriptedSurface();
        return new Fixture(
                new ChatConnector(
                        new SoulbindClient(transport, "cred", CLOCK, new DecisionCache()),
                        surface, "chat"),
                surface);
    }

    private static ChatSurface.Invocation invoke(
            String command, ChatSurface.Invoker who, String... args) {
        return new ChatSurface.Invocation(command, List.of(args), who);
    }

    private static String ok(String payload) {
        return "{\"schema\":1,\"ok\":true,\"payload\":" + payload + "}";
    }

    private static String refusal(String message) {
        return "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"invalid-request\","
                + "\"message\":\"" + message + "\"}}";
    }

    // --- linking ---------------------------------------------------------------

    @Test
    @DisplayName("/link issues a code, and shows it ONLY to the person who asked")
    void issueIsEphemeral() {
        // A code in a public channel is a code anybody can redeem -- and the
        // person who asked would have no idea somebody else took it.
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"code\":\"BCDFGHJK\",\"expiresAtEpochSeconds\":1700000600}")));

        f.connector().handle(invoke("link", MEMBER));

        ScriptedSurface.Sent sent = f.surface().lastSent();
        assertTrue(sent.message().contains("BCDFGHJK"), sent.message());
        assertTrue(
                sent.ephemeral(),
                "the code was shown publicly; anybody in the channel could redeem it");
    }

    @Test
    @DisplayName("/link CODE redeems and reports the graph")
    void redeem() {
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"subjectId\":\"s1\",\"identities\":[{},{}]}")));

        f.connector().handle(invoke("link", MEMBER, "BCDFGHJK"));

        assertTrue(f.surface().lastSent().message().contains("Linked"),
                f.surface().lastSent().message());

        // "1 other account." with the stop, because "1 other account" is a
        // PREFIX of "1 other accounts" -- the assertion that stood here passed
        // whichever branch ran, and mutation found it by negating the choice.
        assertTrue(f.surface().lastSent().message().contains("1 other account."),
                f.surface().lastSent().message());
    }

    @Test
    @DisplayName("the plural agrees with the count, on both sides of the boundary")
    void pluralAgreesWithTheCount() {
        Fixture three = fixture(InMemoryTransport.always(ok(
                "{\"subjectId\":\"s1\",\"identities\":[{},{},{}]}")));
        three.connector().handle(invoke("link", MEMBER, "BCDFGHJK"));
        assertTrue(three.surface().lastSent().message().contains("2 other accounts."),
                three.surface().lastSent().message());
    }

    @Test
    @DisplayName("each refusal gets its own advice")
    void refusalsAreDistinct() {
        record Case(String wire, String mustContain) {}

        for (Case c : new Case[] {
            new Case("unknown-code: no", "not one we issued"),
            new Case("expired: gone", "expired"),
            new Case("already-redeemed: used", "already been used"),
            new Case("same-account: same", "other platform"),
            new Case("already-linked: both", "already linked"),
        }) {
            Fixture f = fixture(InMemoryTransport.always(refusal(c.wire())));
            f.connector().handle(invoke("link", MEMBER, "BCDFGHJK"));
            assertTrue(
                    f.surface().lastSent().message().toLowerCase(java.util.Locale.ROOT)
                            .contains(c.mustContain()),
                    () -> "'" + c.wire() + "' produced: " + f.surface().lastSent().message());
        }
    }

    @Test
    @DisplayName("an outage blames the system, not the person")
    void outageBlamesTheSystem() {
        Fixture f = fixture(InMemoryTransport.always(ok("{}")).goDown());
        f.connector().handle(invoke("link", MEMBER));
        assertTrue(
                f.surface().lastSent().message().contains("our side, not yours"),
                f.surface().lastSent().message());
    }

    // --- whoami -----------------------------------------------------------------

    @Test
    @DisplayName("/whoami distinguishes linked from verified")
    void whoamiShowsVerification() {
        // "Linked" alone does not tell somebody which account still needs
        // proving, which is the thing they can act on.
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"linked\":true,\"identities\":["
                        + "{\"platformKind\":\"game\",\"display\":\"Alex\","
                        + "\"verifiedAtEpochSeconds\":1700000000},"
                        + "{\"platformKind\":\"chat\",\"display\":\"Alex\"}]}")));

        f.connector().handle(invoke("whoami", MEMBER));
        String message = f.surface().lastSent().message();

        assertTrue(message.contains("game"), message);

        // NOT contains("verified") and contains("not yet verified"): the second
        // string CONTAINS the first, so a reply that said "not yet verified"
        // for both identities satisfied them both. Mutation found it -- negating
        // the verified test changed nothing either assertion could see. The
        // marker is anchored on its separator, which the two forms do not share.
        assertEquals(1, countOf(message, "-- verified"),
                "exactly one identity was proven and the reply does not say which: " + message);
        assertEquals(1, countOf(message, "-- not yet verified"),
                "the unproven identity is not marked as such, so a person cannot tell what"
                        + " still needs doing: " + message);
    }

    /** How many times a marker appears, so "contains" cannot stand in for "once". */
    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("a present-but-zero verification timestamp is NOT verified")
    void whoamiTreatsAZeroTimestampAsUnproven() {
        // The boundary, and the direction that matters: `> 0` rather than
        // `>= 0`. A record carrying the field set to zero is one nobody has
        // proven, and reporting it as verified is the exact lie this marker
        // exists to prevent.
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"linked\":true,\"identities\":["
                        + "{\"platformKind\":\"game\",\"display\":\"Alex\","
                        + "\"verifiedAtEpochSeconds\":0}]}")));

        f.connector().handle(invoke("whoami", MEMBER));
        String message = f.surface().lastSent().message();

        assertEquals(0, countOf(message, "-- verified"),
                "an identity with a zero verification time was reported as proven: " + message);
        assertEquals(1, countOf(message, "-- not yet verified"), message);
    }

    @Test
    @DisplayName("/whoami leaves out an empty display rather than printing empty brackets")
    void whoamiOmitsABlankDisplay() {
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"linked\":true,\"identities\":["
                        + "{\"platformKind\":\"game\",\"display\":\"\"}]}")));

        f.connector().handle(invoke("whoami", MEMBER));
        String message = f.surface().lastSent().message();

        assertFalse(message.contains("()"),
                "a platform that reported no display name rendered as empty brackets, which"
                        + " reads as a bug to the person looking at it: " + message);
        assertTrue(message.contains("game"), message);
    }

    @Test
    @DisplayName("/whoami on an unlinked account says how to start")
    void whoamiUnlinked() {
        Fixture f = fixture(InMemoryTransport.always(ok("{\"linked\":false}")));
        f.connector().handle(invoke("whoami", MEMBER));
        assertTrue(f.surface().lastSent().message().contains("/link"),
                f.surface().lastSent().message());
    }

    // --- the two gates ----------------------------------------------------------

    @Test
    @DisplayName("an ordinary member cannot run an admin command, and core is never asked")
    void adminIsPlatformGated() {
        // The capability model says what this CONNECTOR may ask core. It says
        // nothing about which humans may ask the connector -- and a connector
        // holding config-management would otherwise let any member of a chat
        // server rewrite policy.
        InMemoryTransport transport = InMemoryTransport.always(ok("{\"connectors\":[]}"));
        Fixture f = fixture(transport);

        f.connector().handle(invoke("soulbind", MEMBER, "connectors"));

        assertTrue(
                f.surface().lastSent().message().contains("administrators"),
                f.surface().lastSent().message());
        assertEquals(
                0, transport.sendCount(),
                "core was asked before the platform permission was checked, which spends a "
                        + "round trip and lets an unprivileged member probe policy by reading "
                        + "refusals");
    }

    @Test
    @DisplayName("an administrator can, and core IS asked")
    void adminPasses() {
        InMemoryTransport transport = InMemoryTransport.always(ok(
                "{\"connectors\":[{\"name\":\"proxy\",\"status\":\"active\"}]}"));
        Fixture f = fixture(transport);

        f.connector().handle(invoke("soulbind", ADMIN, "connectors"));

        assertEquals(1, transport.sendCount());
        assertTrue(f.surface().lastSent().message().contains("proxy"),
                f.surface().lastSent().message());
    }

    @Test
    @DisplayName("an advertised subcommand does something, rather than repeating the advert")
    void rulesIsImplemented() {
        // `rules` was named in the usage line and had no case, so it fell to
        // default -- which replied with the same usage line that had suggested
        // it. A person typing what they were told to type got told to type it
        // again. Found by somebody reading the message and asking what it was
        // for, which no assertion here was making.
        InMemoryTransport transport = InMemoryTransport.always(ok(
                "{\"gate\":\"discord.member\",\"requiredKinds\":[\"discord\",\"game\"],"
                        + "\"requireLinked\":true,\"graceSeconds\":0,"
                        + "\"defaultEffect\":\"deny\"}"));
        Fixture f = fixture(transport);

        f.connector().handle(invoke("soulbind", ADMIN, "rules discord.member"));

        assertEquals(1, transport.sendCount(), "core was never asked for the rule");
        String reply = f.surface().lastSent().message();
        assertFalse(reply.startsWith("Usage:"),
                "an advertised subcommand answered with the usage line that advertises it: "
                        + reply);
        assertTrue(reply.contains("discord.member"), reply);
        assertTrue(reply.contains("discord") && reply.contains("game"),
                "the rule's required platforms are not shown, which is the thing an "
                        + "administrator opened it to see: " + reply);
        assertTrue(reply.contains("deny"),
                "what happens when the rule is unmet is not shown: " + reply);
    }

    @Test
    @DisplayName("rules renders every field, including the ones whose value is falsy")
    void rulesShowsTheFalsyFieldsToo() {
        // The whole point of the renderer, stated in its own javadoc: "must be
        // linked: no" is the answer to "why is everybody getting in", and a
        // renderer that omitted falsy values would leave exactly that question
        // unanswered. Nothing asserted it -- the test that existed matched
        // `contains("discord")`, which the gate NAME already satisfies.
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"gate\":\"chat.member\",\"requiredKinds\":[],"
                        + "\"requireLinked\":false,\"graceSeconds\":0,"
                        + "\"defaultEffect\":\"allow\"}")));

        f.connector().handle(invoke("soulbind", ADMIN, "rules chat.member"));
        String reply = f.surface().lastSent().message();

        assertTrue(reply.contains("required platforms: (none)"), reply);
        assertTrue(reply.contains("must be linked: no"), reply);
        assertTrue(reply.contains("grace: none"), reply);
        assertTrue(reply.contains("when unmet: allow"), reply);
    }

    @Test
    @DisplayName("rules renders the non-falsy values as themselves")
    void rulesShowsTheRealValues() {
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"gate\":\"chat.member\",\"requiredKinds\":[\"chat\",\"game\"],"
                        + "\"requireLinked\":true,\"graceSeconds\":600,"
                        + "\"defaultEffect\":\"deny\"}")));

        f.connector().handle(invoke("soulbind", ADMIN, "rules chat.member"));
        String reply = f.surface().lastSent().message();

        assertTrue(reply.contains("required platforms: chat, game"), reply);
        assertTrue(reply.contains("must be linked: yes"), reply);
        assertTrue(reply.contains("grace: 600s"), reply);
        assertTrue(reply.contains("when unmet: deny"), reply);
    }

    @Test
    @DisplayName("a rule payload missing fields renders the safe reading, not blanks")
    void rulesFallsBackWhenFieldsAreAbsent() {
        // An older core, or a gate with no rule at all. The fallback for "what
        // happens when unmet" is DENY, and showing a blank there would tell an
        // administrator nothing about the direction they are failing in.
        Fixture f = fixture(InMemoryTransport.always(ok("{\"gate\":\"chat.member\"}")));

        f.connector().handle(invoke("soulbind", ADMIN, "rules chat.member"));
        String reply = f.surface().lastSent().message();

        assertTrue(reply.contains("required platforms: (none)"), reply);
        assertTrue(reply.contains("must be linked: no"), reply);
        assertTrue(reply.contains("grace: none"), reply);
        assertTrue(reply.contains("when unmet: deny"), reply);
    }

    @Test
    @DisplayName("an unrecognised refusal shows core's own words, not its error code")
    void unknownRefusalShowsTheTail() {
        // The default arm: a reason this build has no wording for still has to
        // say something a person can act on, and core's message after the colon
        // is that something. Showing the code instead would be showing them the
        // one part written for a developer.
        Fixture f = fixture(InMemoryTransport.always(
                refusal("some-new-reason: your account is suspended")));

        f.connector().handle(invoke("link", MEMBER, "BCDFGHJK"));
        String reply = f.surface().lastSent().message();

        // EXACT, not contains. `substring(colon + 2)` off by two still contains
        // the sentence -- it just carries two characters of the error code in
        // front of it -- and mutation found that the contains-assertion could
        // not see the difference.
        assertEquals("your account is suspended", reply,
                "the reply is not exactly core's message; either the code leaked in or the"
                        + " text was cut in the wrong place");
    }

    @Test
    @DisplayName("a refusal with no colon at all is still readable")
    void unknownRefusalWithoutAColon() {
        Fixture f = fixture(InMemoryTransport.always(refusal("something odd happened")));

        f.connector().handle(invoke("link", MEMBER, "BCDFGHJK"));

        assertEquals(
                "That did not work: something odd happened",
                f.surface().lastSent().message());
    }

    @Test
    @DisplayName("a message BEGINNING with the separator keeps its whole text")
    void refusalBeginningWithASeparator() {
        // The boundary the code chose with `> 0` rather than `>= 0`: a colon at
        // position zero leaves no reason in front of it, so the whole message is
        // the reason. Under `>= 0` the reason becomes the empty string and the
        // person is shown a fragment instead.
        Fixture f = fixture(InMemoryTransport.always(refusal(": no reason given")));

        f.connector().handle(invoke("link", MEMBER, "BCDFGHJK"));

        assertEquals(
                "That did not work: : no reason given",
                f.surface().lastSent().message());
    }

    @Test
    @DisplayName("a platform that refuses to revoke is not reported as having revoked")
    void revokeRefusedIsReportedHonestly() {
        // RoleEffector logs "revoked" from this boolean. A platform that said
        // no -- the role was deleted, the bot lost its rank -- must not produce
        // a log line saying the role came off, because the operator reading it
        // stops looking.
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        f.surface().preexistingRole("acct-1", "linked").makeRoleUnavailable("linked");

        assertFalse(f.connector().removeRole("acct-1", "linked"),
                "the platform refused the revoke and the connector reported success");
        assertEquals(1, f.surface().revokeCalls().size(),
                "the platform was never asked, so this test proves nothing");
    }

    @Test
    @DisplayName("a gate core will not answer is null, never a verdict")
    void allowsGateIsNullWhenCoreRefuses() {
        // Null is not false. A connector lacking enforcement-point that read a
        // refusal as "denied" would strip every role it had granted.
        Fixture f = fixture(InMemoryTransport.always(refusal("missing-capability: needs it")));
        List<String> logged = new ArrayList<>();

        assertNull(
                f.connector().allowsGate("acct-1", "chat.member", (m, c) -> logged.add(m)),
                "an unanswerable gate produced a verdict, which is a mass role removal waiting"
                        + " for a core restart");
        assertFalse(logged.isEmpty(),
                "core refused and nothing was logged, so the operator has no way to learn that"
                        + " reconciliation is silently doing nothing");
    }

    @Test
    @DisplayName("a gate core denies is FALSE, so reconciliation can act on it")
    void allowsGateIsFalseOnDeny() {
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"effect\":\"deny\",\"reason\":\"not-linked\",\"detail\":\"x\","
                        + "\"ttlSeconds\":60}")));

        assertEquals(
                Boolean.FALSE,
                f.connector().allowsGate("acct-1", "chat.member", (m, c) -> { }));
    }

    @Test
    @DisplayName("rules without a gate says which word is missing, not the whole usage")
    void rulesWithoutAGateIsSpecific() {
        // "You are in the right place and need one more word" is a different
        // message from "that is not a subcommand". Answering the first with the
        // second is what made the original loop feel like a dead end.
        InMemoryTransport transport = InMemoryTransport.always(ok("{}"));
        Fixture f = fixture(transport);

        f.connector().handle(invoke("soulbind", ADMIN, "rules"));

        String reply = f.surface().lastSent().message();
        assertEquals(0, transport.sendCount(),
                "core was asked for a rule with no gate named");
        assertTrue(reply.contains("Which gate"),
                "a subcommand missing its argument got the generic usage line: " + reply);
    }

    @Test
    @DisplayName("an unknown SUBcommand gets the usage line, and core is never asked")
    void unknownSubcommandGetsUsage() {
        // Distinct from an unknown command: the person is in the right place
        // and typed the wrong second word. `rulesWithoutAGateIsSpecific` covers
        // the near-miss; this covers the arm that catches everything else, and
        // nothing executed it.
        InMemoryTransport transport = InMemoryTransport.always(ok("{}"));
        Fixture f = fixture(transport);

        f.connector().handle(invoke("soulbind", ADMIN, "wibble"));

        assertEquals(0, transport.sendCount(),
                "core was asked about a subcommand this build does not have");
        assertTrue(f.surface().lastSent().message().startsWith("Usage:"),
                "an unknown subcommand produced something other than the usage line: "
                        + f.surface().lastSent().message());
    }

    @Test
    @DisplayName("every command that can be refused says something when it is")
    void refusalsReachThePersonOnEveryCommand() {
        // The else arms. Nothing executed them -- PIT reported no coverage on
        // all three -- and a command that goes quiet when core says no is
        // indistinguishable, to the person who typed it, from a bot that is
        // broken.
        record Case(String command, String[] args, String expected) {}

        List<Case> cases = List.of(
                new Case("whoami", new String[] {}, "look that up"),
                new Case("soulbind", new String[] {"rules chat.member"}, "read that rule"),
                new Case("soulbind", new String[] {"connectors"}, "list connectors"));

        for (Case c : cases) {
            InMemoryTransport transport = InMemoryTransport.always(ok("{}"));
            transport.goDown();
            Fixture f = fixture(transport);

            f.connector().handle(invoke(c.command(), ADMIN, c.args()));

            String reply = f.surface().lastSent().message();
            assertTrue(reply.contains(c.expected()),
                    "'" + c.command() + " " + String.join(" ", c.args()) + "' did not say what"
                            + " it had been unable to do: " + reply);
            assertTrue(f.surface().lastSent().ephemeral(), reply);
        }
    }

    @Test
    @DisplayName("an administrator is still subject to the CAPABILITY gate")
    void adminStillNeedsTheCapability() {
        // Both gates, not either. A server administrator cannot grant this
        // connector a capability core did not.
        Fixture f = fixture(InMemoryTransport.always(
                "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"missing-capability\","
                        + "\"message\":\"nope\",\"capability\":\"config-management\"}}"));

        f.connector().handle(invoke("soulbind", ADMIN, "connectors"));
        assertFalse(
                f.surface().lastSent().message().contains("proxy"),
                "a refusal from core was reported as a connector list");
    }

    // --- the role effector -------------------------------------------------------

    @Test
    @DisplayName("applying a role twice applies it once")
    void roleIsIdempotent() {
        // Delivery is at-least-once, so this runs again for events already
        // applied. A platform logging an audit entry per grant would otherwise
        // fill with duplicates of a thing that did not change.
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));

        assertTrue(f.connector().applyRole("acct-1", "linked"));
        assertFalse(
                f.connector().applyRole("acct-1", "linked"),
                "the second application reported a change that did not happen");

        // The CALL count, not the resulting state. Idempotence here is about
        // not asking the platform twice -- the state is identical either way,
        // which is precisely why state cannot show it. A mutation removing the
        // check passed until this line existed.
        assertEquals(
                1, f.surface().grantCalls().size(),
                () -> "the platform was asked " + f.surface().grantCalls().size()
                        + " times for a role it already had");
        assertEquals(java.util.Set.of("linked"), f.surface().rolesOf("acct-1"));
    }

    @Test
    @DisplayName("a role the platform refuses is reported as not applied")
    void roleRefusalIsHonest() {
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        f.surface().makeRoleUnavailable("linked");

        assertFalse(
                f.connector().applyRole("acct-1", "linked"),
                "a refused grant was reported as applied, so nothing will ever retry it");
        assertTrue(f.surface().rolesOf("acct-1").isEmpty());
    }

    @Test
    @DisplayName("removing a role nobody has changes nothing")
    void removeIsIdempotent() {
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        assertFalse(f.connector().removeRole("acct-1", "linked"));

        f.connector().applyRole("acct-1", "linked");
        assertTrue(f.connector().removeRole("acct-1", "linked"));
        assertFalse(f.connector().removeRole("acct-1", "linked"));
        assertEquals(
                1, f.surface().revokeCalls().size(),
                "the platform was asked to revoke a role nobody held");
    }

    @Test
    @DisplayName("an unconfigured role is a no-op, not an error")
    void unconfiguredRole() {
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        for (String role : new String[] {null, "", "  "}) {
            assertFalse(f.connector().applyRole("acct-1", role));
            assertFalse(f.connector().removeRole("acct-1", role));
        }
    }

    @Test
    @DisplayName("a pre-existing role is respected rather than re-granted")
    void preexistingRole() {
        // Somebody an operator granted by hand. Re-granting would be harmless
        // on most platforms and noisy on all of them.
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        f.surface().preexistingRole("acct-1", "linked");

        assertFalse(f.connector().applyRole("acct-1", "linked"));
        assertTrue(
                f.surface().grantCalls().isEmpty(),
                "the platform was asked to grant a role an operator had already given");
    }

    // --- registration -------------------------------------------------------------

    @Test
    @DisplayName("starting registers exactly the commands it answers")
    void registersItsCommands() {
        // A command registered but unhandled is a command that fails silently
        // for whoever types it; a command handled but unregistered is one
        // nobody can find.
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        f.connector().start();

        assertEquals(ChatConnector.COMMANDS, f.surface().registeredCommands());

        for (String command : f.surface().registeredCommands()) {
            f.surface().clearReplies();
            f.connector().handle(invoke(command, ADMIN));
            assertFalse(
                    f.surface().sent().isEmpty(),
                    () -> "registered command '" + command + "' produced no reply");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("an invoker with no platform id is refused at construction")
    void invokerNeedsAPlatformId(String platformId) {
        // Every reply, every role and every gate decision is addressed by this
        // id. A blank one would produce a connector cheerfully granting roles to
        // an account that does not exist, and the failure would surface far from
        // here. Nothing tested the guard; both halves of it survived mutation.
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatSurface.Invoker(platformId, "Alex", false),
                "an invoker was constructed with a blank platform id: "
                        + quotedOrNull(platformId));
    }

    private static String quotedOrNull(String value) {
        return value == null ? "null" : "'" + value + "'";
    }

    @Test
    @DisplayName("an unknown command says so rather than staying silent")
    void unknownCommand() {
        Fixture f = fixture(InMemoryTransport.always(ok("{}")));
        f.connector().handle(invoke("frobnicate", MEMBER));
        assertTrue(f.surface().lastSent().message().contains("do not know"),
                f.surface().lastSent().message());
    }

    @Test
    @DisplayName("every reply to a command is ephemeral")
    void everyReplyIsPrivate() {
        // Link state is nobody else's business, and a code least of all.
        Fixture f = fixture(InMemoryTransport.always(ok(
                "{\"linked\":false,\"code\":\"BCDFGHJK\",\"connectors\":[]}")));

        for (String command : ChatConnector.COMMANDS) {
            f.surface().clearReplies();
            f.connector().handle(invoke(command, ADMIN));
            for (ScriptedSurface.Sent sent : f.surface().sent()) {
                assertTrue(
                        sent.ephemeral(),
                        () -> "'" + command + "' replied publicly: " + sent.message());
            }
        }
    }
}
