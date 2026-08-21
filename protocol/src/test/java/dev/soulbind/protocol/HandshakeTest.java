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

package dev.soulbind.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a connector claims at the handshake, and what happens to claims core
 * does not recognise.
 *
 * <p>Reported entirely NO_COVERAGE by a mutation sweep, which was surprising
 * for a type carrying a policy rather than data. {@code parsedCapabilities}
 * implements the forward-compatibility rule stated on the class: a connector
 * built against a newer protocol may claim a capability this core has never
 * heard of, and the right answer is to grant it nothing extra — <em>not</em> to
 * refuse the whole handshake and take the connector offline over a name.
 *
 * <p>That rule is the difference between a newer connector degrading and a
 * newer connector failing to start, and nothing asserted it.
 */
class HandshakeTest {

    private static HelloRequest hello(List<String> capabilities) {
        return new HelloRequest("conn", "1.0", capabilities, List.of(), List.of());
    }

    @Test
    @DisplayName("an unknown capability is dropped, not fatal")
    void unknownCapabilitiesAreDropped() {
        HelloRequest request = hello(List.of(
                "code-display", "telepathy", "code-entry", "capability-from-2030"));

        assertEquals(
                Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY),
                request.parsedCapabilities(),
                "the recognised claims did not survive alongside the unrecognised ones");
        assertEquals(
                List.of("telepathy", "capability-from-2030"),
                request.unrecognisedCapabilities(),
                "core cannot tell the connector which of its claims meant nothing");
    }

    @Test
    @DisplayName("every real capability is recognised, derived from the enum")
    void allRealCapabilitiesParse() {
        // Derived rather than listed: a capability added later is covered the
        // day it is added. link-state-reader was added mid-project and four
        // callers broke; a hand-written list here would have been stale then.
        List<String> wire = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            wire.add(capability.wireName());
        }
        HelloRequest request = hello(wire);

        assertEquals(Set.of(Capability.values()), request.parsedCapabilities());
        assertTrue(request.unrecognisedCapabilities().isEmpty(),
                "a real capability was reported as unrecognised: "
                        + request.unrecognisedCapabilities());
    }

    @Test
    @DisplayName("claiming nothing is legal and claiming everything unknown is survivable")
    void edges() {
        assertTrue(hello(List.of()).parsedCapabilities().isEmpty());
        assertTrue(hello(List.of()).unrecognisedCapabilities().isEmpty());

        HelloRequest allNonsense = hello(List.of("a", "b"));
        assertTrue(allNonsense.parsedCapabilities().isEmpty(),
                "nonsense parsed into a capability");
        assertEquals(List.of("a", "b"), allNonsense.unrecognisedCapabilities());
    }

    @Test
    @DisplayName("absent lists become empty, and a nameless connector is refused")
    void nullsAndRequiredFields() {
        // Absent is not the same as invalid. A connector that enforces no gates
        // sends no gates; a connector with no name is a bug on the wire.
        HelloRequest sparse = new HelloRequest("conn", "1.0", null, null, null);
        assertTrue(sparse.capabilities().isEmpty());
        assertTrue(sparse.platformKinds().isEmpty());
        assertTrue(sparse.gates().isEmpty());

        assertThrows(NullPointerException.class,
                () -> new HelloRequest(null, "1.0", List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("the response separates what was granted from what was ignored")
    void responseKeepsGrantedAndIgnoredApart() {
        // Two different facts. "You asked for this and have it" and "you asked
        // for this and it means nothing here" send a connector author to
        // different places, and merging them into one list loses that.
        HelloResponse response = new HelloResponse(
                SchemaVersion.CURRENT, "c-1",
                List.of("code-display"), List.of("telepathy"), 1_700_000_000L);

        assertEquals(List.of("code-display"), response.granted());
        assertEquals(List.of("telepathy"), response.ignored());

        HelloResponse sparse = new HelloResponse(1, "c-1", null, null, 0L);
        assertTrue(sparse.granted().isEmpty());
        assertTrue(sparse.ignored().isEmpty());
        assertThrows(NullPointerException.class,
                () -> new HelloResponse(1, null, List.of(), List.of(), 0L));
    }
}
