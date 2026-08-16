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

use InvalidArgumentException;

/**
 * HMAC request signing, re-implemented.
 *
 * The canonical form is contract, not implementation detail:
 *
 *     <timestamp> LF <nonce> LF <body>
 *
 * encoded UTF-8, hex output lowercase. Every part of that has a reason, and the
 * reasons match the other implementation's because the golden vectors prove
 * the two agree.
 */
final class RequestSigner
{
    /**
     * The field separator.
     *
     * A newline, because it cannot appear in a timestamp or a nonce and so no
     * field boundary can be shifted. Concatenating without one would let
     * (12, "3x") and (123, "x") sign identical bytes -- a canonicalisation
     * collision, which is a signature forgery in disguise.
     */
    private const SEPARATOR = "\n";

    private function __construct()
    {
    }

    /**
     * The exact bytes that get signed.
     *
     * @throws InvalidArgumentException if the nonce is empty or contains the
     *     separator, which would make the canonical form ambiguous
     */
    public static function canonicalBytes(int $timestamp, string $nonce, ?string $body): string
    {
        if ($nonce === '') {
            throw new InvalidArgumentException('nonce must not be empty');
        }
        if (str_contains($nonce, self::SEPARATOR)) {
            throw new InvalidArgumentException(
                'nonce must not contain the field separator; it would make the canonical form '
                . 'ambiguous and allow two different requests to produce identical signed bytes'
            );
        }

        // The body may contain newlines freely: it is last, so no boundary can
        // be shifted by its content.
        return $timestamp . self::SEPARATOR . $nonce . self::SEPARATOR . ($body ?? '');
    }

    /**
     * Lowercase-hex HMAC-SHA256 over the canonical form.
     *
     * PHP strings are byte strings, so there is no encoding step here the way
     * there is in a language with a separate character type -- which means the
     * CALLER must hand this UTF-8. Anything that reaches a Flarum extension has
     * already been decoded as UTF-8 by Flarum, and the vector suite runs a
     * second time under a hostile locale to prove nothing here depends on the
     * default.
     */
    public static function sign(string $key, int $timestamp, string $nonce, ?string $body): string
    {
        if ($key === '') {
            throw new InvalidArgumentException('signing key must not be empty');
        }

        // Lowercase hex, stated rather than incidental: the other side pins it,
        // and "whichever case the library happens to produce" is not a contract.
        return hash_hmac('sha256', self::canonicalBytes($timestamp, $nonce, $body), $key);
    }

    /**
     * Constant-time comparison of a presented signature against the expected.
     *
     * hash_equals, never ===. String comparison returns early at the first
     * differing byte, and that timing difference is enough to recover a
     * signature byte by byte.
     */
    public static function verify(
        string $key,
        int $timestamp,
        string $nonce,
        ?string $body,
        ?string $presented
    ): bool {
        if ($presented === null || $presented === '') {
            return false;
        }

        try {
            $expected = self::sign($key, $timestamp, $nonce, $body);
        } catch (InvalidArgumentException) {
            // A malformed nonce cannot be verified. False rather than throwing:
            // the caller controls it, so it is a refusal, not a crash.
            return false;
        }

        return hash_equals($expected, $presented);
    }
}
