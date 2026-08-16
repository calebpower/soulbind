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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Case folding must not invent a code.
 *
 * <p>Normalisation uppercases before it validates. That order is what makes a
 * typed code case-insensitive, and it is also the one place where a character
 * <em>outside</em> the alphabet can be turned into one inside it — because
 * Unicode case mapping does not stay within its input set. {@code U+017F}
 * (LATIN SMALL LETTER LONG S) uppercases to {@code S}. So {@code ſFGHJK}
 * normalised to {@code SFGHJK} and redeemed a code belonging to somebody else,
 * with no error anybody could see.
 *
 * <p>That is precisely the harm {@link LinkCode}'s reject-never-repair rule
 * exists to prevent, and it was the repair step committing it. The forum side
 * was worse: a whole-string fold also expanded {@code U+00DF} to {@code SS},
 * {@code U+FB00} to {@code FF} and {@code U+FB05}/{@code U+FB06} to {@code ST},
 * so the two implementations disagreed about four inputs as well as both being
 * wrong about a fifth.
 *
 * <p>Neither the vector corpus nor any hand-written test caught it. It was
 * found by mutating the charset handling and noticing every mutant survived —
 * the corpus contained no character whose case mapping leaves ASCII, so there
 * was nothing for the mutants to break.
 *
 * <p>Hence a sweep of <b>every code point</b> rather than a handful of rows.
 * Eight vectors catch eight characters; this catches the next one.
 */
class LinkCodeFoldingTest {

    /**
     * Exactly these 48 characters are a code on their own. Nothing else is.
     *
     * <p>Written out by hand — not derived from {@link LinkCode#ALPHABET}, and
     * not computed with the folding rule. An expectation built the way
     * production builds it would assert only that the code agrees with itself,
     * and the defect this test exists for was a folding rule that was
     * internally consistent and wrong.
     *
     * <p>The 28 alphabet characters, plus the 20 ASCII lowercase letters meant
     * to fold into them. No accented letter, no ligature, no long s, no
     * fullwidth form, no Kelvin sign.
     */
    private static final Map<Integer, String> SINGLE_CHARACTER_CODES = new HashMap<>();

    static {
        String upper = "23456789BCDFGHJKMNPQRSTVWXYZ";
        for (int i = 0; i < upper.length(); i++) {
            SINGLE_CHARACTER_CODES.put((int) upper.charAt(i), String.valueOf(upper.charAt(i)));
        }
        String lower = "bcdfghjkmnpqrstvwxyz";
        for (int i = 0; i < lower.length(); i++) {
            SINGLE_CHARACTER_CODES.put(
                    (int) lower.charAt(i),
                    String.valueOf(Character.toUpperCase(lower.charAt(i))));
        }
    }

    @Test
    @DisplayName("the hand-written expectation is the size it claims: 28 + 20")
    void expectationIsTheStatedSize() {
        // Guards the table above against a silent typo -- two entries colliding
        // would shrink it, and a shrunken table exempts a character instead of
        // testing it.
        assertEquals(
                48,
                SINGLE_CHARACTER_CODES.size(),
                "the table lost an entry, so some character is no longer being checked");
        assertEquals(
                28,
                LinkCode.ALPHABET.length(),
                "the alphabet changed size; the hand-written table above must be updated "
                        + "deliberately rather than left to drift");
    }

    @Test
    @DisplayName("no character outside the alphabet normalises INTO it -- every code point")
    void foldingCannotSynthesiseACode() {
        List<String> failures = new ArrayList<>();
        int total = 0;

        for (int cp = Character.MIN_CODE_POINT; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                continue; // surrogates are not characters
            }
            String character = new String(Character.toChars(cp));
            Optional<String> expected =
                    Optional.ofNullable(SINGLE_CHARACTER_CODES.get(cp));
            Optional<String> actual = LinkCode.normalise(character);

            if (actual.equals(expected)) {
                continue;
            }
            total++;
            // The sweep is complete; only the REPORT is capped. Twenty examples
            // diagnose it and a million lines is not a report. The count below
            // states what the cap hid, so a narrowed report cannot read as a
            // narrow failure.
            if (failures.size() < 20) {
                failures.add(
                        "U+%04X normalised to %s, expected %s"
                                .formatted(cp, actual.orElse("(rejected)"),
                                        expected.orElse("(rejected)")));
            }
        }

        int hidden = total - failures.size();
        int shown = failures.size();
        assertTrue(
                total == 0,
                () -> "case folding invented a code. A character outside the alphabet that "
                        + "normalises into it is a code somebody else can redeem, claimed with "
                        + "no error anybody can see.\n  "
                        + String.join("\n  ", failures)
                        + (hidden > 0
                                ? "\n  ... and %d more (the sweep covered every code point; "
                                        .formatted(hidden)
                                        + "only these %d are shown)".formatted(shown)
                                : ""));
    }

    @Test
    @DisplayName("a folding character embedded in an otherwise valid code is rejected too")
    void foldingCharactersAreRejectedInContext() {
        // The sweep above tests single characters. These are the shapes a person
        // would actually type -- and the shape the defect was reachable through,
        // since a one-character code is not one anybody was issued.
        for (String raw : new String[] {
            "ſFGHJK", "BCDſFGH", "BCDFGHſ",
            "ß2345", "BCDßFGH",
            "ﬀBCDF", "Xﬅ" + "23", "Xﬆ" + "23",
        }) {
            assertEquals(
                    Optional.empty(),
                    LinkCode.normalise(raw),
                    () -> "'" + raw + "' normalised instead of being rejected");
        }
    }

    @Test
    @DisplayName("ASCII case-insensitivity still works -- the fix did not overshoot")
    void asciiLowercaseStillFolds() {
        // A fix that rejected everything would pass every assertion above. This
        // is the other half: the thing normalisation is FOR must still happen.
        assertEquals(Optional.of("BCDFGHJK"), LinkCode.normalise("bcdfghjk"));
        assertEquals(Optional.of("BCDFGHJK"), LinkCode.normalise("BcDfGhJk"));
        assertEquals(Optional.of("XYZ23"), LinkCode.normalise("x-y z23"));
    }
}
