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

namespace Soulbind\Flarum\Tests;

use Soulbind\Flarum\Protocol\LinkCode;
use Soulbind\Flarum\Protocol\RequestSigner;

/**
 * The vector assertions, in ONE place.
 *
 * Two things run these: the PHPUnit suite, and a dependency-free runner. Two
 * entry points, one implementation -- because two copies of the assertions
 * would drift, and the copy run less often would drift further while looking
 * like coverage.
 *
 * The runner exists because PHPUnit needs `ext-xmlwriter`, which is not present
 * everywhere, and the vectors are the one claim that must be checkable on any
 * machine with PHP. A cross-language oracle nobody can run is not an oracle.
 *
 * Every check returns its failures rather than throwing, so a caller can report
 * all of them at once and decide what a failure means.
 */
final class VectorChecks
{
    private function __construct()
    {
    }

    /** @return list<string> failures, empty when everything holds */
    public static function normalisation(): array
    {
        $failures = [];

        foreach (Vectors::read('link-code-normalisation.tsv', 2) as $row) {
            [$raw, $expected] = $row['fields'];
            $actual = LinkCode::normalise($raw);

            if (Vectors::isNull($expected)) {
                if ($actual !== null) {
                    $failures[] = "line {$row['line']}: '" . self::visible($raw)
                        . "' should be REJECTED but normalised to '{$actual}'. Repairing a code "
                        . 'is worse than refusing it: it silently redeems a different one.';
                }
                continue;
            }

            if ($actual !== $expected) {
                $failures[] = "line {$row['line']}: '" . self::visible($raw) . "' normalised to '"
                    . ($actual ?? 'null') . "', expected '{$expected}'";
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function signing(): array
    {
        $failures = [];

        foreach (Vectors::read('hmac-signing.tsv', 5) as $row) {
            [$key, $timestamp, $nonce, $body, $expected] = $row['fields'];
            $actualBody = Vectors::isNull($body) ? null : $body;

            $actual = RequestSigner::sign($key, (int) $timestamp, $nonce, $actualBody);
            if ($actual !== $expected) {
                $failures[] = "line {$row['line']}: signature disagrees with the committed "
                    . 'vector, which means it disagrees with the other implementation. Either '
                    . 'the canonical form changed -- a wire break -- or the vector is wrong. Do '
                    . 'not update the vector to match the code without deciding which.';
                continue;
            }

            if (!RequestSigner::verify($key, (int) $timestamp, $nonce, $actualBody, $expected)) {
                $failures[] = "line {$row['line']}: signing agrees with the vector but verifying "
                    . 'does not, so the two disagree with each other';
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function idempotence(): array
    {
        $failures = [];

        foreach (Vectors::read('link-code-normalisation.tsv', 2) as $row) {
            if (Vectors::isNull($row['fields'][1])) {
                continue;
            }
            $once = $row['fields'][1];
            if (LinkCode::normalise($once) !== $once) {
                $failures[] = "line {$row['line']}: '{$once}' does not survive a second "
                    . 'normalisation, so a stored code would stop matching itself';
            }
        }

        return $failures;
    }

    /**
     * The corpus is big enough and balanced enough to mean something.
     *
     * @return list<string>
     */
    public static function corpusShape(): array
    {
        $failures = [];

        $signatures = [];
        foreach (Vectors::read('hmac-signing.tsv', 5) as $row) {
            if (in_array($row['fields'][4], $signatures, true)) {
                $failures[] = "line {$row['line']} repeats an earlier signature";
            }
            $signatures[] = $row['fields'][4];
        }
        if (count($signatures) < 10) {
            $failures[] = 'only ' . count($signatures) . ' signing vectors';
        }

        $rejected = 0;
        $accepted = 0;
        foreach (Vectors::read('link-code-normalisation.tsv', 2) as $row) {
            Vectors::isNull($row['fields'][1]) ? $rejected++ : $accepted++;
        }
        if ($rejected < 10) {
            $failures[] = "only {$rejected} rejection vectors; a corpus of acceptances would "
                . 'pass with rejection deleted entirely';
        }
        if ($accepted < 10) {
            $failures[] = "only {$accepted} acceptance vectors";
        }

        return $failures;
    }

    /**
     * When the hostile run is asked for, it must actually be hostile.
     *
     * @return list<string>
     */
    public static function hostilityTookEffect(): array
    {
        if (getenv('SOULBIND_HOSTILE_CHARSET') !== '1') {
            return [];
        }
        if (mb_internal_encoding() === 'UTF-8') {
            return [
                'the hostile run asked for a non-UTF-8 internal encoding and did not get one, '
                . 'so it proves nothing the ordinary run did not. Fix the configuration rather '
                . 'than deleting this check.',
            ];
        }
        return [];
    }


    /**
     * The signer's argument contract, which the digest corpus cannot express.
     *
     * A vector file is rows of (key, timestamp, nonce, body) -> digest. It can
     * only ever describe inputs that produce a signature, so the rules about
     * inputs that must NOT produce one are invisible to it. Mutation confirmed
     * that: deleting either validation rule from the signer left every vector
     * passing.
     *
     * The other side states the same rules and tests them directly. These are
     * the same rules, so that the two agree about refusals and not merely about
     * digests -- a signer that accepts what the other rejects is a signer that
     * produces signatures the other will not verify.
     *
     * @return list<string>
     */
    public static function signerArgumentValidation(): array
    {
        $failures = [];

        $mustReject = [
            'an empty signing key' => ['', 1700000000, 'abc123', '{}'],
            'an empty nonce' => ['k', 1700000000, '', '{}'],
            // A nonce carrying the field separator would make the canonical form
            // ambiguous: two different (nonce, body) pairs could produce
            // identical signed bytes, and one signature would then authenticate
            // a request nobody signed.
            'a nonce containing the separator' => ['k', 1700000000, "a\nb", '{}'],
            'a nonce that is only a separator' => ['k', 1700000000, "\n", '{}'],
        ];

        foreach ($mustReject as $what => [$key, $timestamp, $nonce, $body]) {
            try {
                RequestSigner::sign($key, $timestamp, $nonce, $body);
                $failures[] = "{$what} was signed instead of rejected";
            } catch (\InvalidArgumentException) {
                // as intended
            }
        }

        // A carriage return is deliberately NOT rejected: the separator is LF
        // alone, so a CR creates no ambiguity, and both implementations treat it
        // as ordinary content. Asserted so that the two stay agreed about it --
        // if one side starts rejecting CR, it stops being able to verify what
        // the other signs, and this states which behaviour is the contract
        // rather than leaving it to whichever was written second.
        try {
            RequestSigner::sign('k', 1700000000, "a\rb", '{}');
        } catch (\InvalidArgumentException) {
            $failures[] = 'a nonce containing a carriage return was rejected. CR is not the '
                . 'field separator and the other implementation signs it; rejecting it here '
                . 'means this side cannot verify what the other side produces.';
        }

        // The obverse: a fix that rejected everything would satisfy every
        // assertion above.
        try {
            RequestSigner::sign('k', 1700000000, 'abc123', null);
            RequestSigner::sign('k', 1700000000, 'abc123', '');
        } catch (\InvalidArgumentException $e) {
            $failures[] = 'a valid call was rejected: ' . $e->getMessage()
                . '. An absent body is legal and canonicalises to empty.';
        }

        return $failures;
    }

    /**
     * Exactly these 48 characters are a code on their own. Nothing else is.
     *
     * Written out by hand, not derived from {@see LinkCode::ALPHABET} and not
     * from the folding rule -- an expectation computed the way production
     * computes it would assert only that the code agrees with itself, and the
     * defect this check exists for was a folding rule that was internally
     * consistent and wrong.
     *
     * The 28 alphabet characters, plus the 20 ASCII lowercase letters that are
     * meant to fold into them. No accented letter, no ligature, no long s, no
     * fullwidth form.
     */
    private const SINGLE_CHARACTER_CODES = [
        '2' => '2', '3' => '3', '4' => '4', '5' => '5', '6' => '6',
        '7' => '7', '8' => '8', '9' => '9',
        'B' => 'B', 'C' => 'C', 'D' => 'D', 'F' => 'F', 'G' => 'G',
        'H' => 'H', 'J' => 'J', 'K' => 'K', 'M' => 'M', 'N' => 'N',
        'P' => 'P', 'Q' => 'Q', 'R' => 'R', 'S' => 'S', 'T' => 'T',
        'V' => 'V', 'W' => 'W', 'X' => 'X', 'Y' => 'Y', 'Z' => 'Z',
        'b' => 'B', 'c' => 'C', 'd' => 'D', 'f' => 'F', 'g' => 'G',
        'h' => 'H', 'j' => 'J', 'k' => 'K', 'm' => 'M', 'n' => 'N',
        'p' => 'P', 'q' => 'Q', 'r' => 'R', 's' => 'S', 't' => 'T',
        'v' => 'V', 'w' => 'W', 'x' => 'X', 'y' => 'Y', 'z' => 'Z',
    ];

    /**
     * No character outside the alphabet may normalise INTO the alphabet.
     *
     * Every code point, not a sample. This is the property the eight folding
     * vectors gesture at; those rows only ever catch the eight characters they
     * name, and the defect they were written for was found by mutation rather
     * than by any of them.
     *
     * Unicode case mapping can map outside its input set: U+017F uppercases to
     * 'S', U+00DF to 'SS', U+FB05 to 'ST'. Applied before validation, that turns
     * a character nobody may type into a code somebody else holds. Both sides
     * now fold ASCII a-z and nothing else, which is the only rule that cannot
     * invent an alphabet character -- and this is what proves it stays true.
     *
     * @return list<string>
     */
    public static function foldingCannotSynthesise(): array
    {
        $failures = [];
        $total = 0;

        for ($cp = 0; $cp <= 0x10FFFF; $cp++) {
            if ($cp >= 0xD800 && $cp <= 0xDFFF) {
                continue; // surrogates are not characters
            }
            $character = @mb_chr($cp, 'UTF-8');
            if ($character === false) {
                continue;
            }

            $expected = self::SINGLE_CHARACTER_CODES[$character] ?? null;
            $actual = LinkCode::normalise($character);
            if ($actual === $expected) {
                continue;
            }

            $total++;
            // The sweep is complete; only the REPORT is capped, because twenty
            // examples is enough to diagnose and a million lines is not a
            // report. The count below states what the cap hid.
            if (count($failures) < 20) {
                $failures[] = sprintf(
                    'U+%04X normalises to %s, expected %s. A character outside the alphabet '
                        . 'that normalises into it is a code somebody else can redeem.',
                    $cp,
                    $actual === null ? 'null' : "'{$actual}'",
                    $expected === null ? 'null' : "'{$expected}'"
                );
            }
        }

        if ($total > count($failures)) {
            $failures[] = ($total - count($failures)) . ' further code points also disagree '
                . '(the sweep covered every one; only this report is capped)';
        }

        return $failures;
    }

    public static function visible(string $value): string
    {
        $out = '';
        // Decoded as UTF-8 EXPLICITLY, even here. Under the hostile run the
        // internal encoding is not UTF-8, and a failure message that garbled the
        // input would describe a different string from the one that failed.
        $length = mb_strlen($value, 'UTF-8');
        for ($i = 0; $i < $length; $i++) {
            $character = mb_substr($value, $i, 1, 'UTF-8');
            $codepoint = mb_ord($character, 'UTF-8');
            $out .= ($codepoint === false || $codepoint < 0x20 || $codepoint > 0x7e)
                ? sprintf('\\u%04X', $codepoint === false ? 0xFFFD : $codepoint)
                : $character;
        }
        return $out;
    }
}
