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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tier 1 — the link-code alphabet and normalisation at their boundaries.
 *
 * <p>What this does NOT prove: that the Java and PHP implementations agree.
 * That claim belongs to the golden vectors, which are the oracle both sides are
 * checked against. These tests pin the Java side's behaviour so that a vector
 * disagreement is attributable.
 */
class LinkCodeTest {

    @Nested
    @DisplayName("the alphabet")
    class Alphabet {

        @Test
        @DisplayName("excludes every character humans confuse")
        void excludesConfusables() {
            // The whole reason the alphabet is not simply A-Z0-9.
            for (char c : new char[] {'0', 'O', '1', 'I', 'L'}) {
                assertFalse(
                        LinkCode.ALPHABET.indexOf(c) >= 0,
                        () -> "'" + c + "' is in the alphabet, but it is one of the pairs "
                                + "people misread. Its presence defeats the alphabet's purpose.");
            }
        }

        @Test
        @DisplayName("excludes vowels, so a code cannot read as a word")
        void excludesVowels() {
            for (char c : new char[] {'A', 'E', 'I', 'O', 'U'}) {
                assertFalse(
                        LinkCode.ALPHABET.indexOf(c) >= 0,
                        () -> "'" + c + "' is in the alphabet; codes could spell words, and an "
                                + "unfortunate one in a code typed into a public channel is a "
                                + "support ticket at best");
            }
        }

        @Test
        @DisplayName("has no duplicate characters")
        void noDuplicates() {
            Set<Character> seen = new HashSet<>();
            for (char c : LinkCode.ALPHABET.toCharArray()) {
                assertTrue(seen.add(c), () -> "duplicate '" + c + "' skews the generator");
            }
            assertEquals(28, LinkCode.ALPHABET.length(), "alphabet size changed; vectors must be regenerated");
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
        @CsvSource({
            // already canonical
            "BCDF2345, BCDF2345",
            // case
            "bcdf2345, BCDF2345",
            "BcDf2345, BCDF2345",
            // separators people insert for readability
            "BCDF-2345, BCDF2345",
            "BCDF_2345, BCDF2345",
            "BCDF.2345, BCDF2345",
            "BCDF:2345, BCDF2345",
            "'BCDF,2345', BCDF2345",
            "B-C-D-F-2-3-4-5, BCDF2345",
            // whitespace, including leading and trailing
            "'  BCDF2345  ', BCDF2345",
            "'BCDF 2345', BCDF2345",
            "'\tBCDF\t2345\t', BCDF2345",
        })
        @DisplayName("strips separators and uppercases")
        void normalises(String raw, String expected) {
            assertEquals(Optional.of(expected), LinkCode.normalise(raw));
        }

        @Test
        @DisplayName("strips the invisibles a copy-paste drags along")
        void stripsInvisibles() {
            // These are the ones that produce "but I typed it correctly" reports:
            // no-break space, zero-width space, byte-order mark.
            assertEquals(Optional.of("BCDF2345"), LinkCode.normalise("BCDF 2345"));
            assertEquals(Optional.of("BCDF2345"), LinkCode.normalise("BCDF​2345"));
            assertEquals(Optional.of("BCDF2345"), LinkCode.normalise("﻿BCDF2345"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "BCDF0345",   // 0 is not in the alphabet
            "BCDFO345",   // nor is O
            "BCDF1345",   // nor 1
            "BCDFI345",   // nor I
            "BCDFL345",   // nor L
            "BCDFA345",   // nor any vowel
            "BCDF@345",   // punctuation that is not a separator
            "BCDFé2345", // accented latin
            "BCDF😀2345", // astral plane
        })
        @DisplayName("rejects anything outside the alphabet rather than repairing it")
        void rejectsOutsideAlphabet(String raw) {
            assertEquals(
                    Optional.empty(),
                    LinkCode.normalise(raw),
                    "normalisation must reject, never guess. A repaired character does not "
                            + "fail -- it silently produces a different well-formed code, which "
                            + "may belong to somebody else.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "---", "\t\n", "​"})
        @DisplayName("rejects input that normalises to nothing")
        void rejectsEmpty(String raw) {
            assertEquals(Optional.empty(), LinkCode.normalise(raw));
        }

        @Test
        @DisplayName("rejects null without throwing")
        void rejectsNull() {
            assertEquals(Optional.empty(), LinkCode.normalise(null));
        }

        @Test
        @DisplayName("is idempotent")
        void idempotent() {
            String once = LinkCode.normalise("bc-df 2345").orElseThrow();
            assertEquals(Optional.of(once), LinkCode.normalise(once));
            assertTrue(LinkCode.isCanonical(once));
        }

        @Test
        @DisplayName("every alphabet character survives a hostile default locale")
        void alphabetSurvivesHostileLocale() {
            // WHAT THIS PROVES: no alphabet character is altered by uppercasing
            // under a locale with unusual case rules, so a code generated on one
            // server still validates on another.
            //
            // WHAT IT DOES NOT PROVE: that normalisation uses a locale-independent
            // uppercase. It cannot. Turkish maps 'i' to 'İ' rather than 'I', but
            // BOTH are outside the alphabet, so a locale-sensitive implementation
            // rejects exactly the same inputs as a correct one.
            //
            // This was found by mutation-checking: an earlier version of this test
            // asserted that "bcdfi345" is rejected under tr-TR, which passes
            // whether or not the bug is present. A tautology wearing a locale
            // costume. The locale-independence in LinkCode is retained as correct
            // defensive practice, but it is unobservable through this alphabet and
            // is not claimed here.
            java.util.Locale original = java.util.Locale.getDefault();
            try {
                for (String tag : new String[] {"tr-TR", "az-AZ", "lt-LT"}) {
                    java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag));
                    String all = LinkCode.ALPHABET;
                    assertEquals(
                            Optional.of(all),
                            LinkCode.normalise(all.toLowerCase(java.util.Locale.ROOT)),
                            () -> "an alphabet character changed identity under " + tag);
                    assertEquals(
                            Optional.of(all),
                            LinkCode.normalise(all),
                            () -> "already-canonical input changed under " + tag);
                }
            } finally {
                java.util.Locale.setDefault(original);
            }
        }

    }

    @Nested
    @DisplayName("generation")
    class Generation {

        @Test
        @DisplayName("produces codes that normalise to themselves")
        void generatesCanonical() {
            for (int i = 0; i < 200; i++) {
                String code = LinkCode.generate();
                assertEquals(LinkCode.DEFAULT_LENGTH, code.length());
                assertTrue(
                        LinkCode.isCanonical(code),
                        () -> "generated code is not canonical: " + code);
            }
        }

        @Test
        @DisplayName("refuses a length short enough to be guessed")
        void refusesShortLengths() {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class, () -> LinkCode.generate(LinkCode.MIN_LENGTH - 1));
            assertTrue(e.getMessage().contains("guessable"),
                    () -> "the refusal should say why, got: " + e.getMessage());
        }

        @Test
        @DisplayName("uses the whole alphabet")
        void usesWholeAlphabet() {
            // Rejection sampling is easy to get wrong in a way that silently
            // drops the tail of the alphabet. With 28 characters and 20k draws,
            // every character appearing is overwhelmingly likely if the sampler
            // is uniform, and a dropped tail is caught immediately.
            Set<Character> seen = new HashSet<>();
            for (int i = 0; i < 2500; i++) {
                for (char c : LinkCode.generate(8).toCharArray()) {
                    seen.add(c);
                }
            }
            assertEquals(
                    LinkCode.ALPHABET.length(),
                    seen.size(),
                    () -> "generator never produced some alphabet characters, which suggests "
                            + "the rejection-sampling bound is wrong; missing: "
                            + LinkCode.ALPHABET.chars()
                                    .mapToObj(c -> (char) c)
                                    .filter(c -> !seen.contains(c))
                                    .toList());
        }
    }
}
