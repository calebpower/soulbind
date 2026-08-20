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

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generator's two properties that a valid-looking code does not prove.
 *
 * <p>Everything else about {@code generate} is checked by looking at its
 * output: right length, right alphabet. Both survive every mutation that
 * matters, because a biased draw and an unbiased one produce equally
 * well-formed codes. Mutation coverage found three survivors here — the
 * minimum-length boundary, the rejection limit's arithmetic, and the
 * rejection comparison itself — and none of them is visible without either a
 * distribution test or a known byte sequence. This file takes the second.
 */
class LinkCodeGenerationTest {

    /** Hands back a fixed byte sequence, so the sampler's decisions are visible. */
    private static final class Bytes extends Random {
        private static final long serialVersionUID = 1L;
        private final int[] values;
        private int at;

        Bytes(int... values) {
            this.values = values;
        }

        @Override
        public void nextBytes(byte[] buf) {
            if (at >= values.length) {
                throw new IllegalStateException(
                        "the generator asked for more bytes than the test supplied, which"
                                + " means it rejected more draws than expected");
            }
            buf[0] = (byte) values[at++];
        }
    }

    // 28 characters, so the largest usable multiple of 28 at or below 256 is
    // 252. Anything from 252 up must be thrown away: 252..255 would map onto
    // '2','3','4','5' a second time and make those four measurably likelier
    // than the rest. A link code is a bearer token for its short life.
    private static final int LIMIT = 252;

    @Test
    @DisplayName("a draw at or above the rejection limit is discarded, not folded back in")
    void rejectsBiasedDraws() {
        // 253 would fold to index 1 and 252 to index 0. Both must be dropped,
        // leaving 5 -> '7', 9 -> 'C', 0 -> '2', 27 -> 'Z'.
        String code = LinkCode.generate(4, new Bytes(253, LIMIT, 5, 9, 0, 27));

        assertEquals("7C2Z", code,
                "a draw at or above " + LIMIT + " was used instead of discarded, which is"
                        + " exactly the modulo bias the rejection sampling exists to remove");
    }

    @Test
    @DisplayName("the boundary draw itself is rejected")
    void theLimitIsExclusive() {
        // Separated from the test above because they die to different mutants:
        // this one is about `>=` versus `>`, and 252 is the only value that
        // tells them apart.
        String code = LinkCode.generate(4, new Bytes(LIMIT, 5, 9, 0, 27));
        assertEquals("7C2Z", code,
                "the draw exactly at the limit was accepted; the comparison is `>`,"
                        + " not `>=`, and index 0 is now twice as likely as index 5");
    }

    @Test
    @DisplayName("every accepted draw lands inside the alphabet")
    void drawsStayInTheAlphabet() {
        String code = LinkCode.generate(4, new Bytes(0, 27, 251, 28));
        for (int i = 0; i < code.length(); i++) {
            assertTrue(LinkCode.ALPHABET.indexOf(code.charAt(i)) >= 0,
                    "generated character '" + code.charAt(i) + "' is not in the alphabet");
        }
        assertEquals(4, code.length());
    }

    @Test
    @DisplayName("the minimum length is allowed and one below it is refused")
    void minimumLengthIsInclusive() {
        // The boundary, both sides. Only the refusal was tested, so moving the
        // comparison to `<=` -- which bans the shortest LEGAL code -- changed
        // nothing.
        assertEquals(LinkCode.MIN_LENGTH,
                LinkCode.generate(LinkCode.MIN_LENGTH).length(),
                "the minimum permitted length was refused");
        assertThrows(IllegalArgumentException.class,
                () -> LinkCode.generate(LinkCode.MIN_LENGTH - 1),
                "a code shorter than the minimum was generated");
    }

    @Test
    @DisplayName("isCanonical says no to the forms normalisation exists to accept")
    void isCanonicalRejectsNonCanonicalForms() {
        // `replaced boolean return with true` survived: every existing
        // assertion was about a string that IS canonical, so a method that
        // answered yes to everything passed them all.
        String canonical = LinkCode.generate(LinkCode.DEFAULT_LENGTH);
        assertTrue(LinkCode.isCanonical(canonical));

        assertFalse(LinkCode.isCanonical(canonical.toLowerCase(java.util.Locale.ROOT)),
                "lower case reported as canonical");
        assertFalse(LinkCode.isCanonical(canonical.substring(0, 4) + "-"
                        + canonical.substring(4)),
                "a hyphenated code reported as canonical");
        assertFalse(LinkCode.isCanonical(" " + canonical),
                "a leading space reported as canonical");
        assertFalse(LinkCode.isCanonical("AEIOU"),
                "a code of excluded letters reported as canonical");
        assertFalse(LinkCode.isCanonical(""), "the empty string reported as canonical");
        assertFalse(LinkCode.isCanonical(null), "null reported as canonical");
    }
}
