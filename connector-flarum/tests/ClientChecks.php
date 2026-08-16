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

use Soulbind\Flarum\Client\ArrayDecisionStore;
use Soulbind\Flarum\Client\DecisionCache;
use Soulbind\Flarum\Client\FailMode;
use Soulbind\Flarum\Client\OkOutcome;
use Soulbind\Flarum\Client\RefusedOutcome;
use Soulbind\Flarum\Client\SoulbindClient;
use Soulbind\Flarum\Client\Source;
use Soulbind\Flarum\Client\Transport;
use Soulbind\Flarum\Client\UnreachableOutcome;
use Soulbind\Flarum\Policy\Decision;
use Soulbind\Flarum\Policy\Effect;
use Soulbind\Flarum\Protocol\RequestSigner;

/**
 * The client: signing, the outage/refusal distinction, and the fallback order.
 *
 * None of it needs a socket, which is why {@see Transport} is an interface.
 */
final class ClientChecks
{
    private const NOW = 1_700_000_000;
    private const CREDENTIAL = 'test-credential';
    private const NONCE = 'fixed-nonce-for-tests';

    private function __construct()
    {
    }

    private static function client(Transport $transport, ?DecisionCache $cache = null): SoulbindClient
    {
        return new SoulbindClient(
            $transport,
            self::CREDENTIAL,
            $cache ?? new DecisionCache(),
            static fn (): int => self::NOW,
            static fn (): string => self::NONCE
        );
    }

    private static function allowPayload(int $ttl = 60): array
    {
        return [
            'effect' => 'allow',
            'reason' => 'requirements-met',
            'detail' => 'fine',
            'ttlSeconds' => $ttl,
            'missingKinds' => [],
        ];
    }

    /** @return list<string> */
    public static function requestIsWellFormedAndSigned(): array
    {
        $failures = [];

        $transport = FakeTransport::ok(self::allowPayload());
        self::client($transport)->decide('join', 'forum', 'u1');

        $body = $transport->lastBody ?? '';
        $decoded = json_decode($body, true);

        if (!is_array($decoded)) {
            return ['the request body was not JSON'];
        }
        foreach (['schema', 'op', 'id', 'payload'] as $field) {
            if (!array_key_exists($field, $decoded)) {
                $failures[] = "the request envelope is missing '{$field}'";
            }
        }
        if (($decoded['schema'] ?? null) !== SoulbindClient::SCHEMA) {
            $failures[] = 'the request did not carry the current schema version';
        }
        if (($decoded['op'] ?? null) !== 'decide') {
            $failures[] = "the operation was '" . ($decoded['op'] ?? 'absent') . "', not 'decide'";
        }

        $headers = $transport->lastHeaders;
        foreach ([
            'Authorization',
            'X-Soulbind-Timestamp',
            'X-Soulbind-Nonce',
            'X-Soulbind-Signature',
        ] as $header) {
            if (($headers[$header] ?? '') === '') {
                $failures[] = "the request is missing the {$header} header";
            }
        }

        // The signature must actually verify over the body that was sent -- not
        // merely be present. A signature over the wrong bytes is a header that
        // looks right in a log and is refused by core.
        $expected = RequestSigner::sign(
            self::CREDENTIAL,
            (int) ($headers['X-Soulbind-Timestamp'] ?? 0),
            $headers['X-Soulbind-Nonce'] ?? '',
            $body
        );
        if (($headers['X-Soulbind-Signature'] ?? '') !== $expected) {
            $failures[] = 'the signature does not verify over the body that was sent';
        }

        if (($headers['Authorization'] ?? '') !== 'Bearer ' . self::CREDENTIAL) {
            $failures[] = 'the credential is not presented as a bearer token';
        }

        return $failures;
    }

    /**
     * A nonce must not repeat, or the replay guard has nothing to record.
     *
     * @return list<string>
     */
    public static function noncesDoNotRepeat(): array
    {
        $transport = new FakeTransport(array_fill(
            0,
            50,
            (string) json_encode(['ok' => true, 'payload' => self::allowPayload()])
        ));
        // The REAL nonce source, not the fixed test one.
        $client = new SoulbindClient($transport, self::CREDENTIAL, new DecisionCache());

        $seen = [];
        for ($i = 0; $i < 50; $i++) {
            $client->decide('join', 'forum', 'u1');
            $seen[] = $transport->lastHeaders['X-Soulbind-Nonce'] ?? '';
        }

        $unique = count(array_unique($seen));
        if ($unique !== 50) {
            return [
                "50 requests produced only {$unique} distinct nonces. A repeated nonce is "
                . 'refused as a replay, and a predictable one is a replay window.',
            ];
        }
        if (in_array('', $seen, true)) {
            return ['a request went out with an empty nonce'];
        }

        return [];
    }

    /** @return list<string> */
    public static function anAnswerIsFreshAndCached(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $answer = self::client(FakeTransport::ok(self::allowPayload(60)), $cache)
            ->decide('join', 'forum', 'u1');

        if (!$answer->decision->isAllowed() || $answer->source !== Source::FRESH) {
            $failures[] = 'core answered allow and the client did not report a fresh allow';
        }
        if ($cache->cached('join', 'forum:u1', self::NOW) === null) {
            $failures[] = 'a fresh answer was not cached, so the cache can never help during '
                . 'an outage';
        }

        return $failures;
    }

    /**
     * The rule this whole type hierarchy exists for.
     *
     * @return list<string>
     */
    public static function aRefusalNeverConsultsTheCache(): array
    {
        $failures = [];

        // A cached ALLOW is sitting there, and core refuses. The refusal wins.
        $cache = new DecisionCache();
        $cache->store(
            'join',
            'forum:u1',
            new Decision(Effect::ALLOW, 'requirements-met', 'earlier', 600),
            self::NOW
        );

        $answer = self::client(
            FakeTransport::refusing('missing-capability', 'this connector may not decide'),
            $cache
        )->decide('join', 'forum', 'u1');

        if ($answer->decision->isAllowed()) {
            $failures[] = 'a refusal was softened into an allow by the cache. If core says this '
                . 'connector lacks the capability, serving a cached allow uses a stale answer '
                . 'to overrule a current one.';
        }
        if ($answer->source === Source::CACHED) {
            $failures[] = 'a refusal was reported as a cached answer';
        }
        if ($answer->decision->reason !== 'missing-capability') {
            $failures[] = "the refusal lost core's reason; it became '"
                . $answer->decision->reason . "'. An operator debugging this needs to see "
                . 'missing-capability, not a generic denial.';
        }

        // Fail-OPEN must not rescue a refusal either -- that is the same
        // mistake with a louder failure mode.
        $openCache = new DecisionCache(FailMode::OPEN);
        $openAnswer = self::client(
            FakeTransport::refusing('missing-capability', 'nope'),
            $openCache
        )->decide('join', 'forum', 'u1');

        if ($openAnswer->decision->isAllowed()) {
            $failures[] = 'a refusal reached the fail mode and was allowed. A refusal is not an '
                . 'outage: core answered, and the answer was no.';
        }

        // And a refusal must not be cached against the subject: it is about
        // this connector's standing, not this person's.
        $store = new ArrayDecisionStore(static fn (): int => self::NOW);
        $freshCache = new DecisionCache(FailMode::CLOSED, $store);
        self::client(FakeTransport::refusing('missing-capability', 'nope'), $freshCache)
            ->decide('join', 'forum', 'u1');
        if ($store->size() !== 0) {
            $failures[] = 'a refusal was cached against the subject, so a credential problem '
                . 'would keep denying one person after it was fixed';
        }

        // And the refusal it hands back must be UNCACHEABLE on its face.
        //
        // Today the client never stores a refusal, so this TTL is unobservable
        // from outside -- which is exactly why it is asserted rather than left
        // to be true by accident. The value only starts mattering the moment a
        // caller decides to cache what decide() returns, and at that moment
        // nobody will re-derive why a refusal must not be kept: a refusal is
        // about this CONNECTOR's standing, not this person's, so caching it
        // against the subject would keep denying one person after the
        // credential was fixed.
        $refusal = self::client(
            FakeTransport::refusing('missing-capability', 'nope'),
            new DecisionCache()
        )->decide('join', 'forum', 'u1');

        if ($refusal->decision->ttlSeconds !== 0) {
            $failures[] = 'a refusal came back with a ttl of ' . $refusal->decision->ttlSeconds
                . '. It must be 0, so that a caller which caches what decide() returns cannot '
                . 'hold a credential problem against one subject.';
        }

        return $failures;
    }

    /**
     * Anything that is not a protocol envelope is an OUTAGE, not a refusal.
     *
     * @return list<string>
     */
    public static function nonEnvelopeResponsesAreOutages(): array
    {
        $failures = [];

        $notEnvelopes = [
            'a proxy error page' => '<html><body><h1>502 Bad Gateway</h1></body></html>',
            'an empty body' => '',
            'a JSON array' => '[1,2,3]',
            'JSON without an ok field' => '{"payload":{"effect":"allow"}}',
            'a bare string' => '"maintenance"',
            'truncated JSON' => '{"ok":tr',
        ];

        foreach ($notEnvelopes as $what => $response) {
            $outcome = self::client(FakeTransport::replying($response))->call('decide');
            if (!$outcome instanceof UnreachableOutcome) {
                $failures[] = "{$what} was treated as " . get_class($outcome)
                    . '. It is an outage: core never said no, because core never saw it. '
                    . 'Reading it as a refusal turns a captive portal into a policy decision.';
            }
        }

        // A genuine envelope must still parse, or the rule above is satisfied
        // by treating everything as an outage.
        $ok = self::client(FakeTransport::ok(self::allowPayload()))->call('decide');
        if (!$ok instanceof OkOutcome) {
            $failures[] = 'a well-formed envelope was not recognised; treating everything as an '
                . 'outage would pass every assertion above';
        }
        $refused = self::client(FakeTransport::refusing('unknown-code', 'no'))->call('decide');
        if (!$refused instanceof RefusedOutcome) {
            $failures[] = 'a well-formed refusal was not recognised as a refusal';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function outagesFallBackToCacheThenFailMode(): array
    {
        $failures = [];

        // Cached and live: the cache answers.
        $cache = new DecisionCache();
        $cache->store(
            'join',
            'forum:u1',
            new Decision(Effect::ALLOW, 'requirements-met', 'earlier', 600),
            self::NOW
        );
        $answer = self::client(FakeTransport::failing(), $cache)->decide('join', 'forum', 'u1');
        if (!$answer->decision->isAllowed() || $answer->source !== Source::CACHED) {
            $failures[] = 'an outage did not use the live cached decision';
        }

        // Nothing cached: the fail mode, which defaults to denying.
        $answer = self::client(FakeTransport::failing())->decide('join', 'forum', 'nobody');
        if ($answer->decision->isAllowed() || $answer->source !== Source::FAIL_MODE) {
            $failures[] = 'an outage with an empty cache did not fail closed';
        }
        if (($answer->decision->detail ?? '') !== DecisionCache::FAIL_CLOSED_MESSAGE) {
            $failures[] = 'the outage denial did not use the message that blames the system';
        }

        return $failures;
    }
}
