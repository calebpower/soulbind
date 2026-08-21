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
        sync(r, "allow", List.of(), new ArrayList<>(),
                event(1, "subject.requirements-met", "chat:" + PLAYER, GATE)).drain();

        assertTrue(r.granted().isEmpty(),
                "a group was applied for an account on another platform: " + r.granted());
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
