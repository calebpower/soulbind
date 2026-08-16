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

namespace Soulbind\Flarum\Client;

use JsonException;
use Soulbind\Flarum\Policy\Decision;
use Soulbind\Flarum\Protocol\RequestSigner;

/**
 * This connector's half of the protocol.
 *
 * Everything here is testable without a socket: request construction, signing,
 * the distinction between a refusal and an outage, cache population, and the
 * fail-mode fallback. That is the point of the {@see Transport} seam -- a test
 * that needs a network is a test that does not get run.
 */
final class SoulbindClient
{
    /** The version this build speaks. Mirrors the other side's SchemaVersion. */
    public const SCHEMA = 1;

    /** @var callable(): int */
    private $clock;

    /**
     * @param callable(): int|null $clock seconds since the epoch; injectable so
     *     expiry and timestamp behaviour are testable without waiting
     * @param callable(): string|null $nonces injectable so a test can assert the
     *     signature over a known nonce rather than a random one
     */
    public function __construct(
        private readonly Transport $transport,
        private readonly string $credential,
        private readonly DecisionCache $cache,
        ?callable $clock = null,
        private $nonces = null
    ) {
        $this->clock = $clock ?? static fn (): int => time();
        $this->nonces = $nonces ?? static function (): string {
            // random_bytes, not uniqid or mt_rand: a predictable nonce is a
            // replay window, and the replay guard on the other side is only as
            // good as the unpredictability of what it records.
            return bin2hex(random_bytes(16));
        };
    }

    public function cache(): DecisionCache
    {
        return $this->cache;
    }

    /** Calls an operation. Signs every request; there is one call path, not two. */
    public function call(string $operation, array $payload = []): Outcome
    {
        try {
            $body = json_encode([
                'schema' => self::SCHEMA,
                'op' => $operation,
                'id' => ($this->nonces)(),
                'payload' => (object) $payload,
            ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        } catch (JsonException $e) {
            // A request this connector could not even build is a programming
            // error here, not an outage. Reporting it as unreachable would send
            // an operator to check the network.
            throw new \LogicException("cannot build a {$operation} request", 0, $e);
        }

        $timestamp = ($this->clock)();
        $nonce = ($this->nonces)();

        $headers = [
            'Authorization' => 'Bearer ' . $this->credential,
            'X-Soulbind-Timestamp' => (string) $timestamp,
            'X-Soulbind-Nonce' => $nonce,
            'X-Soulbind-Signature' => RequestSigner::sign(
                $this->credential,
                $timestamp,
                $nonce,
                $body
            ),
            'Content-Type' => 'application/json; charset=utf-8',
        ];

        try {
            $responseBody = $this->transport->send($body, $headers);
        } catch (TransportException $e) {
            return new UnreachableOutcome($e->getMessage());
        }

        try {
            $root = json_decode($responseBody, true, 32, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return new UnreachableOutcome('the response could not be parsed');
        }

        if (!is_array($root) || !array_key_exists('ok', $root)) {
            // A response that is not an envelope means something between here
            // and core is answering -- a proxy error page, a captive portal, a
            // maintenance splash. That is an outage, not a refusal: core never
            // said no, because core never saw it.
            return new UnreachableOutcome('the response was not a protocol envelope');
        }

        if ($root['ok'] === true) {
            $payload = $root['payload'] ?? [];
            return new OkOutcome(is_array($payload) ? $payload : []);
        }

        $error = is_array($root['error'] ?? null) ? $root['error'] : [];

        return new RefusedOutcome(
            is_string($error['code'] ?? null) ? $error['code'] : 'internal',
            is_string($error['message'] ?? null) ? $error['message'] : ''
        );
    }

    /**
     * Asks whether an identity may pass a gate, with the cache and fail mode
     * behind it.
     *
     * Ask core; on an answer, cache it and return it; on an outage, use an
     * unexpired cached decision if there is one, and otherwise let the fail mode
     * decide.
     *
     * A **refusal** is not an outage and does not reach the fail mode. If core
     * says this connector lacks the capability, falling back to a cached allow
     * would use a stale answer to overrule a current one -- so a refusal is
     * returned as the denial it is, with core's own reason attached, and the
     * cache is not consulted at all.
     */
    public function decide(string $gate, string $platformKind, string $platformId): Answer
    {
        $now = ($this->clock)();
        $identityRef = $platformKind . ':' . $platformId;

        $outcome = $this->call('decide', [
            'gate' => $gate,
            'platformKind' => $platformKind,
            'platformId' => $platformId,
        ]);

        if ($outcome instanceof OkOutcome) {
            $decision = Decision::fromPayload($outcome->payload);
            $this->cache->store($gate, $identityRef, $decision, $now);
            return new Answer($decision, Source::FRESH);
        }

        if ($outcome instanceof RefusedOutcome) {
            return new Answer(
                new Decision(
                    \Soulbind\Flarum\Policy\Effect::DENY,
                    $outcome->code,
                    $outcome->message,
                    // Not cacheable. A refusal is about this connector's
                    // standing, not this subject's -- caching it against the
                    // subject would deny the wrong thing for as long as the
                    // entry lived.
                    0
                ),
                Source::FRESH
            );
        }

        return $this->cache->whenUnreachable($gate, $identityRef, $now);
    }
}
