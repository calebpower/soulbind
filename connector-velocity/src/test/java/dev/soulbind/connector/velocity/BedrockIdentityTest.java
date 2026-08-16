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

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Recognising Bedrock players.
 *
 * <p>Fixtures rather than a live proxy: the shapes below are what Floodgate
 * produces, and the point of the tests is that this code reads them correctly
 * without a Bedrock client, a Geyser instance or a network in the room.
 *
 * <p>What these do NOT prove: that Floodgate still produces these shapes. That
 * claim needs a real client, and it lives in the full-stack battery.
 */
class BedrockIdentityTest {

    /** A Floodgate UUID: all-zero high bits, XUID in the low bits. */
    private static UUID bedrock(long xuid) {
        return new UUID(0L, xuid);
    }

    @Test
    @DisplayName("a Floodgate UUID is recognised by its zero high bits")
    void recognisesBedrockUuid() {
        // Structural, not a string prefix: the textual form's dash layout varies
        // with how it was rendered, and matching "00000000-0000-0000" would miss
        // a UUID printed without dashes.
        assertTrue(BedrockIdentity.isBedrockUuid(bedrock(2535413000000000L)));
        assertTrue(BedrockIdentity.isBedrockUuid(new UUID(0L, 1L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "069a79f4-44e9-4726-a5be-fca90e38aaf5", // version 4, online mode
        "3f5c2b1e-0000-3000-8000-000000000000", // version 3, offline mode
        "ffffffff-ffff-ffff-ffff-ffffffffffff",
    })
    @DisplayName("a Java player's UUID is not mistaken for a Bedrock one")
    void javaUuidIsNotBedrock(String uuid) {
        assertFalse(BedrockIdentity.isBedrockUuid(UUID.fromString(uuid)));
    }

    @Test
    @DisplayName("a null UUID is not Bedrock, and does not throw")
    void nullUuid() {
        assertFalse(BedrockIdentity.isBedrockUuid(null));
        assertTrue(BedrockIdentity.xuidOf(null).isEmpty());
    }

    @Test
    @DisplayName("the XUID is rendered unsigned")
    void xuidIsUnsigned() {
        // An XUID can exceed Long.MAX_VALUE. Rendering it signed produces a
        // negative number that matches nothing on the Bedrock side -- and it
        // would only show up for accounts above the boundary, which is to say
        // in production and not in a test somebody wrote by hand.
        UUID high = bedrock(-1L);
        assertEquals("18446744073709551615", BedrockIdentity.xuidOf(high).orElseThrow());

        UUID ordinary = bedrock(2535413000000000L);
        assertEquals("2535413000000000", BedrockIdentity.xuidOf(ordinary).orElseThrow());
    }

    @Test
    @DisplayName("a Java UUID yields no XUID rather than a misleading one")
    void javaUuidHasNoXuid() {
        assertTrue(BedrockIdentity.xuidOf(
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")).isEmpty());
    }

    // --- name prefixes ---------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        ".Alex,.,Alex",
        "*Alex,*,Alex",
        "Alex,.,Alex",
        "Alex,,Alex",
        ".Alex,,.Alex",
    })
    @DisplayName("a configured prefix is stripped when present and ignored when not")
    void stripsPrefix(String raw, String prefix, String expected) {
        assertEquals(expected, BedrockIdentity.stripPrefix(raw, prefix));
    }

    @Test
    @DisplayName("only ONE leading prefix is removed")
    void stripsOnlyOnce() {
        // A player legitimately named "..Alex" behind a "." prefix becomes
        // ".Alex", not "Alex". Stripping repeatedly mangles a real name, and a
        // mangled display name is worse than an odd one because it looks
        // correct.
        assertEquals(".Alex", BedrockIdentity.stripPrefix("..Alex", "."));
    }

    @Test
    @DisplayName("a prefix appearing mid-name is left alone")
    void stripsOnlyLeading() {
        assertEquals("Al.ex", BedrockIdentity.stripPrefix("Al.ex", "."));
    }

    @Test
    @DisplayName("an operator who disabled the prefix has not created an error")
    void absentPrefixIsFine() {
        assertEquals("Alex", BedrockIdentity.stripPrefix("Alex", ""));
        assertEquals("Alex", BedrockIdentity.stripPrefix("Alex", null));
        assertEquals(null, BedrockIdentity.stripPrefix(null, "."));
    }

    // --- flags -----------------------------------------------------------------

    @Test
    @DisplayName("a Bedrock player carries the bedrock flag and its XUID")
    void bedrockFlags() {
        Map<String, Object> flags = BedrockIdentity.flagsFor(bedrock(2535413000000000L));
        assertEquals(Boolean.TRUE, flags.get("bedrock"));
        assertEquals("2535413000000000", flags.get("xuid"));
    }

    @Test
    @DisplayName("a Java player carries NO bedrock flag, rather than bedrock=false")
    void javaFlagsAreEmpty() {
        // Writing `bedrock = false` would put a Bedrock-shaped field on every
        // identity in the system, and invite a reader to treat its absence as
        // "unknown" rather than as "no".
        Map<String, Object> flags = BedrockIdentity.flagsFor(
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertTrue(flags.isEmpty(), () -> "expected no flags, got " + flags);
    }

    // --- display ---------------------------------------------------------------

    @Test
    @DisplayName("the display name is the stripped name")
    void display() {
        assertEquals(
                "Alex",
                BedrockIdentity.displayFor(".Alex", ".", bedrock(1L)));
    }

    @Test
    @DisplayName("a Bedrock player whose name did not arrive is still nameable")
    void displayFallsBackToUuid() {
        UUID uuid = bedrock(1L);
        assertEquals(uuid.toString(), BedrockIdentity.displayFor(null, ".", uuid));
        assertEquals(uuid.toString(), BedrockIdentity.displayFor("   ", ".", uuid));
        assertEquals(uuid.toString(), BedrockIdentity.displayFor(".", ".", uuid));
    }

    @Test
    @DisplayName("display never folds case")
    void displayPreservesCase() {
        // Minecraft names are case-preserving. Folding one for display shows an
        // operator something different from what the player sees.
        assertEquals("AlEx", BedrockIdentity.displayFor(".AlEx", ".", bedrock(1L)));
    }

    @Test
    @DisplayName("folding is locale-independent")
    void foldIsLocaleIndependent() {
        // Under a Turkish default locale 'I' folds to a dotless i, so a
        // locale-sensitive fold would make comparison depend on where the proxy
        // happens to run.
        assertEquals("ii", BedrockIdentity.fold("Ii"));
        assertEquals(null, BedrockIdentity.fold(null));
    }
}
