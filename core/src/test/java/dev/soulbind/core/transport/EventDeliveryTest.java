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
package dev.soulbind.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.EventType;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Event delivery: the Phase 4 gate.
 *
 * <p>The gate is asserted <b>by reading the effector's state back</b>, never by
 * counting deliveries. A delivery count proves what the transport did; the
 * effector's state proves what actually happened to the world, which is the
 * only thing anybody cares about. Delivery is at-least-once, so counting would
 * be asserting the wrong number anyway.
 */
class EventDeliveryTest {

    @TempDir
    Path tempDir;

    /**
     * A stand-in effector with an idempotency key set.
     *
     * <p>Deliberately naive about everything except dedup, because dedup is the
     * property under test. A real effector grants a role; this one records that
     * it would have.
     */
    private static final class FakeEffector {
        private final Set<String> appliedKeys = new LinkedHashSet<>();
        private final Map<String, Integer> applyCounts = new HashMap<>();
        private final List<Long> order = new ArrayList<>();

        /** Applies an event unless its key has been seen. Returns true if applied. */
        boolean apply(long sequence, String type, String key) {
            applyCounts.merge(type, 1, Integer::sum);
            if (!appliedKeys.add(key)) {
                return false;
            }
            order.add(sequence);
            return true;
        }

        int distinctApplied() {
            return appliedKeys.size();
        }

        List<Long> order() {
            return List.copyOf(order);
        }
    }

    private JsonNode ok(TestCore core, Clock clock, String op, String payload) throws Exception {
        JsonNode json = core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    /** Polls, applies through the effector, and acknowledges. */
    private int drain(TestCore core, Clock clock, FakeEffector effector) throws Exception {
        int applied = 0;
        while (true) {
            JsonNode page = ok(core, clock, "event.subscribe", "{\"limit\":25}");
            JsonNode events = page.get("events");
            if (events.isEmpty()) {
                return applied;
            }
            long last = 0;
            for (JsonNode event : events) {
                long seq = event.get("sequence").asLong();
                if (effector.apply(
                        seq, event.get("type").asText(), event.get("idempotencyKey").asText())) {
                    applied++;
                }
                last = seq;
            }
            ok(core, clock, "event.ack", "{\"through\":" + last + "}");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("GATE: a connector down for 100 mutations receives exactly what it missed")
    void downForAHundredMutations(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY,
                        Capability.IDENTITY_PROVIDER, Capability.CONFIG_MANAGEMENT), clock)) {

            // 100 mutations while nobody is listening. The connector's cursor
            // stays at zero because it never acknowledged anything.
            for (int i = 0; i < 100; i++) {
                ok(core, clock, "attest",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-" + i + "\","
                                + "\"proofMethod\":\"oauth\"}");
            }

            assertEquals(
                    100, core.storage.events().highestSequence(),
                    "the outbox holds one event per mutation");
            assertEquals(
                    0, core.storage.events().cursorOf(core.connector.id()),
                    "a connector that never acknowledged has not advanced");

            // It comes back.
            FakeEffector effector = new FakeEffector();
            int applied = drain(core, clock, effector);

            assertEquals(100, applied, "every missed event was applied");
            assertEquals(
                    100, effector.distinctApplied(),
                    "and each exactly once, by the effector's own reckoning");

            // IN ORDER. Applying out of order would let a subscriber see an
            // unlink before the link it undoes.
            List<Long> order = effector.order();
            for (int i = 0; i < order.size(); i++) {
                assertEquals(
                        (long) (i + 1), order.get(i),
                        () -> "events were applied out of order: " + order);
            }

            assertEquals(
                    100, core.storage.events().cursorOf(core.connector.id()),
                    "and the cursor caught up");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("polling twice without acknowledging redelivers -- and the key absorbs it")
    void redeliveryIsAbsorbedByTheKey(Backend backend) throws Exception {
        // At-least-once means exactly this: a connector that polled, applied,
        // and died before acknowledging will see the same events again. The
        // effector's state must be identical afterwards.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.IDENTITY_PROVIDER), clock)) {

            for (int i = 0; i < 5; i++) {
                ok(core, clock, "attest",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-" + i + "\"}");
            }

            FakeEffector effector = new FakeEffector();

            // Poll and apply, but do NOT acknowledge.
            JsonNode first = ok(core, clock, "event.subscribe", "{}");
            for (JsonNode event : first.get("events")) {
                effector.apply(
                        event.get("sequence").asLong(),
                        event.get("type").asText(),
                        event.get("idempotencyKey").asText());
            }
            assertEquals(5, effector.distinctApplied());

            // The cursor did not move, so the same events come back.
            assertEquals(0, core.storage.events().cursorOf(core.connector.id()));
            JsonNode second = ok(core, clock, "event.subscribe", "{}");
            assertEquals(
                    5, second.get("events").size(),
                    "polling without acknowledging must redeliver -- otherwise a connector that "
                            + "died mid-apply loses them");

            for (JsonNode event : second.get("events")) {
                effector.apply(
                        event.get("sequence").asLong(),
                        event.get("type").asText(),
                        event.get("idempotencyKey").asText());
            }

            assertEquals(
                    5, effector.distinctApplied(),
                    "the redelivery was absorbed: the effector's state is what it was");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the idempotency key is stable across redeliveries")
    void keyIsStableAcrossRedeliveries(Backend backend) throws Exception {
        // If the key changed per delivery it would dedup nothing, and every
        // redelivery would be applied again -- while looking, from the
        // transport's side, exactly like a correct system.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.IDENTITY_PROVIDER), clock)) {

            ok(core, clock, "attest",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}");

            String firstKey = ok(core, clock, "event.subscribe", "{}")
                    .get("events").get(0).get("idempotencyKey").asText();
            String secondKey = ok(core, clock, "event.subscribe", "{}")
                    .get("events").get(0).get("idempotencyKey").asText();

            assertEquals(firstKey, secondKey);
            assertFalse(firstKey.isBlank());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("acknowledging twice is harmless, and never moves the cursor back")
    void doubleAck(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.IDENTITY_PROVIDER), clock)) {

            for (int i = 0; i < 3; i++) {
                ok(core, clock, "attest",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-" + i + "\"}");
            }

            assertEquals(3, ok(core, clock, "event.ack", "{\"through\":3}")
                    .get("cursor").asLong());
            assertEquals(
                    3, ok(core, clock, "event.ack", "{\"through\":3}").get("cursor").asLong(),
                    "acknowledging the same position twice is a retry, not an error");

            // Backwards is refused. A buggy acknowledgement replaying the whole
            // history is survivable but is a very different amount of work
            // arriving without warning.
            assertEquals(
                    3, ok(core, clock, "event.ack", "{\"through\":1}").get("cursor").asLong(),
                    "the cursor moved backwards");

            assertTrue(
                    ok(core, clock, "event.subscribe", "{}").get("events").isEmpty(),
                    "nothing is redelivered after a full acknowledgement");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("two connectors have independent cursors")
    void cursorsAreIndependent(Backend backend) throws Exception {
        // A shared position would mean whichever subscriber was fastest decided
        // what the others never saw.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.IDENTITY_PROVIDER), clock)) {

            String other = core.registerAnother("other", Set.of(Capability.EFFECTOR));

            for (int i = 0; i < 4; i++) {
                ok(core, clock, "attest",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-" + i + "\"}");
            }

            ok(core, clock, "event.ack", "{\"through\":4}");
            assertTrue(ok(core, clock, "event.subscribe", "{}").get("events").isEmpty());

            JsonNode otherPage = core.codec.mapper().readTree(core.postSignedAs(
                    other, core.request("event.subscribe", "{}"), clock.instant()).body());
            assertEquals(
                    4, otherPage.get(Wire.PAYLOAD).get("events").size(),
                    "one connector's acknowledgement consumed another's events");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the page size is bounded whatever a caller asks for")
    void pageIsBounded(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.IDENTITY_PROVIDER), clock)) {

            for (int i = 0; i < 12; i++) {
                ok(core, clock, "attest",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-" + i + "\"}");
            }

            assertEquals(
                    5, ok(core, clock, "event.subscribe", "{\"limit\":5}")
                            .get("events").size());
            assertEquals(
                    12, ok(core, clock, "event.subscribe", "{\"limit\":999999}")
                            .get("events").size(),
                    "only twelve exist; the ceiling clamps the request, not the result");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the page ceiling actually binds, at any requested size")
    void pageCeilingBinds() {
        // Asserted against the clamp directly. The wire test above asks for
        // 999,999 with twelve events in the outbox, so the ceiling never binds
        // there -- a mutation removing it passed. Pressing against it through
        // the wire would mean creating a thousand events to prove one integer.
        int max = CoreHandlers.maxEventPage();

        assertEquals(max, CoreHandlers.effectivePageSize(max + 1));
        assertEquals(max, CoreHandlers.effectivePageSize(Integer.MAX_VALUE));
        assertEquals(max, CoreHandlers.effectivePageSize(max));
        assertEquals(max - 1, CoreHandlers.effectivePageSize(max - 1));

        // And the floor, so a caller asking for nothing does not get an
        // infinite loop of empty pages.
        assertEquals(1, CoreHandlers.effectivePageSize(0));
        assertEquals(1, CoreHandlers.effectivePageSize(-5));
        assertTrue(CoreHandlers.effectivePageSize(null) > 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("linking emits the events the specification says it does")
    void linkingEmitsItsEvents(Backend backend) throws Exception {
        // T4's "which mutations emit what". Asserted from the stream a
        // subscriber actually sees, not from the emitter's own bookkeeping.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY,
                        Capability.CONFIG_MANAGEMENT), clock)) {

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}")
                    .get("code").asText();
            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"kind-b\","
                            + "\"platformId\":\"acct-2\"}");
            ok(core, clock, "identity.unlink",
                    "{\"platformKind\":\"kind-b\",\"platformId\":\"acct-2\"}");

            Set<String> seen = new HashSet<>();
            for (JsonNode event : ok(core, clock, "event.subscribe", "{}").get("events")) {
                seen.add(event.get("type").asText());
            }

            assertTrue(
                    seen.contains(EventType.IDENTITY_LINKED.wireName()),
                    () -> "a link emitted no identity.linked: " + seen);
            assertTrue(
                    seen.contains(EventType.IDENTITY_UNLINKED.wireName()),
                    () -> "an unlink emitted no identity.unlinked: " + seen);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a caller-supplied position overrides the stored cursor")
    void explicitPosition(Backend backend) throws Exception {
        // How a connector that keeps its own bookkeeping stays authoritative
        // about what it applied.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.IDENTITY_PROVIDER), clock)) {

            for (int i = 0; i < 6; i++) {
                ok(core, clock, "attest",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-" + i + "\"}");
            }
            ok(core, clock, "event.ack", "{\"through\":6}");

            assertEquals(
                    2, ok(core, clock, "event.subscribe", "{\"after\":4}")
                            .get("events").size(),
                    "an explicit position must be honoured even when the cursor is further on");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the full-stack link sequence puts a requirements-met on the wire, named and addressed")
    void theLinkSequencePutsSomethingActionableOnTheWire(Backend backend) throws Exception {
        // The full-stack `groups` stage performs exactly this sequence and then
        // waits for a permissions group that never arrives. Nothing between
        // `GateTransitionTest` -- which reads the outbox through the repository
        // -- and a live proxy asserted that the event an effector consumes
        // comes back over `event.subscribe` carrying the two fields an effector
        // routes on. An event emitted with the right gate and delivered without
        // it is indistinguishable, from the connector's side, from no event at
        // all: it drops the page in silence and acknowledges past it.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.CONFIG_MANAGEMENT, Capability.CODE_DISPLAY,
                        Capability.CODE_ENTRY, Capability.ENFORCEMENT_POINT), clock)) {

            String player = "d96d427c-e2d6-3a31-8231-cf626a854942";
            String ref = "game:" + player;

            ok(core, clock, "rule.set",
                    "{\"gate\":\"game.join\",\"requiredKinds\":[],\"requireLinked\":true,"
                            + "\"graceSeconds\":0,\"defaultEffect\":\"deny\"}");

            // The gate denies first, as it does for the unlinked player the
            // stage connects before anything else. This is also what registers
            // the gate by first mention, and it happens BEFORE the link -- the
            // ordering the stage relies on.
            assertEquals(
                    "deny",
                    ok(core, clock, "decide",
                            "{\"gate\":\"game.join\",\"platformKind\":\"game\","
                                    + "\"platformId\":\"" + player + "\"}")
                            .get("effect").asText());

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"game\",\"platformId\":\"" + player + "\","
                            + "\"display\":\"Linker\"}")
                    .get("code").asText();

            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"harness\","
                            + "\"platformId\":\"acct-1\"}");

            assertEquals(
                    "allow",
                    ok(core, clock, "decide",
                            "{\"gate\":\"game.join\",\"platformKind\":\"game\","
                                    + "\"platformId\":\"" + player + "\"}")
                            .get("effect").asText(),
                    "the link did not open the gate, so there is nothing for an effector to"
                            + " act on and the rest of this test would be asserting the wrong"
                            + " thing");

            // `after: 0` rather than the cursor, for the same reason the stage
            // now uses it: this reads the outbox as it stands, not as this
            // connector has consumed it.
            JsonNode page = ok(core, clock, "event.subscribe", "{\"after\":0,\"limit\":100}");

            List<String> types = new ArrayList<>();
            JsonNode met = null;
            for (JsonNode event : page.get("events")) {
                types.add(event.get("type").asText());
                if (EventType.SUBJECT_REQUIREMENTS_MET.wireName().equals(
                                event.get("type").asText())
                        && ref.equals(event.get("identityRef").asText())) {
                    met = event;
                }
            }

            assertNotNull(met,
                    "no requirements-met for " + ref + " reached the wire; the outbox delivered "
                            + types);

            // THE TWO FIELDS AN EFFECTOR ROUTES ON. Either missing and the
            // connector drops the event silently -- which is precisely the
            // shape of "the drain ran, acknowledged, and granted nothing".
            assertEquals("game.join", met.get("gate").asText(),
                    "the event does not name the gate, so a connector configured for one gate"
                            + " cannot tell whether this is its business");
            assertFalse(met.get("subjectId").isNull(),
                    "the event carries no subject, so nothing can correlate it to the person");
        }
    }
}
