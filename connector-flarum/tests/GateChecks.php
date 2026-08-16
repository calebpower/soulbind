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
use Soulbind\Flarum\Client\FailMode;
use Soulbind\Flarum\Client\SoulbindClient;
use Soulbind\Flarum\Client\Source;
use Soulbind\Flarum\Client\Transport;
use Soulbind\Flarum\Gate\AccessGate;
use Soulbind\Flarum\Settings\ArraySettings;
use Soulbind\Flarum\Settings\ConnectorSettings;

/**
 * The register and post gates, and the settings behind them.
 *
 * No forum here on purpose: {@see AccessGate} knows nothing about the host
 * platform, so the rules are testable without standing up one -- and the rules
 * are what must not be wrong.
 */
final class GateChecks
{
    private const NOW = 1_700_000_000;

    private function __construct()
    {
    }

    /** @param array<string, string> $overrides */
    private static function settings(array $overrides = []): ConnectorSettings
    {
        return new ConnectorSettings(new ArraySettings($overrides));
    }

    /** @param array<string, string> $overrides */
    private static function configured(array $overrides = []): array
    {
        return array_merge([
            ConnectorSettings::CORE_URL => 'https://core.example.com',
            ConnectorSettings::CREDENTIAL => 'a-credential',
        ], $overrides);
    }

    private static function gate(
        Transport $transport,
        array $settings,
        ?DecisionCache $cache = null
    ): AccessGate {
        $client = new SoulbindClient(
            $transport,
            'a-credential',
            $cache ?? new DecisionCache(),
            static fn (): int => self::NOW,
            static fn (): string => 'nonce'
        );
        return new AccessGate($client, self::settings($settings), 'forum');
    }

    /**
     * A fresh install gates nothing, and every default is the safe one.
     *
     * @return list<string>
     */
    public static function defaultsAreSafe(): array
    {
        $failures = [];
        $fresh = self::settings();

        if ($fresh->registerGate() !== null || $fresh->postGate() !== null) {
            $failures[] = 'a fresh install has a gate switched on. An extension that starts '
                . 'refusing registrations before anybody configured a core to ask would be '
                . 'uninstalled within the minute, and would deserve it.';
        }
        if ($fresh->failMode() !== FailMode::CLOSED) {
            $failures[] = 'the default fail mode is not closed';
        }
        if ($fresh->isConfigured()) {
            $failures[] = 'a settings store with nothing in it reported itself configured';
        }

        // A gate name that is only whitespace is not a gate name.
        $blank = self::settings([ConnectorSettings::REGISTER_GATE => '   ']);
        if ($blank->registerGate() !== null) {
            $failures[] = 'a whitespace-only gate name was treated as a gate';
        }

        // Half-configured is not configured.
        foreach ([
            'a URL with no credential' => [ConnectorSettings::CORE_URL => 'https://x'],
            'a credential with no URL' => [ConnectorSettings::CREDENTIAL => 'c'],
        ] as $what => $half) {
            if (self::settings($half)->isConfigured()) {
                $failures[] = "{$what} reported itself configured";
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function theTimeoutIsBoundedAtBothEnds(): array
    {
        $failures = [];
        $default = ConnectorSettings::DEFAULT_TIMEOUT_MS;

        if (self::settings()->timeoutMs() !== $default) {
            $failures[] = 'an absent timeout setting did not produce the default';
        }

        // Unreadable values must reach the DEFAULT, not merely land somewhere
        // inside the permitted range.
        //
        // Found by mutation: with the parse check removed, '2.5' cast to 2 and
        // clamped to 100ms. That is in range, so an assertion that only checked
        // the range passed -- while 100ms is short enough that every decide call
        // would time out, turning a typo into a permanent outage and a permanent
        // outage into a permanently closed gate.
        $mustDefault = [
            '' => 'an empty setting',
            '   ' => 'a whitespace setting',
            'soon' => 'an unparseable setting',
            '2.5' => 'a non-integer, which a cast would silently truncate',
            '-1' => 'a negative value, which a cast would read as -1',
            '2000ms' => 'a value with a unit suffix, which a cast would read as 2000',
            '1e3' => 'scientific notation, which is_numeric would accept',
            '0' => 'an explicit zero, which means "no timeout" to most HTTP clients -- and '
                . 'clamping it to the 100ms floor is not what the operator meant either, so '
                . 'neither reading is guessed',
        ];
        foreach ($mustDefault as $value => $why) {
            $ms = self::settings([ConnectorSettings::TIMEOUT_MS => (string) $value])->timeoutMs();
            if ($ms !== $default) {
                $failures[] = "the timeout setting '{$value}' produced {$ms}ms rather than the "
                    . "default {$default}ms -- {$why}";
            }
        }

        // Out-of-range but readable values are clamped, not defaulted: the
        // operator expressed an intent and the nearest permitted value honours
        // it, whereas an unreadable value expressed nothing.
        if (self::settings([ConnectorSettings::TIMEOUT_MS => '5'])->timeoutMs() !== 100) {
            $failures[] = 'a too-small timeout was not clamped to the floor';
        }
        if (self::settings([ConnectorSettings::TIMEOUT_MS => '999999'])->timeoutMs() !== 10_000) {
            $failures[] = 'an absurd timeout was not clamped to the ceiling; a hang with extra '
                . 'steps is still a hang';
        }

        // A sane value must be honoured exactly, or the setting is decorative
        // and every assertion above would pass with a hardcoded constant.
        if (self::settings([ConnectorSettings::TIMEOUT_MS => '750'])->timeoutMs() !== 750) {
            $failures[] = 'a valid timeout was not used';
        }

        return $failures;
    }

    /**
     * A credential is opaque and is not tidied up.
     *
     * @return list<string>
     */
    public static function theCredentialIsNotAltered(): array
    {
        $failures = [];

        $withSpace = self::settings(['soulbind.credential' => ' token-with-space '])
            ->credential();
        if ($withSpace !== ' token-with-space ') {
            $failures[] = 'the credential was altered on the way out. Trimming turns a '
                . 'copy-paste with a trailing space into an authentication failure nobody can '
                . 'explain; passing it through unchanged fails visibly instead.';
        }

        // The URL, by contrast, IS trimmed -- a stray newline there is never
        // meaningful and always a paste artefact.
        if (self::settings(['soulbind.core_url' => "  https://x  \n"])->coreUrl()
            !== 'https://x') {
            $failures[] = 'the core URL was not trimmed';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function anUnconfiguredConnectorIsInertNotClosed(): array
    {
        $failures = [];

        // The gate is NAMED but there is no core to ask. Half-configured.
        $gate = self::gate(
            FakeTransport::failing('there is nothing to connect to'),
            [ConnectorSettings::REGISTER_GATE => 'forum-register']
        );
        $outcome = $gate->checkRegistration('u1');

        if (!$outcome->allowed) {
            $failures[] = 'an unconfigured connector DENIED. Fail-closed is about an outage: '
                . 'core configured and unreachable. Core never configured is not an outage, it '
                . 'is the absence of a gate -- and an extension that bricks a working forum '
                . 'between two admin-panel form fields is not a safety feature.';
        }
        if ($outcome->source !== null) {
            $failures[] = 'an inert gate reported a decision source, so an operator cannot '
                . 'tell "nobody configured this" from "core said yes"';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function aConfiguredGateAsksCoreAndHonoursIt(): array
    {
        $failures = [];

        $allow = self::gate(
            FakeTransport::ok([
                'effect' => 'allow',
                'reason' => 'requirements-met',
                'ttlSeconds' => 60,
            ]),
            self::configured([ConnectorSettings::REGISTER_GATE => 'forum-register'])
        )->checkRegistration('u1');

        if (!$allow->allowed || $allow->source !== Source::FRESH) {
            $failures[] = 'core allowed and the gate did not';
        }

        $deny = self::gate(
            FakeTransport::ok([
                'effect' => 'deny',
                'reason' => 'not-linked',
                'detail' => 'Link a game account to post here.',
                'ttlSeconds' => 60,
            ]),
            self::configured([ConnectorSettings::POST_GATE => 'forum-post'])
        )->checkPosting('u1');

        if ($deny->allowed) {
            $failures[] = 'core denied and the gate allowed';
        }
        if ($deny->message !== 'Link a game account to post here.') {
            $failures[] = "the gate discarded core's own wording and said '{$deny->message}'. "
                . 'Core knows what is missing; this connector does not.';
        }
        if ($deny->reason !== 'not-linked') {
            $failures[] = 'the machine-readable reason was lost';
        }

        return $failures;
    }

    /**
     * A denial always says something a person can act on.
     *
     * @return list<string>
     */
    public static function aDenialIsNeverWordless(): array
    {
        $failures = [];

        foreach ([null, ''] as $detail) {
            $outcome = self::gate(
                FakeTransport::ok([
                    'effect' => 'deny',
                    'reason' => 'not-linked',
                    'detail' => $detail,
                    'ttlSeconds' => 60,
                ]),
                self::configured([ConnectorSettings::POST_GATE => 'forum-post'])
            )->checkPosting('u1');

            if (trim($outcome->message) === '') {
                $failures[] = 'core denied without a detail and the gate passed the silence on. '
                    . 'A refusal nobody can act on is a support ticket.';
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function anOutageDeniesAndBlamesTheSystem(): array
    {
        $failures = [];

        $outcome = self::gate(
            FakeTransport::failing(),
            self::configured([ConnectorSettings::POST_GATE => 'forum-post'])
        )->checkPosting('u1');

        if ($outcome->allowed) {
            $failures[] = 'a configured gate allowed during an outage';
        }
        if ($outcome->source !== Source::FAIL_MODE) {
            $failures[] = 'the outage denial did not report itself as a fail-mode answer';
        }
        if ($outcome->message !== DecisionCache::FAIL_CLOSED_MESSAGE) {
            $failures[] = 'the outage denial did not use the message that blames the system. '
                . 'Somebody refused because a server they have never heard of is unreachable '
                . 'must not be told they are not allowed.';
        }

        // A live cached allow survives the outage; that is what the cache is for.
        $cache = new DecisionCache();
        $cache->store(
            'forum-post',
            'forum:u1',
            new \Soulbind\Flarum\Policy\Decision(
                \Soulbind\Flarum\Policy\Effect::ALLOW,
                'requirements-met',
                'ok',
                600
            ),
            self::NOW
        );
        $cached = self::gate(
            FakeTransport::failing(),
            self::configured([ConnectorSettings::POST_GATE => 'forum-post']),
            $cache
        )->checkPosting('u1');

        if (!$cached->allowed || $cached->source !== Source::CACHED) {
            $failures[] = 'an outage ignored a live cached allow, so the cache buys nothing at '
                . 'the only moment it matters';
        }

        return $failures;
    }

    /**
     * A refusal is not an outage, at the gate as well as in the client.
     *
     * @return list<string>
     */
    public static function aRefusalIsNotSoftenedAtTheGate(): array
    {
        $failures = [];

        $outcome = self::gate(
            FakeTransport::refusing('missing-capability', 'this connector may not decide'),
            self::configured([ConnectorSettings::POST_GATE => 'forum-post'])
        )->checkPosting('u1');

        if ($outcome->allowed) {
            $failures[] = 'core refused the connector and the gate allowed the action';
        }
        if ($outcome->reason !== 'missing-capability') {
            $failures[] = 'the gate flattened a capability refusal into a generic denial, so '
                . 'an operator sees "not linked" when the real fix is a credential grant';
        }

        return $failures;
    }

    /**
     * Only the configured gate is asked, and only for the right action.
     *
     * @return list<string>
     */
    public static function eachActionAsksItsOwnGate(): array
    {
        $failures = [];

        // Posting is gated; registration is not. Registering must not consult
        // the post gate, nor ask core at all.
        $transport = FakeTransport::ok(['effect' => 'deny', 'reason' => 'not-linked']);
        $gate = self::gate(
            $transport,
            self::configured([ConnectorSettings::POST_GATE => 'forum-post'])
        );

        $registration = $gate->checkRegistration('u1');
        if (!$registration->allowed) {
            $failures[] = 'registration was denied by a gate configured for posting';
        }
        if ($transport->calls !== 0) {
            $failures[] = 'an ungated action still called core. Every call is latency on a '
                . 'page load somebody is waiting for.';
        }

        // And the gate name that reaches core is the configured one.
        $transport = FakeTransport::ok(['effect' => 'allow', 'reason' => 'ok']);
        self::gate(
            $transport,
            self::configured([ConnectorSettings::REGISTER_GATE => 'a-specific-gate'])
        )->checkRegistration('u1');

        $sent = json_decode($transport->lastBody ?? '', true);
        if (($sent['payload']['gate'] ?? null) !== 'a-specific-gate') {
            $failures[] = 'the gate name sent to core was not the configured one';
        }
        if (($sent['payload']['platformKind'] ?? null) !== 'forum') {
            $failures[] = 'the platform kind sent to core was not this connector\'s own';
        }

        return $failures;
    }
}
