<?php

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

declare(strict_types=1);

namespace Soulbind\Flarum\Protocol;

/**
 * Link-code normalisation, re-implemented.
 *
 * A SECOND implementation of the same rules, written from the specification
 * rather than translated from the Java. That is the point: the golden vectors
 * are the oracle proving the two agree, and a transliteration would agree by
 * construction while disagreeing with the specification in the same way twice.
 *
 * Every rule here has a reason, and the reasons are the same ones the other
 * side carries -- because if they were not, one of the two is wrong.
 */
final class LinkCode
{
    /**
     * The alphabet.
     *
     * No 0/O, no 1/l/I, and no vowels at all: look-alikes stay apart and short
     * codes cannot spell words. 28 characters.
     */
    public const ALPHABET = '23456789BCDFGHJKMNPQRSTVWXYZ';

    /**
     * Separators and whitespace removed before comparison.
     *
     * The ASCII separators people insert for readability, every kind of
     * whitespace, and the three invisibles a copy-paste from a web page drags
     * along: U+00A0, U+200B and U+FEFF. Those three produce the "but I typed it
     * correctly" reports.
     */
    private const STRIPPED = [
        '-', '_', '.', ':', ',',
        "\u{0020}", "\u{0009}", "\u{000A}", "\u{000B}", "\u{000C}", "\u{000D}",
        "\u{00A0}", "\u{200B}", "\u{FEFF}",
    ];

    private function __construct()
    {
    }

    /**
     * Normalises a typed code, or returns null.
     *
     * REJECTS, never repairs. Mapping O to 0 would silently redeem a different
     * code and link the wrong account, with no error anybody can see.
     *
     * @param string|null $raw what the person typed
     * @return string|null the normalised form, or null if it is not a code
     */
    public static function normalise(?string $raw): ?string
    {
        if ($raw === null) {
            return null;
        }

        $candidate = str_replace(self::STRIPPED, '', $raw);

        // Every remaining Unicode whitespace, which the explicit list above
        // cannot enumerate. The list stays because it names the invisibles that
        // are NOT whitespace to a regex -- U+200B and U+FEFF among them.
        $candidate = preg_replace('/\s+/u', '', $candidate) ?? $candidate;

        if ($candidate === '') {
            return null;
        }

        // ASCII case mapping ONLY -- not mb_strtoupper, and not strtoupper.
        //
        // The alphabet is pure ASCII, so Unicode case mapping can do exactly one
        // thing here that ASCII mapping cannot: turn a character that is NOT in
        // the alphabet into one that is. It did, five times over. mb_strtoupper
        // folds U+00DF to 'SS', U+FB00 to 'FF', U+FB05 and U+FB06 to 'ST', and
        // U+017F to 'S' -- so typing a sharp s redeemed a code beginning 'SS'
        // that belonged to somebody else, with no error anybody could see. That
        // is the precise harm the reject-never-repair rule above exists to
        // prevent, committed by the repair step itself.
        //
        // The other side had the same defect through a narrower door: its
        // per-character mapping cannot expand one character into two, so only
        // U+017F got through. The two implementations therefore disagreed about
        // four inputs as well as both being wrong about a fifth, and no vector
        // noticed, because the corpus held no character whose case mapping
        // leaves ASCII.
        //
        // strtr over the ASCII range is byte-wise, which is safe here and not by
        // luck: in UTF-8 no byte of a multi-byte sequence is ever below 0x80, so
        // an 'a'..'z' byte is always a genuine ASCII 'a'..'z'. It is independent
        // of locale and of mb_internal_encoding both, which is what the comment
        // that stood here was reaching for.
        $candidate = strtr(
            $candidate,
            'abcdefghijklmnopqrstuvwxyz',
            'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
        );

        // Validated BYTE-wise, deliberately.
        //
        // Every alphabet character is ASCII, so any byte that is not an alphabet
        // byte is a rejection -- whether it is a stray ASCII character or one
        // byte of a multi-byte sequence. Walking bytes rather than characters
        // leaves this loop with no encoding dependence at all: there is nothing
        // for a hostile mb_internal_encoding to change, rather than a dependence
        // that happens to be handled correctly.
        $length = strlen($candidate);
        for ($i = 0; $i < $length; $i++) {
            if (!str_contains(self::ALPHABET, $candidate[$i])) {
                return null;
            }
        }

        return $candidate;
    }

    /** Whether a typed code normalises to exactly the given normalised form. */
    public static function matches(?string $raw, string $normalised): bool
    {
        $candidate = self::normalise($raw);

        // hash_equals, not ===. A code is a bearer token for its short life, and
        // a length-dependent early return leaks how much of a guess was right.
        return $candidate !== null && hash_equals($normalised, $candidate);
    }
}
