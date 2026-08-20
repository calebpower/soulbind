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

namespace Soulbind\Flarum\Webhook;

use Soulbind\Flarum\Protocol\RequestSigner;

/**
 * Verifies an inbound webhook.
 *
 * The mirror image of the other side's request verifier, and deliberately the
 * same rules in the same order: presence, then the clock, then replay, then the
 * signature.
 *
 * Replay protection is two halves and needs both. The timestamp window bounds
 * how long a captured delivery stays interesting; the nonce store stops it
 * being used twice inside that window. Either alone is not replay protection.
 */
final class WebhookVerifier
{
    /** Matches the other side's default. A window is not a place to differ. */
    public const DEFAULT_WINDOW_SECONDS = 300;

    public function __construct(
        private readonly string $secret,
        private readonly NonceStore $nonces,
        private readonly int $windowSeconds = self::DEFAULT_WINDOW_SECONDS
    ) {
    }

    /**
     * @param array<string, string> $headers case-insensitive on the wire, so
     *     they are folded before lookup rather than trusted to arrive in one
     *     casing. A header that works behind one web server and not another is
     *     the worst kind of deployment bug.
     */
    public function verify(array $headers, string $body, int $now): Verdict
    {
        if ($this->secret === '') {
            return Verdict::NOT_CONFIGURED;
        }

        $folded = [];
        foreach ($headers as $name => $value) {
            $folded[strtolower($name)] = $value;
        }

        $timestampHeader = $folded['x-soulbind-timestamp'] ?? null;
        $nonce = $folded['x-soulbind-nonce'] ?? null;
        $signature = $folded['x-soulbind-signature'] ?? null;

        if ($timestampHeader === null || $nonce === null || $signature === null
            || $nonce === '' || $signature === '') {
            return Verdict::MALFORMED;
        }

        $trimmed = trim($timestampHeader);
        // A strict integer test. is_numeric() would accept "1.7e9" and "0x10",
        // and (int) would silently read "300abc" as 300 -- turning a malformed
        // header into a timestamp that might land inside the window.
        if ($trimmed === '' || preg_match('/^-?\d+$/', $trimmed) !== 1) {
            return Verdict::MALFORMED;
        }
        $timestamp = (int) $trimmed;

        // Both directions. A timestamp far in the FUTURE is refused too --
        // otherwise a captured delivery could be given a distant timestamp and
        // stay replayable indefinitely, which is the window with the lid off.
        if (abs($now - $timestamp) > $this->windowSeconds) {
            return Verdict::STALE_TIMESTAMP;
        }

        // The nonce is recorded BEFORE the signature is checked, matching the
        // other side. It reads backwards and is not: recording only verified
        // nonces would let an attacker replay a captured delivery as many times
        // as they liked while the signature check passed each time, because
        // nothing would remember the first. The store is bounded and fails
        // closed, so recording unverified nonces cannot be used to exhaust it.
        // 2W + 1, and the +1 is not padding.
        //
        // A delivery stamped t is acceptable for now in [t-W, t+W] -- a span 2W
        // wide, inclusive at BOTH ends. A nonce first seen at the earliest of
        // those, t-W, must therefore still be remembered at the latest, t+W.
        // With a retention of exactly 2W it expires at t-W+2W = t+W, and the
        // store sweeps entries whose expiry is `<= $now`, so at t+W it is
        // forgotten -- while the timestamp check still accepts. A captured
        // delivery replayed at the final instant of its own window went
        // through.
        //
        // Found by mutation coverage rather than by reading: nothing here
        // asserted the retention at all, so `* 1`, `* 3` and `/ 2` all survived,
        // and writing the test that killed them is what surfaced the off-by-one
        // in the original `* 2`.
        if (!$this->nonces->recordIfNew($nonce, $now, $this->windowSeconds * 2 + 1)) {
            return Verdict::REPLAYED_NONCE;
        }

        // The signature is computed over an attacker-controlled nonce, and the
        // signer REFUSES a nonce carrying the field separator -- that refusal is
        // what keeps the canonical form unambiguous. Uncaught, it would leave
        // this endpoint answering 500 to a hostile header, which is a crash
        // reported as a server fault when it is really a rejected request.
        try {
            $expected = RequestSigner::sign($this->secret, $timestamp, $nonce, $body);
        } catch (\InvalidArgumentException) {
            return Verdict::MALFORMED;
        }

        // hash_equals, never ===. String comparison short-circuits on the first
        // differing byte, which leaks how much of a guess was right.
        if (!hash_equals($expected, $signature)) {
            return Verdict::BAD_SIGNATURE;
        }

        return Verdict::ACCEPTED;
    }
}
