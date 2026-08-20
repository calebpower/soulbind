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

use Soulbind\Flarum\Protocol\RequestSigner;
use Soulbind\Flarum\Webhook\InMemoryNonceStore;
use Soulbind\Flarum\Webhook\Verdict;
use Soulbind\Flarum\Webhook\WebhookPayload;
use Soulbind\Flarum\Webhook\WebhookVerifier;

/**
 * The inbound webhook: signature, clock, replay.
 *
 * This endpoint is the only part of the connector an unauthenticated caller can
 * reach, so every check here is about what happens when the input is hostile
 * rather than merely wrong.
 */
final class WebhookChecks
{
    private const NOW = 1_700_000_000;
    private const SECRET = 'webhook-secret';
    private const BODY = '{"event":"identity.linked","identityRef":"forum:u1"}';

    private function __construct()
    {
    }

    private static function verifier(?InMemoryNonceStore $store = null): WebhookVerifier
    {
        return new WebhookVerifier(self::SECRET, $store ?? new InMemoryNonceStore());
    }

    /** @return array<string, string> */
    private static function signedHeaders(
        string $nonce = 'nonce-1',
        ?int $timestamp = null,
        string $body = self::BODY,
        string $secret = self::SECRET
    ): array {
        $timestamp ??= self::NOW;
        return [
            'X-Soulbind-Timestamp' => (string) $timestamp,
            'X-Soulbind-Nonce' => $nonce,
            'X-Soulbind-Signature' => RequestSigner::sign($secret, $timestamp, $nonce, $body),
        ];
    }

    /**
     * @return list<string>
     *
     * The window's edge, on the accepting side. Nothing asserted it, so moving
     * the comparison from `>` to `>=` -- which refuses a delivery that is
     * exactly as old as the window permits -- passed every check here.
     */
    public static function aTimestampAtTheWindowEdgeIsAccepted(): array
    {
        $problems = [];
        $window = 60;
        foreach ([-$window, $window] as $offset) {
            $stamp = self::NOW + $offset;
            $verifier = new WebhookVerifier(self::SECRET, new InMemoryNonceStore(), $window);
            $verdict = $verifier->verify(
                self::signedHeaders('edge-' . $offset, $stamp),
                self::BODY,
                self::NOW
            );
            if ($verdict !== Verdict::ACCEPTED) {
                $problems[] = 'a delivery exactly ' . abs($offset) . 's from now, with a '
                    . $window . 's window, was refused as ' . $verdict->value
                    . '. The window is inclusive at both ends; a delivery at the edge is '
                    . 'inside it.';
            }
        }
        return $problems;
    }

    /**
     * @return list<string>
     *
     * Whitespace around a header value is ordinary -- proxies and hand-written
     * clients add it. The trim was removable with nothing failing.
     */
    public static function aPaddedTimestampHeaderIsAccepted(): array
    {
        $headers = self::signedHeaders('padded-1');
        $headers['X-Soulbind-Timestamp'] = "  " . $headers['X-Soulbind-Timestamp'] . " \n";

        $verdict = self::verifier()->verify($headers, self::BODY, self::NOW);
        if ($verdict !== Verdict::ACCEPTED) {
            return [
                'a timestamp header padded with whitespace was refused as ' . $verdict->value
                . '. The value is correct; only its surroundings are not.',
            ];
        }
        return [];
    }

    /**
     * @return list<string>
     *
     * The retention property, and the one that found a real defect rather than
     * a missing test.
     *
     * A delivery stamped t is acceptable for now in [t-W, t+W]. Seen at the
     * earliest of those it must still be refused at the latest. With retention
     * of exactly 2W the nonce expired at precisely t+W and the store sweeps at
     * `<= $now`, so the replay went through at the final instant of its own
     * window.
     */
    public static function aNonceOutlastsItsWholeWindow(): array
    {
        $window = 60;
        $store = new InMemoryNonceStore();
        $verifier = new WebhookVerifier(self::SECRET, $store, $window);

        // Stamped NOW, delivered at the earliest instant that stamp is valid.
        $headers = self::signedHeaders('long-lived', self::NOW);
        $first = $verifier->verify($headers, self::BODY, self::NOW - $window);
        if ($first !== Verdict::ACCEPTED) {
            return [
                'a delivery at the earliest acceptable instant was refused as '
                . $first->value . ', so the replay below would prove nothing.',
            ];
        }

        // The same delivery, replayed at the LAST instant it is still valid.
        $replay = $verifier->verify($headers, self::BODY, self::NOW + $window);
        if ($replay !== Verdict::REPLAYED_NONCE) {
            return [
                'a captured delivery was replayed at the last instant of its own window '
                . 'and came back ' . $replay->value . ' rather than replayed-nonce. The '
                . 'nonce is forgotten while the timestamp is still accepted, which is a '
                . 'replay window with no lid on it.',
            ];
        }
        return [];
    }

    /**
     * @return list<string>
     *
     * An entry whose expiry is exactly now is expired. Nothing asserted it, so
     * the sweep's comparison could be moved off its boundary freely.
     */
    public static function anEntryExpiringExactlyNowIsForgotten(): array
    {
        $store = new InMemoryNonceStore();
        $store->recordIfNew('boundary', self::NOW, 30);

        if ($store->recordIfNew('boundary', self::NOW + 29, 30)) {
            return ['a nonce was forgotten a second before its ttl elapsed'];
        }
        if (!$store->recordIfNew('boundary', self::NOW + 30, 30)) {
            return [
                'a nonce whose expiry is exactly now was still remembered. Expiry at now '
                . 'means expired; keeping it makes the effective ttl one second longer '
                . 'than every caller computed.',
            ];
        }
        return [];
    }

    /**
     * @return list<string>
     *
     * Full means refuse, never evict -- evicting the oldest entry is how a
     * replay gets in. The threshold is 100,000 away in production and nothing
     * reached it.
     */
    public static function aFullStoreRefusesRatherThanEvicting(): array
    {
        $store = new InMemoryNonceStore(4);
        for ($i = 0; $i < 4; $i++) {
            if (!$store->recordIfNew('n' . $i, self::NOW, 30)) {
                return ['the store refused a nonce before it was full'];
            }
        }

        if ($store->recordIfNew('overflow', self::NOW, 30)) {
            return [
                'a full store accepted another nonce instead of failing closed. An '
                . 'unauthenticated caller fills this store, so unbounded growth is a '
                . 'memory-exhaustion path they control.',
            ];
        }

        // And the refusal is not permanent: once the entries expire it recovers.
        if (!$store->recordIfNew('after-expiry', self::NOW + 31, 30)) {
            return ['the store never recovered after its entries expired'];
        }
        return [];
    }

    /** @return list<string> */
    public static function aProperlySignedDeliveryIsAccepted(): array
    {
        $verdict = self::verifier()->verify(self::signedHeaders(), self::BODY, self::NOW);
        if ($verdict !== Verdict::ACCEPTED) {
            return [
                'a correctly signed, fresh, first-time delivery was refused as '
                . $verdict->value . '. Every other check here is worthless if the happy path '
                . 'does not work -- a verifier that refuses everything would satisfy them all.',
            ];
        }
        return [];
    }

    /** @return list<string> */
    public static function missingHeadersAreMalformed(): array
    {
        $failures = [];

        $full = self::signedHeaders();
        foreach (array_keys($full) as $drop) {
            $headers = $full;
            unset($headers[$drop]);
            $verdict = self::verifier()->verify($headers, self::BODY, self::NOW);
            if ($verdict !== Verdict::MALFORMED) {
                $failures[] = "a delivery with no {$drop} was {$verdict->value}, not malformed";
            }
        }

        // Present but empty is the same as absent, and is the case a naive
        // isset() check misses.
        foreach (['X-Soulbind-Nonce', 'X-Soulbind-Signature'] as $blank) {
            $headers = $full;
            $headers[$blank] = '';
            if (self::verifier()->verify($headers, self::BODY, self::NOW) !== Verdict::MALFORMED) {
                $failures[] = "an empty {$blank} was not treated as missing";
            }
        }

        return $failures;
    }

    /**
     * A timestamp header is an integer or it is nothing.
     *
     * @return list<string>
     */
    public static function nonIntegerTimestampsAreMalformed(): array
    {
        $failures = [];

        // Each of these is accepted by is_numeric() or silently truncated by a
        // cast to int -- and each would then land INSIDE the window.
        $hostile = ['1700000000abc', '1.7e9', '0x654', ' ', '', 'now', '+1700000000', '1_700'];

        foreach ($hostile as $value) {
            $headers = self::signedHeaders();
            $headers['X-Soulbind-Timestamp'] = $value;
            $verdict = self::verifier()->verify($headers, self::BODY, self::NOW);
            if ($verdict !== Verdict::MALFORMED) {
                $failures[] = "the timestamp '{$value}' was {$verdict->value}, not malformed. "
                    . 'A cast would read it as a number and it would land inside the window.';
            }
        }

        return $failures;
    }

    /**
     * The window is closed at BOTH ends.
     *
     * @return list<string>
     */
    public static function staleAndFutureTimestampsAreRefused(): array
    {
        $failures = [];
        $window = WebhookVerifier::DEFAULT_WINDOW_SECONDS;

        $cases = [
            'far in the past' => self::NOW - $window - 1,
            'far in the future' => self::NOW + $window + 1,
        ];
        foreach ($cases as $what => $timestamp) {
            $verdict = self::verifier()->verify(
                self::signedHeaders('n-' . $timestamp, $timestamp),
                self::BODY,
                self::NOW
            );
            if ($verdict !== Verdict::STALE_TIMESTAMP) {
                $failures[] = "a timestamp {$what} was {$verdict->value}, not refused. A "
                    . 'future timestamp left unchecked is the window with the lid off: a '
                    . 'captured delivery stays replayable indefinitely.';
            }
        }

        // Inside the window, both directions, must still be accepted.
        foreach ([self::NOW - $window + 1, self::NOW + $window - 1] as $timestamp) {
            $verdict = self::verifier()->verify(
                self::signedHeaders('ok-' . $timestamp, $timestamp),
                self::BODY,
                self::NOW
            );
            if ($verdict !== Verdict::ACCEPTED) {
                $failures[] = "a timestamp inside the window ({$timestamp}) was refused as "
                    . $verdict->value . '; the window is narrower than it claims';
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function aReplayedNonceIsRefused(): array
    {
        $failures = [];

        $store = new InMemoryNonceStore();
        $verifier = self::verifier($store);
        $headers = self::signedHeaders('single-use');

        if ($verifier->verify($headers, self::BODY, self::NOW) !== Verdict::ACCEPTED) {
            return ['the first delivery was not accepted, so the replay check proves nothing'];
        }

        $second = $verifier->verify($headers, self::BODY, self::NOW);
        if ($second !== Verdict::REPLAYED_NONCE) {
            $failures[] = "replaying a byte-identical delivery was {$second->value}. The "
                . 'signature is valid on a replay -- that is what makes it a replay -- so the '
                . 'nonce is the only thing standing between a captured delivery and unlimited '
                . 'reuse.';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function aTamperedBodyOrSignatureIsRefused(): array
    {
        $failures = [];

        // Right signature, different body.
        $verdict = self::verifier()->verify(
            self::signedHeaders('t1'),
            '{"event":"identity.unlinked","identityRef":"forum:someone-else"}',
            self::NOW
        );
        if ($verdict !== Verdict::BAD_SIGNATURE) {
            $failures[] = "a body swapped under a valid signature was {$verdict->value}. If the "
                . 'signature does not cover the body, anybody who has seen one delivery can '
                . 'send any payload they like.';
        }

        // Wrong secret.
        $verdict = self::verifier()->verify(
            self::signedHeaders('t2', null, self::BODY, 'not-the-secret'),
            self::BODY,
            self::NOW
        );
        if ($verdict !== Verdict::BAD_SIGNATURE) {
            $failures[] = "a delivery signed with the wrong secret was {$verdict->value}";
        }

        // Garbage signature.
        $headers = self::signedHeaders('t3');
        $headers['X-Soulbind-Signature'] = str_repeat('0', 64);
        if (self::verifier()->verify($headers, self::BODY, self::NOW) !== Verdict::BAD_SIGNATURE) {
            $failures[] = 'an all-zero signature was not refused';
        }

        return $failures;
    }

    /**
     * A hostile header must not become a crash.
     *
     * @return list<string>
     */
    public static function hostileInputDoesNotThrow(): array
    {
        $failures = [];

        $hostile = [
            'a nonce carrying the field separator' => "a\nb",
            'a nonce that is only a separator' => "\n",
            'a nonce with a null byte' => "a\0b",
            'a very long nonce' => str_repeat('x', 100_000),
            'an astral-plane nonce' => "\u{1F600}\u{1F4A9}",
            'an RTL nonce' => "\u{202E}gnirts",
        ];

        foreach ($hostile as $what => $nonce) {
            $headers = [
                'X-Soulbind-Timestamp' => (string) self::NOW,
                'X-Soulbind-Nonce' => $nonce,
                'X-Soulbind-Signature' => str_repeat('a', 64),
            ];
            try {
                $verdict = self::verifier()->verify($headers, self::BODY, self::NOW);
            } catch (\Throwable $e) {
                $failures[] = "{$what} threw " . get_class($e) . '. An unauthenticated caller '
                    . 'can set this header, so a throw here is a 500 anybody can trigger.';
                continue;
            }
            if ($verdict->isAccepted()) {
                $failures[] = "{$what} was ACCEPTED";
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function anUnconfiguredEndpointAcceptsNothing(): array
    {
        $verifier = new WebhookVerifier('', new InMemoryNonceStore());
        $verdict = $verifier->verify(self::signedHeaders(), self::BODY, self::NOW);

        if ($verdict !== Verdict::NOT_CONFIGURED) {
            return [
                "an endpoint with no secret answered {$verdict->value}. It must accept nothing "
                . 'until one is set -- and say so, rather than reporting the delivery as '
                . 'malformed and sending an operator to inspect a delivery that is fine.',
            ];
        }
        return [];
    }

    /**
     * Headers are case-insensitive on the wire.
     *
     * @return list<string>
     */
    public static function headerLookupIsCaseInsensitive(): array
    {
        $failures = [];

        foreach ([
            'lowercase' => strtolower(...),
            'uppercase' => strtoupper(...),
        ] as $what => $fold) {
            $headers = [];
            foreach (self::signedHeaders('case-' . $what) as $name => $value) {
                $headers[$fold($name)] = $value;
            }
            $verdict = self::verifier()->verify($headers, self::BODY, self::NOW);
            if ($verdict !== Verdict::ACCEPTED) {
                $failures[] = "{$what} header names were refused as {$verdict->value}. HTTP "
                    . 'header names are case-insensitive, and a webhook that works behind one '
                    . 'web server and not another is the worst kind of deployment bug.';
            }
        }

        return $failures;
    }

    /**
     * Nothing this endpoint refuses is a 5xx.
     *
     * @return list<string>
     */
    public static function refusalsAreNeverServerErrors(): array
    {
        $failures = [];

        foreach (Verdict::cases() as $verdict) {
            $status = $verdict->httpStatus();
            if ($status >= 500) {
                $failures[] = "{$verdict->value} answers {$status}. A refused webhook is this "
                    . 'endpoint working correctly; answering 5xx makes core retry a delivery '
                    . 'that will never be accepted, forever.';
            }
            if ($status < 200 || $status > 499) {
                $failures[] = "{$verdict->value} answers an implausible {$status}";
            }
        }

        if (Verdict::ACCEPTED->httpStatus() !== 200) {
            $failures[] = 'an accepted delivery does not answer 200';
        }

        // A replay is 200 on purpose: the delivery it duplicates was already
        // accepted, so there is nothing for an at-least-once sender to usefully
        // retry, and 4xx would make correct behaviour look like a fault.
        if (Verdict::REPLAYED_NONCE->httpStatus() !== 200) {
            $failures[] = 'a replayed delivery does not answer 200; an at-least-once sender '
                . 'behaving exactly as designed would look broken to its operator';
        }

        return $failures;
    }

    /**
     * Reading which identities a delivery is about.
     *
     * This runs only on deliveries that already verified, so the input is
     * signed by core -- but "signed by core" is not "shaped the way this build
     * expects". A newer core can send a field this version has never seen, and
     * the right answer is to take what is recognised and ignore the rest, never
     * to throw inside a webhook handler.
     *
     * @return list<string>
     */
    public static function payloadReadingIsTotal(): array
    {
        $failures = [];

        $cases = [
            'a single identityRef' =>
                ['{"payload":{"identityRef":"forum:u1"}}', ['forum:u1']],
            'a list of them' =>
                ['{"payload":{"identityRefs":["forum:u1","game:u2"]}}', ['forum:u1', 'game:u2']],
            'the kind/id pair spelling' =>
                ['{"payload":{"platformKind":"forum","platformId":"u1"}}', ['forum:u1']],
            'no envelope, just the payload' =>
                ['{"identityRef":"forum:u1"}', ['forum:u1']],
            'duplicates collapsed' =>
                ['{"payload":{"identityRef":"forum:u1","identityRefs":["forum:u1"]}}',
                 ['forum:u1']],
            'blank entries dropped' =>
                ['{"payload":{"identityRefs":["","  ","forum:u1"]}}', ['forum:u1']],
        ];

        foreach ($cases as $what => [$body, $expected]) {
            $actual = WebhookPayload::affectedIdentities($body);
            if ($actual !== $expected) {
                $failures[] = "{$what}: got [" . implode(', ', $actual) . '], expected ['
                    . implode(', ', $expected) . ']';
            }
        }

        // Nothing here may throw, and nothing may invent an identity.
        $hostile = [
            'not JSON at all' => 'hello',
            'an empty body' => '',
            'a JSON array' => '[1,2,3]',
            'null' => 'null',
            'deeply nested rubbish' => '{"payload":{"identityRef":{"nested":true}}}',
            'a numeric ref' => '{"payload":{"identityRef":12345}}',
            'refs that are not strings' => '{"payload":{"identityRefs":[1,true,null]}}',
            'an unknown event shape' => '{"payload":{"somethingNew":"forum:u1"}}',
        ];
        foreach ($hostile as $what => $body) {
            try {
                $result = WebhookPayload::affectedIdentities($body);
            } catch (\Throwable $e) {
                $failures[] = "{$what} threw " . get_class($e)
                    . '. A throw inside a webhook handler is a 500, and core will retry it.';
                continue;
            }
            if ($result !== []) {
                $failures[] = "{$what} produced identities out of nothing: ["
                    . implode(', ', $result) . ']';
            }
        }

        // Bounded. A delivery claiming ten thousand identities must not become
        // ten thousand cache writes on a page load.
        $many = json_encode([
            'payload' => ['identityRefs' => array_map(
                static fn (int $i): string => "forum:u{$i}",
                range(1, 10_000)
            )],
        ]);
        $bounded = WebhookPayload::affectedIdentities((string) $many);
        if (count($bounded) > 64) {
            $failures[] = 'a delivery naming 10,000 identities produced ' . count($bounded)
                . ' invalidations. Unbounded, one signed delivery is a way to make the forum '
                . 'do arbitrary work.';
        }

        return $failures;
    }

    /**
     * Secrets are compared with hash_equals, never with == or ===.
     *
     * A SOURCE check, not a behavioural one, and deliberately so: swapping
     * hash_equals for === changes no observable behaviour whatsoever. Every
     * assertion in this file passes either way. What changes is that ===
     * short-circuits on the first differing byte, so the time it takes leaks how
     * much of a guess was right -- and a leak nobody can observe from the
     * outside is exactly the kind that survives a test suite indefinitely.
     *
     * The same reasoning as the other side's static guards: when the property
     * cannot be asserted by running the code, assert it against the code.
     *
     * Narrowing, stated: this reads `src/` only, and only flags comparisons
     * whose operands are *named* like secrets. A secret held in a variable
     * called something else is not caught. That is a real gap and not a
     * pretended one -- the check is a backstop for the obvious mistake, not a
     * proof of absence.
     *
     * @return list<string>
     */
    public static function secretsAreComparedInConstantTime(): array
    {
        $failures = [];
        $root = dirname(__DIR__) . '/src';

        $secretish = 'signature|expected|secret|credential|hmac|digest|token';

        $iterator = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($root));
        $scanned = 0;

        foreach ($iterator as $file) {
            if (!$file->isFile() || $file->getExtension() !== 'php') {
                continue;
            }
            $scanned++;
            $lines = explode("\n", (string) file_get_contents($file->getPathname()));
            foreach ($lines as $number => $line) {
                $code = preg_replace('#//.*$#', '', $line) ?? $line;
                if (preg_match('/^\s*\*/', $code) === 1) {
                    continue; // a docblock line, not code
                }
                if (preg_match(
                    '/\$(' . $secretish . ')\w*\s*(===|==|!==|!=)|(===|==|!==|!=)\s*\$('
                        . $secretish . ')\w*/i',
                    $code
                ) === 1) {
                    // A presence test is not a secret comparison. Comparing
                    // against a literal null or empty string reveals only
                    // whether the value is there, which the caller already
                    // knows -- and refusing an absent signature, or an
                    // unconfigured secret, is something this code must keep
                    // doing. Exempted narrowly: the literals null and '' and
                    // nothing else, so comparing a secret to any actual value
                    // still fires.
                    if (preg_match("/(===|!==|==|!=)\s*(null|''|\"\")/i", $code) === 1) {
                        continue;
                    }
                    $failures[] = sprintf(
                        '%s:%d compares a secret with an equality operator -> %s',
                        basename((string) $file->getPathname()),
                        $number + 1,
                        trim($code)
                    );
                }
            }
        }

        if ($scanned === 0) {
            $failures[] = 'the guard scanned no files at all, so it proves nothing. A guard '
                . 'that silently matches nothing is worse than no guard: it reads as coverage.';
        }

        return $failures;
    }

    /**
     * The nonce store is bounded, and full means refuse rather than evict.
     *
     * @return list<string>
     */
    public static function theNonceStoreFailsClosedWhenFull(): array
    {
        $failures = [];

        $store = new InMemoryNonceStore();
        if (!$store->recordIfNew('first', self::NOW, 600)) {
            return ['a fresh store refused the first nonce'];
        }
        if ($store->recordIfNew('first', self::NOW, 600)) {
            $failures[] = 'the store accepted the same nonce twice';
        }

        // Expiry frees space, so a long-running site is not permanently full.
        if (!$store->recordIfNew('first', self::NOW + 601, 600)) {
            $failures[] = 'an expired nonce was still remembered, so the store only ever grows';
        }

        if (InMemoryNonceStore::MAX_ENTRIES <= 0) {
            $failures[] = 'the store is unbounded; an unauthenticated caller can put entries '
                . 'in it, and that is a way to exhaust memory';
        }

        return $failures;
    }
}
