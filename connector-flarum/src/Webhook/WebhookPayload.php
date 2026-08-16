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

/**
 * Reads which identities a delivery is about.
 *
 * Separate from the controller so it can be tested without PSR-7, and separate
 * from the verifier because it runs only on deliveries that already verified --
 * this reads a payload core signed, not one an attacker composed.
 *
 * It is still written defensively. "Signed by core" is not "shaped the way this
 * version of the connector expects": a newer core can send a field this build
 * has never seen, and the right answer to that is to take what is recognised
 * and ignore the rest, never to throw inside a webhook handler.
 */
final class WebhookPayload
{
    /** More than a subject can plausibly have; a bound, not a policy. */
    private const MAX_IDENTITIES = 64;

    private function __construct()
    {
    }

    /**
     * The identity references a delivery says changed.
     *
     * Accepts a single `identityRef` or a list of them, because core's event
     * shapes differ by event and both spellings are in the protocol.
     *
     * @return list<string> deduplicated, never containing an empty string
     */
    public static function affectedIdentities(string $body): array
    {
        $decoded = json_decode($body, true);
        if (!is_array($decoded)) {
            return [];
        }

        $payload = is_array($decoded['payload'] ?? null) ? $decoded['payload'] : $decoded;

        $candidates = [];
        if (is_string($payload['identityRef'] ?? null)) {
            $candidates[] = $payload['identityRef'];
        }
        if (is_array($payload['identityRefs'] ?? null)) {
            foreach ($payload['identityRefs'] as $ref) {
                if (is_string($ref)) {
                    $candidates[] = $ref;
                }
            }
        }
        // The pair spelling, for events that name the platform separately.
        if (is_string($payload['platformKind'] ?? null)
            && is_string($payload['platformId'] ?? null)) {
            $candidates[] = $payload['platformKind'] . ':' . $payload['platformId'];
        }

        $out = [];
        foreach ($candidates as $ref) {
            $ref = trim($ref);
            if ($ref === '' || in_array($ref, $out, true)) {
                continue;
            }
            $out[] = $ref;
            if (count($out) >= self::MAX_IDENTITIES) {
                break;
            }
        }

        return $out;
    }
}
