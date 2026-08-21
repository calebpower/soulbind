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
        assertTrue(f.surface().lastSent().message().contains("1 other account"),
                f.surface().lastSent().message());
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
        assertTrue(message.contains("verified"), message);
        assertTrue(message.contains("not yet verified"), message);
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
            f.surface().clear();
            f.connector().handle(invoke(command, ADMIN));
            assertFalse(
                    f.surface().sent().isEmpty(),
                    () -> "registered command '" + command + "' produced no reply");
        }
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
            f.surface().clear();
            f.connector().handle(invoke(command, ADMIN));
            for (ScriptedSurface.Sent sent : f.surface().sent()) {
                assertTrue(
                        sent.ephemeral(),
                        () -> "'" + command + "' replied publicly: " + sent.message());
            }
        }
    }
}
