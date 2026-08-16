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

import java.security.SecureRandom;
import java.util.Optional;

/**
 * The link-code alphabet, and normalisation of what a human typed.
 *
 * <p>A code is read off one screen and typed into another, often from a phone,
 * often by someone not concentrating. Every decision here is about surviving
 * that.
 *
 * <p><b>Re-implemented in PHP.</b> The alphabet, the normalisation rules and
 * the rejection cases are contract, pinned by golden vectors and run twice —
 * once normally, once under a hostile default charset.
 */
public final class LinkCode {

    /**
     * The alphabet: 28 characters.
     *
     * <p>Excludes the pairs people confuse reading off a screen — {@code 0}/{@code O}
     * and {@code 1}/{@code I}/{@code L} — and excludes vowels, so the generator
     * cannot produce a code that reads as a word. An unfortunate word in a code
     * someone is asked to type into a public channel is a support ticket, and
     * occasionally worse.
     *
     * <p>Not a power of two, deliberately. The generator handles the resulting
     * modulo bias rather than the alphabet being bent to suit the arithmetic.
     */
    public static final String ALPHABET = "23456789BCDFGHJKMNPQRSTVWXYZ";

    /** Default code length. Runtime-configurable; this is the shipped default. */
    public static final int DEFAULT_LENGTH = 8;

    /** Minimum length. Below this a code is guessable within its own lifetime. */
    public static final int MIN_LENGTH = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private LinkCode() {
        throw new AssertionError("no instances");
    }

    /**
     * Normalises what a human typed into the canonical form used for comparison.
     *
     * <p>Three operations, and only these three: strip whitespace and common
     * separators, uppercase, then validate against the alphabet.
     *
     * <p><b>Characters outside the alphabet are rejected, never repaired.</b>
     * It is tempting to map a typed {@code O} onto some letter, since the
     * alphabet contains no {@code O} and the user has clearly misread
     * something. But which character they misread is unknowable — and a wrong
     * guess does not fail, it silently produces a *different well-formed code*,
     * which may belong to somebody else. Rejection asks the user to retype.
     * Guessing risks redeeming the wrong link.
     */
    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (isStrippable(c)) {
                continue;
            }

            // ASCII case mapping ONLY -- not Character.toUpperCase, and not
            // String.toUpperCase.
            //
            // The alphabet is pure ASCII, so Unicode case mapping can do exactly
            // one thing here that ASCII mapping cannot: turn a character that is
            // NOT in the alphabet into one that is. It did. U+017F (LATIN SMALL
            // LETTER LONG S) uppercases to 'S', so "SFGHJK" was redeemable by
            // typing a long s -- somebody else's code, claimed with no error
            // anybody could see. That is the precise harm the reject-never-repair
            // rule above exists to prevent, committed by the repair step itself.
            //
            // Restricting the mapping to a-z is the only rule that cannot invent
            // an alphabet character from a non-alphabet one. It is also
            // locale-independent, which is what the comment that stood here was
            // reaching for -- but locale was the smaller of the two problems, and
            // fixing it per-character hid the larger one by making it rare.
            //
            // LinkCodeFoldingTest sweeps every code point and asserts that no
            // character outside the alphabet normalises into it.
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }

        String candidate = sb.toString();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (ALPHABET.indexOf(candidate.charAt(i)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(candidate);
    }

    /**
     * Separators and whitespace removed before comparison.
     *
     * <p>Covers the ASCII separators people insert for readability, every kind
     * of Unicode whitespace, and the three invisibles a copy-paste from a web
     * page or a chat client drags along: the non-breaking space, the zero-width
     * space, and the byte-order mark. Those three are the ones that produce
     * "but I typed it correctly" reports.
     */
    private static boolean isStrippable(char c) {
        return c == '-' || c == '_' || c == '.' || c == ':' || c == ','
                || Character.isWhitespace(c)
                || c == ' '   // no-break space
                || c == '​'   // zero-width space
                || c == '﻿';  // byte-order mark / zero-width no-break space
    }

    /**
     * Generates a fresh code.
     *
     * <p>Rejection sampling rather than {@code nextInt() % length}: 28 does not
     * divide 256, so a naive modulo makes the first few characters measurably
     * more likely. That is a real loss of entropy in a value that is a bearer
     * token for its short life.
     */
    public static String generate(int length) {
        if (length < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "link code length must be at least " + MIN_LENGTH
                            + "; shorter codes are guessable within a code's lifetime");
        }
        StringBuilder sb = new StringBuilder(length);
        int bound = ALPHABET.length();
        int limit = 256 - (256 % bound); // largest multiple of bound at or below 256
        byte[] buf = new byte[1];
        while (sb.length() < length) {
            RANDOM.nextBytes(buf);
            int v = buf[0] & 0xFF;
            if (v >= limit) {
                continue; // would bias the draw; take another
            }
            sb.append(ALPHABET.charAt(v % bound));
        }
        return sb.toString();
    }

    /** Generates a code of the default length. */
    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /** Whether a string is already in canonical form. */
    public static boolean isCanonical(String s) {
        return s != null && normalise(s).map(s::equals).orElse(false);
    }
}
