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

use Soulbind\Flarum\Client\DecisionCache;
use Soulbind\Flarum\Client\SoulbindClient;
use Soulbind\Flarum\Client\Transport;
use Soulbind\Flarum\Link\LinkService;

/**
 * The three things a member can do about linking.
 *
 * No forum here: {@see LinkService} knows nothing about the host, so what a
 * member is told can be checked without standing one up.
 */
final class LinkChecks
{
    private const NOW = 1_700_000_000;

    private function __construct()
    {
    }

    private static function service(Transport $transport): LinkService
    {
        return new LinkService(
            new SoulbindClient(
                $transport,
                'a-credential',
                new DecisionCache(),
                static fn (): int => self::NOW,
                static fn (): string => 'nonce'
            ),
            'forum'
        );
    }

    /** @return list<string> */
    public static function statusReportsWhatCoreSays(): array
    {
        $failures = [];

        $linked = self::service(FakeTransport::ok([
            'linked' => true,
            'identities' => [['platformKind' => 'game', 'platformId' => 'g1']],
        ]))->status('u1');

        if (!$linked->ok || $linked->data['linked'] !== true) {
            $failures[] = 'a linked account was not reported as linked';
        }
        if (count($linked->data['identities'] ?? []) !== 1) {
            $failures[] = 'the identities core listed did not come back';
        }

        $unlinked = self::service(FakeTransport::ok(['linked' => false, 'identities' => []]))
            ->status('u1');
        if (!$unlinked->ok || $unlinked->data['linked'] !== false) {
            $failures[] = 'an unlinked account was not reported as unlinked';
        }

        // A payload missing the field entirely must read as NOT linked. The
        // safe default matters: showing "linked" to somebody who is not would
        // send them to ask why a gate still refuses them.
        $silent = self::service(FakeTransport::ok([]))->status('u1');
        if (!$silent->ok || $silent->data['linked'] !== false) {
            $failures[] = 'a response with no linked field did not default to unlinked';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function aCodeComesBackOrTheReasonDoes(): array
    {
        $failures = [];

        $issued = self::service(FakeTransport::ok(['code' => 'BCDFGH']))->issueCode('u1', 'Ann');
        if (!$issued->ok || ($issued->data['code'] ?? '') !== 'BCDFGH') {
            $failures[] = 'an issued code did not come back';
        }

        // A success carrying no code is not a success. Handing back an empty
        // string shows the member a blank box and no error at all.
        foreach ([[], ['code' => ''], ['code' => 123]] as $payload) {
            $empty = self::service(FakeTransport::ok($payload))->issueCode('u1', 'Ann');
            if ($empty->ok) {
                $failures[] = 'a response with no usable code was reported as success';
            }
        }

        return $failures;
    }

    /**
     * A refusal keeps core's words; an outage gets ours.
     *
     * @return list<string>
     */
    public static function refusalAndOutageStayDistinct(): array
    {
        $failures = [];

        $refused = self::service(
            FakeTransport::refusing('unknown-code', 'No such code, or it expired.')
        )->redeemCode('BCDFGH', 'u1', 'Ann');

        if ($refused->ok) {
            $failures[] = 'a refused redemption was reported as success';
        }
        if ($refused->unavailable) {
            $failures[] = 'core answered and it was reported as an outage. A member told to '
                . 'try again later, when the truth is their code was wrong, will try again '
                . 'later -- forever.';
        }
        if (!str_contains($refused->message, 'No such code')) {
            $failures[] = "core's own reason was discarded: '{$refused->message}'";
        }

        $down = self::service(FakeTransport::failing())->redeemCode('BCDFGH', 'u1', 'Ann');
        if ($down->ok) {
            $failures[] = 'a redemption during an outage was reported as success';
        }
        if (!$down->unavailable) {
            $failures[] = 'an outage was reported as a refusal, so the member is told their '
                . 'code is wrong when it was never checked';
        }
        if (!str_contains($down->message, 'on our side')) {
            $failures[] = 'the outage message does not say the fault is ours';
        }

        return $failures;
    }

    /**
     * An obvious typo is refused here, without a round trip.
     *
     * @return list<string>
     */
    public static function anImpossibleCodeIsRefusedLocally(): array
    {
        $failures = [];

        foreach (['', '   ', 'not a code!', '0OIL1', 'ß2345'] as $rubbish) {
            // A transport that would FAIL if called. If normalisation lets one
            // of these through, the failure is unmistakable rather than a
            // wasted round trip nobody notices.
            $result = self::service(FakeTransport::failing('should not have been called'))
                ->redeemCode($rubbish, 'u1', 'Ann');

            if ($result->ok) {
                $failures[] = "'{$rubbish}' was accepted as a code";
            }
            if ($result->unavailable) {
                $failures[] = "'{$rubbish}' reached the network. It cannot be a code -- "
                    . 'refusing it locally is the difference between telling somebody their '
                    . 'code is malformed and telling them the system is down.';
            }
        }

        // And a real code must still travel, or the check above is satisfied by
        // refusing everything.
        $ok = self::service(FakeTransport::ok(['identities' => []]))
            ->redeemCode('bcd-fgh', 'u1', 'Ann');
        if (!$ok->ok) {
            $failures[] = 'a well-formed code was refused locally; refusing everything would '
                . 'satisfy every assertion above';
        }

        return $failures;
    }

    /**
     * The code that travels is the NORMALISED one.
     *
     * @return list<string>
     */
    public static function theNormalisedCodeIsWhatTravels(): array
    {
        $transport = FakeTransport::ok(['identities' => []]);
        self::service($transport)->redeemCode('  bcd-fgh  ', 'u1', 'Ann');

        $sent = json_decode($transport->lastBody ?? '', true);
        $code = $sent['payload']['code'] ?? null;

        if ($code !== 'BCDFGH') {
            return [
                "the code sent to core was '" . var_export($code, true) . "', not 'BCDFGH'. "
                . 'Core normalises again and is the authority, but sending the raw text means '
                . 'the two sides are normalising different things -- which is the disagreement '
                . 'the golden vectors exist to prevent.',
            ];
        }

        return [];
    }

    /**
     * The platform kind is this connector's, never the caller's.
     *
     * @return list<string>
     */
    public static function thePlatformKindIsNotCallerSupplied(): array
    {
        $failures = [];

        foreach (['status', 'issueCode', 'redeemCode'] as $method) {
            $transport = FakeTransport::ok(['code' => 'BCDFGH', 'identities' => [], 'linked' => false]);
            $service = self::service($transport);

            match ($method) {
                'status' => $service->status('u1'),
                'issueCode' => $service->issueCode('u1', 'Ann'),
                'redeemCode' => $service->redeemCode('BCDFGH', 'u1', 'Ann'),
            };

            $sent = json_decode($transport->lastBody ?? '', true);
            if (($sent['payload']['platformKind'] ?? null) !== 'forum') {
                $failures[] = "{$method} sent platformKind '"
                    . ($sent['payload']['platformKind'] ?? 'absent')
                    . "'. It is fixed at construction on purpose: a caller able to change it "
                    . 'could ask about, or link, an account on another platform entirely.';
            }
        }

        return $failures;
    }
}
