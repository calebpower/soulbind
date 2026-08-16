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

namespace Soulbind\Flarum\Link;

use Soulbind\Flarum\Client\OkOutcome;
use Soulbind\Flarum\Client\RefusedOutcome;
use Soulbind\Flarum\Client\SoulbindClient;
use Soulbind\Flarum\Client\UnreachableOutcome;
use Soulbind\Flarum\Protocol\LinkCode;

/**
 * The three things a member can do about linking, with no forum in sight.
 *
 * Show me what I am linked to; give me a code to type somewhere else; take a
 * code I was given somewhere else. The controllers over this are a few lines
 * each and translate HTTP; everything decidable is here, where it is testable
 * without standing up a forum.
 *
 * The platform kind is fixed at construction, not taken from a request. A
 * caller able to change it could ask about, or link, an account on another
 * platform entirely.
 */
final class LinkService
{
    public function __construct(
        private readonly SoulbindClient $client,
        private readonly string $platformKind
    ) {
    }

    /** What this account is linked to. */
    public function status(string $platformId): LinkResult
    {
        $outcome = $this->client->call('identity.describe', [
            'platformKind' => $this->platformKind,
            'platformId' => $platformId,
        ]);

        if ($outcome instanceof OkOutcome) {
            $identities = self::identities($outcome->payload);

            return LinkResult::success([
                'linked' => (bool) ($outcome->payload['linked'] ?? false),
                'identities' => $identities,
                // OTHERS, excluding this account.
                //
                // identity.describe returns every identity on the subject,
                // including the one asking. A panel that counted them all told a
                // member with one linked game account that they were "linked to 2
                // other" -- and somebody reading that goes looking for a second
                // account that does not exist.
                //
                // Counted here rather than in the panel because this is where the
                // platform kind and the caller's id both are, and a browser
                // subtracting one would be guessing that its own identity is
                // always present.
                'otherCount' => count(array_filter(
                    $identities,
                    fn (array $i): bool => !(
                        ($i['platformKind'] ?? null) === $this->platformKind
                        && (string) ($i['platformId'] ?? '') === $platformId
                    )
                )),
            ]);
        }

        return self::failure($outcome, 'We could not check your link status just now.');
    }

    /** A code this member types into the other platform. */
    public function issueCode(string $platformId, string $display): LinkResult
    {
        $outcome = $this->client->call('code.issue', [
            'platformKind' => $this->platformKind,
            'platformId' => $platformId,
            'display' => $display,
        ]);

        if ($outcome instanceof OkOutcome) {
            $code = $outcome->payload['code'] ?? null;
            if (!is_string($code) || $code === '') {
                // A success with no code is not a success. Handing back an
                // empty string would show the member a blank box and no error.
                return LinkResult::unavailable('We could not get a code just now.');
            }
            return LinkResult::success(['code' => $code]);
        }

        return self::failure($outcome, 'We could not get a code just now.');
    }

    /**
     * Takes a code issued on the other platform.
     *
     * Normalised HERE before it travels, and rejected locally when it cannot be
     * a code at all. That saves a round trip for an obvious typo -- but core
     * normalises again and is the authority, because two normalisations that
     * disagree is the defect the golden vectors exist to prevent.
     */
    public function redeemCode(string $rawCode, string $platformId, string $display): LinkResult
    {
        $normalised = LinkCode::normalise($rawCode);
        if ($normalised === null) {
            return LinkResult::refused(
                'That does not look like a link code. Check it and try again.'
            );
        }

        $outcome = $this->client->call('code.redeem', [
            'code' => $normalised,
            'platformKind' => $this->platformKind,
            'platformId' => $platformId,
            'display' => $display,
        ]);

        if ($outcome instanceof OkOutcome) {
            return LinkResult::success(
                ['identities' => self::identities($outcome->payload)],
                'Linked.'
            );
        }

        return self::failure($outcome, 'We could not redeem that code just now.');
    }

    /**
     * @param array<string, mixed> $payload
     * @return list<array<string, mixed>>
     */
    private static function identities(array $payload): array
    {
        $identities = $payload['identities'] ?? [];
        if (!is_array($identities)) {
            return [];
        }
        return array_values(array_filter($identities, 'is_array'));
    }

    /**
     * A refusal keeps core's wording; an outage gets ours.
     *
     * Core knows why it said no -- which kind is missing, whether the code was
     * already used -- and this connector does not. But when core did not answer
     * at all there is no wording to keep, and inventing a reason would be
     * guessing at somebody else's answer.
     */
    private static function failure(object $outcome, string $whenUnavailable): LinkResult
    {
        if ($outcome instanceof RefusedOutcome) {
            return LinkResult::refused(
                $outcome->message !== '' ? $outcome->message : 'That was refused.'
            );
        }

        if ($outcome instanceof UnreachableOutcome) {
            return LinkResult::unavailable(
                $whenUnavailable . ' This is a problem on our side, not yours -- '
                . 'please try again shortly.'
            );
        }

        // Neither refused nor unreachable and not Ok: a shape this build does
        // not know. Treated as unavailable rather than refused, because
        // claiming somebody was refused is a stronger statement than admitting
        // we do not know.
        return LinkResult::unavailable($whenUnavailable);
    }
}
