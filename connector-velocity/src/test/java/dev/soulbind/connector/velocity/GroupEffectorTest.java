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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The permissions effector, tested without a permissions plugin.
 *
 * <p>Which is the point: the behaviours that matter are what happens when it is
 * <em>absent</em>, when a grant throws, and when nothing is configured. All
 * three are the cases a real plugin on the classpath would make harder to reach,
 * not easier.
 */
class GroupEffectorTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private final List<String> logged = new ArrayList<>();

    private void log(String message, Throwable cause) {
        logged.add(message);
    }

    @Test
    @DisplayName("a present effector grants")
    void grants() {
        AtomicInteger grants = new AtomicInteger();
        GroupEffector effector = GroupEffector.of(
                (id, group) -> grants.incrementAndGet(), (id, group) -> { }, this::log);

        assertTrue(effector.grant(PLAYER, "linked"));
        assertEquals(1, grants.get());
        assertTrue(effector.isAvailable());
    }

    @Test
    @DisplayName("an ABSENT permissions plugin is a logged no-op, not a failure")
    void absentIsNonFatal() {
        // A proxy without one should still run /link and still enforce the join
        // gate. Refusing to start over a missing optional integration turns one
        // operator's choice into an outage.
        GroupEffector effector = GroupEffector.absent(this::log);

        assertFalse(effector.isAvailable());
        assertFalse(effector.grant(PLAYER, "linked"));
        assertEquals(1, logged.size(), "absence must be said once, not per call");
        assertTrue(logged.get(0).contains("still work"), logged.get(0));
    }

    @Test
    @DisplayName("a missing class resolves to absent rather than throwing")
    void discoverMissingClass() {
        GroupEffector effector = GroupEffector.discover(
                "com.example.definitely.NotHere",
                () -> Optional.of(GroupEffector.of((i, g) -> { }, (i, g) -> { }, this::log)),
                this::log);

        assertFalse(
                effector.isAvailable(),
                "the resolver ran even though its class was absent");
    }

    @Test
    @DisplayName("a present class uses the resolver")
    void discoverPresentClass() {
        AtomicInteger grants = new AtomicInteger();
        GroupEffector effector = GroupEffector.discover(
                "java.lang.String",
                () -> Optional.of(GroupEffector.of(
                        (i, g) -> grants.incrementAndGet(), (i, g) -> { }, this::log)),
                this::log);

        assertTrue(effector.isAvailable());
        effector.grant(PLAYER, "linked");
        assertEquals(1, grants.get());
    }

    @Test
    @DisplayName("a resolver that declines falls back to absent")
    void resolverDeclines() {
        // The class is there but the integration could not be built -- a version
        // whose API moved, say. Absent is the honest answer.
        GroupEffector effector = GroupEffector.discover(
                "java.lang.String", Optional::empty, this::log);
        assertFalse(effector.isAvailable());
    }

    @Test
    @DisplayName("a grant that throws is reported, not propagated")
    void grantFailureIsContained() {
        // A player who linked successfully should not see an error because a
        // permissions plugin was briefly unhappy. The link happened; the group
        // is a consequence that can be retried.
        GroupEffector effector = GroupEffector.of(
                (id, group) -> {
                    throw new IllegalStateException("permissions backend down");
                },
                (id, group) -> { },
                this::log);

        assertFalse(effector.grant(PLAYER, "linked"));
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("could not grant"), logged.get(0));
    }

    @Test
    @DisplayName("an unconfigured group is a no-op, and is not an error")
    void noGroupConfigured() {
        AtomicInteger grants = new AtomicInteger();
        GroupEffector effector = GroupEffector.of(
                (id, group) -> grants.incrementAndGet(), (id, group) -> { }, this::log);

        for (String group : new String[] {null, "", "   "}) {
            assertFalse(effector.grant(PLAYER, group));
        }
        assertEquals(0, grants.get());
        assertTrue(logged.isEmpty(), "an unconfigured group is a choice, not a problem to log");
    }

    @Test
    @DisplayName("revoke behaves the same way as grant")
    void revokeMatchesGrant() {
        AtomicInteger revokes = new AtomicInteger();
        GroupEffector effector = GroupEffector.of(
                (id, group) -> { }, (id, group) -> revokes.incrementAndGet(), this::log);

        assertTrue(effector.revoke(PLAYER, "linked"));
        assertEquals(1, revokes.get());
        assertFalse(effector.revoke(PLAYER, null));
    }
}
