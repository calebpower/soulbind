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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a link on another platform ever reaches a permissions group.
 *
 * <p>It did not. The proxy connector never subscribed to core's event stream —
 * nothing in production called {@code GroupEffector.grant} or {@code revoke},
 * only tests did — so {@code effector.group} achieved nothing however it was
 * configured. Phase 5's gate covers the join gate, which is {@code decide} and
 * a different path; Phase 4's event gate was proven with the chat connector;
 * and LuckPerms is not in the composed stack, so no tier could have noticed.
 * DECISIONS 10.23.
 */
class GroupSyncTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
    private static final String GATE = "game.join";
    private static final String GROUP = "linked";
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** A GroupEffector over a set, so a test can see what it did. */
    private record Recording(GroupEffector effector, Set<String> granted) {}

    private static Recording recording() {
        Set<String> granted = new LinkedHashSet<>();
        return new Recording(
                GroupEffector.of(
                        (id, group) -> granted.add(id + "/" + group),
                        (id, group) -> granted.remove(id + "/" + group),
                        (m, c) -> { }),
                granted);
    }

    private static String event(long seq, String type, String ref, String gate) {
        return "{\"sequence\":" + seq + ",\"type\":\"" + type + "\",\"idempotencyKey\":\"k"
                + seq + "\",\"identityRef\":\"" + ref + "\",\"gate\":\"" + gate
                + "\",\"payload\":{}}";
    }

    private static String page(String... events) {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"events\":["
                + String.join(",", events) + "],\"cursor\":0,\"highest\":0}}";
    }

    private GroupSync sync(
            Recording recording, String decideEffect, List<UUID> online, List<String> log,
            String... events) {

        String body = page(events);
        String decideAnswer = decideEffect == null
                ? "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"missing-capability\","
                        + "\"message\":\"enforcement-point\"}}"
                : "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"" + decideEffect
                        + "\",\"reason\":\"x\",\"detail\":\"x\",\"ttlSeconds\":60}}";

        InMemoryTransport transport = new InMemoryTransport(request -> {
            if (request.contains("event.ack")) {
                return "{\"schema\":1,\"ok\":true,\"payload\":{\"cursor\":1}}";
            }
            if (request.contains("\"decide\"")) {
                return decideAnswer;
            }
            return body;
        });

        return new GroupSync(
                new SoulbindClient(transport, "cred", CLOCK, new DecisionCache()),
                recording.effector(), new IdempotentApplier(), GATE, GROUP, "game",
                () -> online, (message, cause) -> log.add(message));
    }

    @Test
    @DisplayName("requirements-met grants the group, which is the whole missing feature")
    void grantsOnRequirementsMet() {
        Recording r = recording();
        GroupSync s = sync(r, "allow", List.of(), new ArrayList<>(),
                event(1, "subject.requirements-met", "game:" + PLAYER, GATE));

        GroupSync.Drained drained = s.drain();

        assertEquals(1, drained.applied());
        assertTrue(drained.acknowledged());
        assertTrue(r.granted().contains(PLAYER + "/" + GROUP),
                "a subject that satisfied the gate got no group: " + r.granted());
    }

    @Test
    @DisplayName("a grant is reported, so an operator can read that it happened")
    void grantsAreReported() {
        Recording r = recording();
        List<String> log = new ArrayList<>();
        sync(r, "allow", List.of(), log,
                event(1, "subject.requirements-met", "game:" + PLAYER, GATE)).drain();

        assertTrue(
                log.stream().anyMatch(m -> m.contains(GROUP) && m.contains(PLAYER.toString())),
                "the group was applied and nothing said so; the only way to confirm it was to"
                        + " read the permissions plugin's own storage: " + log);
    }

    @Test
    @DisplayName("an event for another gate says which gate, rather than vanishing")
    void gateMismatchIsSaidOutLoud() {
        // THE failure this connector kept producing: every step reports success
        // -- the drain polls, applies, acknowledges -- and no group appears,
        // with not one line anywhere saying why. A gate configured as one
        // string and emitted as another is indistinguishable, from the outside,
        // from a connector that was never wired up at all.
        Recording r = recording();
        List<String> log = new ArrayList<>();

        sync(r, "allow", List.of(), log,
                event(1, "subject.requirements-met", "game:" + PLAYER, "somebody.else"))
                .drain();

        assertTrue(r.granted().isEmpty(),
                "a group was applied for a gate this connector is not configured for");
        assertTrue(
                log.stream().anyMatch(m -> m.contains("somebody.else") && m.contains(GATE)),
                "the event was dropped in silence, or the message names only one of the two"
                        + " gates -- an operator needs both to see they differ: " + log);
    }

    @Test
    @DisplayName("an unreachable core is reported once, not twelve times a minute")
    void pollFailureIsSaidOnceAndRecoveryToo() {
        // Latched deliberately. The drain runs at a five-second delay, so
        // logging per cycle would put twelve identical lines a minute into a
        // proxy console during any outage -- and an operator who scrolls past
        // that noise is an operator who misses the line that matters.
        Recording r = recording();
        List<String> log = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean reachable =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        InMemoryTransport transport = new InMemoryTransport(request -> reachable.get()
                ? page()
                : "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"internal\","
                        + "\"message\":\"core is down\"}}");

        GroupSync s = new GroupSync(
                new SoulbindClient(transport, "cred", CLOCK, new DecisionCache()),
                r.effector(), new IdempotentApplier(), GATE, GROUP, "game",
                List::of, (m, c) -> log.add(m));

        s.drain();
        s.drain();
        s.drain();

        assertEquals(1, log.size(),
                "an outage was either silent or repeated every cycle: " + log);
        assertTrue(log.get(0).contains("cannot poll core"), log.toString());

        reachable.set(true);
        s.drain();

        assertEquals(2, log.size(), log.toString());
        assertTrue(log.get(1).contains("recovered"),
                "the outage was announced and its end was not, so the console still reads as"
                        + " broken: " + log);
    }

    @Test
    @DisplayName("requirements-lost takes it back")
    void revokesOnRequirementsLost() {
        Recording r = recording();
        r.effector().grant(PLAYER, GROUP);

        sync(r, "allow", List.of(), new ArrayList<>(),
                event(1, "subject.requirements-lost", "game:" + PLAYER, GATE)).drain();

        assertFalse(r.granted().contains(PLAYER + "/" + GROUP), r.granted().toString());
    }

    @Test
    @DisplayName("a rule change re-checks online players and revokes the unqualified")
    void ruleChangeRevokesOnline() {
        Recording r = recording();
        r.effector().grant(PLAYER, GROUP);
        List<String> log = new ArrayList<>();

        sync(r, "deny", List.of(PLAYER), log,
                event(1, "rule.changed", "", GATE)).drain();

        assertFalse(r.granted().contains(PLAYER + "/" + GROUP),
                "a rule change left the group on somebody core now denies");
        assertTrue(log.stream().anyMatch(m -> m.contains("no longer qualify")), log.toString());
    }

    @Test
    @DisplayName("a rule change leaves the still-qualified alone")
    void ruleChangeKeepsQualified() {
        Recording r = recording();
        r.effector().grant(PLAYER, GROUP);

        sync(r, "allow", List.of(PLAYER), new ArrayList<>(),
                event(1, "rule.changed", "", GATE)).drain();

        assertTrue(r.granted().contains(PLAYER + "/" + GROUP),
                "a rule change stripped the group from somebody who still qualifies");
    }

    @Test
    @DisplayName("an unanswerable decide revokes nothing")
    void outageRevokesNothing() {
        Recording r = recording();
        r.effector().grant(PLAYER, GROUP);
        List<String> log = new ArrayList<>();

        sync(r, null, List.of(PLAYER), log, event(1, "rule.changed", "", GATE)).drain();

        assertTrue(r.granted().contains(PLAYER + "/" + GROUP),
                "groups were stripped because core could not be asked, which turns a brief"
                        + " restart into a mass removal");
        assertTrue(log.stream().anyMatch(m -> m.contains("enforcement-point")), log.toString());
    }

    @Test
    @DisplayName("an identity on another platform is not this connector's business")
    void foreignPlatformsIgnored() {
        // The kind is checked, not merely split off: a chat account whose id
        // happened to parse as a UUID would otherwise take a group here.
        Recording r = recording();
        List<String> log = new ArrayList<>();
        sync(r, "allow", List.of(), log,
                event(1, "subject.requirements-met", "chat:" + PLAYER, GATE)).drain();

        assertTrue(r.granted().isEmpty(),
                "a group was applied for an account on another platform: " + r.granted());

        // Said, not merely done. A deployment whose platform kind is misspelled
        // in the connector's config drops every event on this path, and the
        // only symptom is a group that never appears.
        assertTrue(log.stream().anyMatch(m -> m.contains("chat:" + PLAYER)),
                "the event was dropped without naming the account it was for: " + log);
    }

    @Test
    @DisplayName("an Error out of a drain is contained, not left to cancel the schedule")
    void drainQuietlyContainsAnError() {
        // The scheduled task caught RuntimeException while its own comment
        // claimed it caught everything, so an Error -- a NoClassDefFoundError
        // from a shaded jar, say -- would cancel group sync for the life of the
        // proxy. Every other feature keeps working and nothing is logged, which
        // is the hardest possible shape to diagnose.
        Recording r = recording();
        List<String> log = new ArrayList<>();
        InMemoryTransport transport = new InMemoryTransport(request -> {
            throw new NoClassDefFoundError("dev/soulbind/sdk/Payload");
        });

        GroupSync s = new GroupSync(
                new SoulbindClient(transport, "cred", CLOCK, new DecisionCache()),
                r.effector(), new IdempotentApplier(), GATE, GROUP, "game",
                List::of, (m, c) -> log.add(m));

        assertDoesNotThrow(s::drainQuietly,
                "an Error escaped the drain; scheduleWithFixedDelay would cancel every"
                        + " future run and say nothing");
        assertTrue(log.stream().anyMatch(m -> m.contains("retried")),
                "the failure was swallowed without a word: " + log);
    }

    @Test
    @DisplayName("an unconfigured sync does nothing and asks core nothing")
    void unconfiguredIsInert() {
        // Absent effector, or no group, or no gate. Each must be silent rather
        // than polling core forever for events it can never act on.
        Recording r = recording();
        GroupSync noGroup = new GroupSync(
                new SoulbindClient(new InMemoryTransport(q -> page()), "c", CLOCK,
                        new DecisionCache()),
                r.effector(), new IdempotentApplier(), GATE, null, "game",
                List::of, (m, c) -> { });

        assertFalse(noGroup.isConfigured());
        assertEquals(0, noGroup.drain().seen());
    }
}
