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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The words the protocol is spoken in.
 *
 * <p>A mutation sweep reported {@code Capability.fromWireName},
 * {@code ErrorCode.fromWireName}, {@code EventType.fromWireName},
 * {@code SchemaVersion.isSupported} and {@code PlatformAccount}'s validation as
 * <b>NO_COVERAGE</b> — no test in this module executed them. They are exercised
 * indirectly by everything that speaks the protocol, which is precisely how
 * they went unasserted: a round trip that happens to work is not a claim that
 * it must.
 *
 * <p>What rides on {@code Capability.fromWireName} is authorization. A wire
 * name read as the wrong capability, or as none, is a connector granted or
 * refused the wrong thing.
 */
class WireVocabularyTest {

    /**
     * Round-trips an enum's wire vocabulary.
     *
     * <p>Derived from {@code values()} rather than listed, so a capability or
     * error code added later is covered the day it is added instead of the day
     * somebody remembers this file.
     */
    private static <T extends Enum<T>> void assertVocabulary(
            T[] values, Function<T, String> wireName, Function<String, java.util.Optional<T>> parse,
            String what) {

        Set<String> seen = new HashSet<>();
        for (T value : values) {
            String wire = wireName.apply(value);

            assertTrue(wire != null && !wire.isBlank(),
                    what + " " + value + " has no wire name; it would serialise as nothing");
            assertTrue(seen.add(wire),
                    what + " has two constants sharing the wire name '" + wire
                            + "', so one silently becomes the other on the way in");
            assertEquals(value, parse.apply(wire).orElseThrow(
                            () -> new AssertionError(
                                    what + " cannot read back its own wire name '" + wire + "'")),
                    what + " " + value + " does not survive a round trip");
        }
        assertEquals(values.length, seen.size());

        // Unknown input is refused rather than guessed at. A capability that
        // falls back to some default is a grant nobody wrote.
        for (String nonsense : List.of("", "   ", "not-a-real-name", "CODE_DISPLAY!")) {
            assertTrue(parse.apply(nonsense).isEmpty(),
                    what + " read '" + nonsense + "' as "
                            + parse.apply(nonsense).orElse(null));
        }
        assertTrue(parse.apply(null).isEmpty(), what + " read null as a value");
    }

    @Test
    @DisplayName("every capability survives its wire name, and nothing else parses")
    void capabilities() {
        assertVocabulary(Capability.values(), Capability::wireName,
                Capability::fromWireName, "Capability");
    }

    @Test
    @DisplayName("every error code survives its wire name, and nothing else parses")
    void errorCodes() {
        assertVocabulary(ErrorCode.values(), ErrorCode::wireName,
                ErrorCode::fromWireName, "ErrorCode");
    }

    @Test
    @DisplayName("every event type survives its wire name, and nothing else parses")
    void eventTypes() {
        assertVocabulary(EventType.values(), EventType::wireName,
                EventType::fromWireName, "EventType");
    }

    @Test
    @DisplayName("only the current schema is supported, and that is deliberate")
    void schemaVersion() {
        // §: accepting a version this build has never seen means adopting it
        // untested, and the first real bump would be the first time the branch
        // ever ran. So the check is equality, and the neighbours must fail.
        assertTrue(SchemaVersion.isSupported(SchemaVersion.CURRENT));
        assertFalse(SchemaVersion.isSupported(SchemaVersion.CURRENT + 1),
                "a schema newer than this build understands was accepted");
        assertFalse(SchemaVersion.isSupported(SchemaVersion.CURRENT - 1),
                "a schema older than this build understands was accepted");
        assertFalse(SchemaVersion.isSupported(0));
        assertFalse(SchemaVersion.isSupported(-1));
    }

    @Test
    @DisplayName("a platform account needs both halves, and renders as kind:id")
    void platformAccount() {
        // The ref is what audit rows and refusals are written in terms of, and
        // what an effector splits to find its target. A blank half would make
        // "kind-a:" a valid-looking reference to nobody.
        assertEquals("kind-a:123", new PlatformAccount("kind-a", "123", "Alex").ref());

        for (String blank : List.of("", " ", "\t")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlatformAccount(blank, "123", "Alex"),
                    "a blank platform kind was accepted");
            assertThrows(IllegalArgumentException.class,
                    () -> new PlatformAccount("kind-a", blank, "Alex"),
                    "a blank platform id was accepted");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformAccount(null, "123", "Alex"));
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformAccount("kind-a", null, "Alex"));
    }
}
