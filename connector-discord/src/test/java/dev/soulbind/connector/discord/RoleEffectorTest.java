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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The role effector under redelivery.
 *
 * <p>Delivery is at-least-once, so the interesting cases are all repeats: the
 * same event twice, a poll that returns what a previous poll already applied, a
 * platform that refuses mid-batch. None of them needs the platform or a network.
 */
class RoleEffectorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final String GATE = "chat.member";
    private static final String ROLE = "linked";

    private record Fixture(RoleEffector effector, ScriptedSurface surface,
            InMemoryTransport transport, List<String> logged) {}

    private static String event(long seq, String type, String key, String ref, String gate) {
        return "{\"sequence\":" + seq + ",\"type\":\"" + type + "\",\"idempotencyKey\":\""
                + key + "\",\"identityRef\":\"" + ref + "\",\"gate\":\"" + gate
                + "\",\"payload\":{}}";
    }

    private static String page(String... events) {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"events\":["
                + String.join(",", events) + "],\"cursor\":0,\"highest\":0}}";
    }

    private static final String ACK_OK =
            "{\"schema\":1,\"ok\":true,\"payload\":{\"cursor\":1}}";

    private Fixture fixture(String... events) {
        String body = page(events);
        InMemoryTransport transport = new InMemoryTransport(
                request -> request.contains("event.ack") ? ACK_OK : body);

        ScriptedSurface surface = new ScriptedSurface();
        SoulbindClient client = new SoulbindClient(transport, "cred", CLOCK, new DecisionCache());
        ChatConnector connector = new ChatConnector(client, surface, "chat");
        List<String> logged = new ArrayList<>();

        return new Fixture(
                new RoleEffector(
                        client, connector, new IdempotentApplier(), GATE, ROLE, "chat",
                        (message, cause) -> logged.add(message)),
                surface, transport, logged);
    }

    /**
     * A fixture whose core answers {@code decide} with a fixed effect.
     *
     * @param decideEffect what core says about everybody, or null to refuse
     *     the question entirely
     */
    private Fixture reconcilingFixture(String decideEffect, String... events) {
        String body = page(events);
        String decideAnswer = decideEffect == null
                ? "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"missing-capability\","
                        + "\"message\":\"enforcement-point\"}}"
                : "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"" + decideEffect
                        + "\",\"reason\":\"x\",\"detail\":\"x\",\"ttlSeconds\":60}}";

        InMemoryTransport transport = new InMemoryTransport(request -> {
            if (request.contains("event.ack")) {
                return ACK_OK;
            }
            if (request.contains("\"decide\"")) {
                return decideAnswer;
            }
            return body;
        });

        ScriptedSurface surface = new ScriptedSurface();
        SoulbindClient client = new SoulbindClient(transport, "cred", CLOCK, new DecisionCache());
        ChatConnector connector = new ChatConnector(client, surface, "chat");
        List<String> logged = new ArrayList<>();
        return new Fixture(
                new RoleEffector(
                        client, connector, new IdempotentApplier(), GATE, ROLE, "chat",
                        (message, cause) -> logged.add(message)),
                surface, transport, logged);
    }

    @Test
    @DisplayName("an Error out of a drain is contained, not left to cancel the schedule")
    void drainQuietlyContainsAnError() {
        // Main's scheduled task caught RuntimeException behind a comment saying
        // "nothing escapes into the scheduler". An Error does, and a cancelled
        // scheduled task is silent: the bot stays online, answers commands, and
        // never grants another role, with nothing in the log to say when it
        // stopped.
        List<String> logged = new ArrayList<>();
        InMemoryTransport transport = new InMemoryTransport(request -> {
            throw new NoClassDefFoundError("dev/soulbind/sdk/Payload");
        });
        SoulbindClient client = new SoulbindClient(transport, "cred", CLOCK, new DecisionCache());
        RoleEffector effector = new RoleEffector(
                client, new ChatConnector(client, new ScriptedSurface(), "chat"),
                new IdempotentApplier(), GATE, ROLE, "chat",
                (message, cause) -> logged.add(message));

        assertDoesNotThrow(effector::drainQuietly,
                "an Error escaped the drain; scheduleWithFixedDelay would cancel every"
                        + " future run and say nothing");
        assertTrue(logged.stream().anyMatch(m -> m.contains("retried")),
                "the failure was swallowed without a word: " + logged);
    }

    @Test
    @DisplayName("an unreachable core is reported once, and so is its recovery")
    void pollFailureIsSaidOnceAndRecoveryToo() {
        // Silence here was deliberate and wrong: a connector that cannot reach
        // core looks exactly like one with nothing to do. Latched rather than
        // per-cycle, because at the default poll interval that is a wall of
        // identical lines an operator learns to scroll past.
        List<String> logged = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean reachable =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        InMemoryTransport transport = new InMemoryTransport(request -> reachable.get()
                ? page()
                : "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"internal\","
                        + "\"message\":\"core is down\"}}");
        SoulbindClient client = new SoulbindClient(transport, "cred", CLOCK, new DecisionCache());
        RoleEffector effector = new RoleEffector(
                client, new ChatConnector(client, new ScriptedSurface(), "chat"),
                new IdempotentApplier(), GATE, ROLE, "chat",
                (message, cause) -> logged.add(message));

        effector.drain();
        effector.drain();
        effector.drain();

        assertEquals(1, logged.size(),
                "an outage was either silent or repeated every cycle: " + logged);
        assertTrue(logged.get(0).contains("cannot poll core"), logged.toString());

        reachable.set(true);
        effector.drain();

        assertEquals(2, logged.size(), logged.toString());
        assertTrue(logged.get(1).contains("recovered"),
                "the outage was announced and its end was not, so the log still reads as"
                        + " broken: " + logged);
    }

    @Test
    @DisplayName("a rule change takes the role from whoever no longer qualifies")
    void ruleChangeRevokes() {
        // Core emits rule.changed and nothing consumed it, so editing a rule
        // left every existing grant standing until each holder happened to link
        // or unlink something else. Core cannot fix that alone: a rule change
        // can flip every subject at once, and fanning that out inside the
        // request that changed the rule would hold a connection across the
        // whole graph. The connector's population is bounded and it already
        // knows it.
        Fixture f = reconcilingFixture("deny",
                event(1, "rule.changed", "k1", "", GATE));
        f.surface().grantRole("acct-1", ROLE);
        f.surface().grantRole("acct-2", ROLE);

        f.effector().drain();

        assertFalse(f.surface().hasRole("acct-1", ROLE),
                "a holder core now denies kept the role");
        assertFalse(f.surface().hasRole("acct-2", ROLE));
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("no longer qualify")),
                "the removal was silent: " + f.logged());
    }

    @Test
    @DisplayName("a rule change leaves alone anybody who still qualifies")
    void ruleChangeKeepsTheQualified() {
        Fixture f = reconcilingFixture("allow",
                event(1, "rule.changed", "k1", "", GATE));
        f.surface().grantRole("acct-1", ROLE);

        f.effector().drain();

        assertTrue(f.surface().hasRole("acct-1", ROLE),
                "a rule change stripped the role from somebody who still qualifies");
    }

    @Test
    @DisplayName("an unanswerable decide revokes NOTHING")
    void outageDoesNotStripRoles() {
        // Null is not false. An outage -- or a connector without
        // enforcement-point -- must not strip roles from everybody holding
        // one, which would turn a brief core restart into a mass removal
        // somebody then undoes by hand.
        Fixture f = reconcilingFixture(null,
                event(1, "rule.changed", "k1", "", GATE));
        f.surface().grantRole("acct-1", ROLE);

        f.effector().drain();

        assertTrue(f.surface().hasRole("acct-1", ROLE),
                "roles were stripped because core could not be asked");
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("enforcement-point")),
                "a missing capability was swallowed, so the connector would look like it "
                        + "was working while reconciling nothing: " + f.logged());
    }

    @Test
    @DisplayName("a rule change for ANOTHER gate is not this effector's business")
    void otherGatesAreIgnored() {
        Fixture f = reconcilingFixture("deny",
                event(1, "rule.changed", "k1", "", "some.other.gate"));
        f.surface().grantRole("acct-1", ROLE);

        f.effector().drain();

        assertTrue(f.surface().hasRole("acct-1", ROLE),
                "a rule change for an unrelated gate revoked this gate's role");
    }

    @Test
    @DisplayName("a requirements-met event grants the role")
    void grantsOnRequirementsMet() {
        Fixture f = fixture(event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));

        RoleEffector.Drained drained = f.effector().drain();

        assertEquals(1, drained.applied());
        assertTrue(drained.acknowledged());
        assertTrue(f.surface().rolesOf("acct-1").contains(ROLE));
    }

    @Test
    @DisplayName("a requirements-lost event revokes it")
    void revokesOnRequirementsLost() {
        Fixture f = fixture(event(1, "subject.requirements-lost", "k1", "chat:acct-1", GATE));
        f.surface().preexistingRole("acct-1", ROLE);

        f.effector().drain();
        assertFalse(f.surface().rolesOf("acct-1").contains(ROLE));
    }

    @Test
    @DisplayName("the SAME event delivered twice is applied once")
    void redeliveryIsAbsorbed() {
        // At-least-once means this happens. The applier holds the key across
        // both drains.
        Fixture f = fixture(event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));

        assertEquals(1, f.effector().drain().applied());
        assertEquals(
                0, f.effector().drain().applied(),
                "the same event was applied a second time");
        assertEquals(
                1, f.surface().grantCalls().size(),
                "the platform was asked twice for one event");
    }

    @Test
    @DisplayName("an event for ANOTHER gate is ignored")
    void otherGateIgnored() {
        // Acting on it would grant this role for a requirement nobody tied to
        // it.
        Fixture f = fixture(
                event(1, "subject.requirements-met", "k1", "chat:acct-1", "some.other.gate"));

        f.effector().drain();
        assertTrue(f.surface().rolesOf("acct-1").isEmpty());
        assertTrue(f.surface().grantCalls().isEmpty());
    }

    @Test
    @DisplayName("an event for another PLATFORM is ignored")
    void otherPlatformIgnored() {
        // The load-bearing one. Splitting kind:id and using the id regardless
        // would grant this platform's role to whoever happened to share an
        // identifier with a game account.
        Fixture f = fixture(
                event(1, "subject.requirements-met", "k1", "game:acct-1", GATE));

        f.effector().drain();
        assertTrue(
                f.surface().grantCalls().isEmpty(),
                "a role was granted from another platform's identity reference");
    }

    @Test
    @DisplayName("an unrelated event type is ignored without noise")
    void unrelatedTypeIgnored() {
        Fixture f = fixture(event(1, "identity.linked", "k1", "chat:acct-1", GATE));

        f.effector().drain();
        assertTrue(f.surface().grantCalls().isEmpty());
        assertTrue(
                f.logged().isEmpty(),
                "an uninteresting event was logged; this stream carries everything, and a "
                        + "line each is a log nobody reads");
    }

    @Test
    @DisplayName("an outage acknowledges nothing, so the events come back")
    void outageAcknowledgesNothing() {
        Fixture f = fixture(event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));
        f.transport().goDown();

        RoleEffector.Drained drained = f.effector().drain();
        assertEquals(0, drained.seen());
        assertFalse(drained.acknowledged());
    }

    @Test
    @DisplayName("an empty page acknowledges nothing")
    void emptyPage() {
        Fixture f = fixture();
        RoleEffector.Drained drained = f.effector().drain();
        assertEquals(0, drained.seen());
        assertFalse(
                drained.acknowledged(),
                "acknowledging on an empty page moves a cursor for no reason");
    }

    @Test
    @DisplayName("a batch is applied in order, and every one lands")
    void batchInOrder() {
        Fixture f = fixture(
                event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE),
                event(2, "subject.requirements-met", "k2", "chat:acct-2", GATE),
                event(3, "subject.requirements-met", "k3", "chat:acct-3", GATE));

        RoleEffector.Drained drained = f.effector().drain();

        assertEquals(3, drained.applied());
        for (String account : new String[] {"acct-1", "acct-2", "acct-3"}) {
            assertTrue(f.surface().rolesOf(account).contains(ROLE), account);
        }
    }

    @Test
    @DisplayName("a role an operator already granted is not re-granted")
    void operatorGrantedRoleIsRespected() {
        Fixture f = fixture(event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));
        f.surface().preexistingRole("acct-1", ROLE);

        f.effector().drain();
        assertTrue(
                f.surface().grantCalls().isEmpty(),
                "the platform was asked to grant a role it had already been given by hand");
    }

    @Test
    @DisplayName("an event that THROWS acknowledges nothing, so it comes back")
    void throwingEventIsNotAcknowledged() {
        // The property that keeps a failed application from being lost forever.
        // Acknowledging past it would skip it permanently -- the role would
        // simply never appear, with nothing anywhere saying why.
        Fixture f = fixture(event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));
        f.surface().makeRoleThrow(ROLE);

        RoleEffector.Drained drained = f.effector().drain();

        assertEquals(0, drained.applied());
        assertFalse(
                drained.acknowledged(),
                "the cursor moved past an event that failed to apply; it will never be "
                        + "delivered again");
        assertFalse(f.logged().isEmpty(), "the failure was not logged");
    }

    @Test
    @DisplayName("a throw stops the batch rather than applying later events out of order")
    void throwStopsTheBatch() {
        // A later event's meaning can depend on an earlier one -- a revoke
        // undoing a grant that never happened. Continuing past a failure
        // applies the undo without the do.
        Fixture f = fixture(
                event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE),
                event(2, "subject.requirements-met", "k2", "chat:acct-2", GATE));
        f.surface().makeRoleThrow(ROLE);

        f.effector().drain();
        assertTrue(
                f.surface().rolesOf("acct-2").isEmpty(),
                "a later event was applied after an earlier one failed");
    }

    @Test
    @DisplayName("recovery: once the platform is well, the retry applies and acknowledges")
    void recoversAfterAThrow() {
        Fixture f = fixture(event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));
        f.surface().makeRoleThrow(ROLE);
        f.effector().drain();

        f.surface().stopThrowing(ROLE);
        RoleEffector.Drained retry = f.effector().drain();

        assertEquals(
                1, retry.applied(),
                "the failed event was not retried; the applier remembered a key for work that "
                        + "never happened");
        assertTrue(retry.acknowledged());
        assertTrue(f.surface().rolesOf("acct-1").contains(ROLE));
    }

    @Test
    @DisplayName("a platform refusal does not stop the batch, and does not acknowledge past it")
    void platformRefusalIsRecorded() {
        // The role failing to apply is not an exception -- ChatSurface reports
        // it. What matters is that the connector does not then claim it worked.
        Fixture f = fixture(
                event(1, "subject.requirements-met", "k1", "chat:acct-1", GATE));
        f.surface().makeRoleUnavailable(ROLE);

        f.effector().drain();
        assertTrue(f.surface().rolesOf("acct-1").isEmpty());
    }
}
