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

namespace Soulbind\Flarum\Settings;

use Soulbind\Flarum\Client\FailMode;

/**
 * The settings this connector understands, with their defaults.
 *
 * One place that knows the key names and what an absent value means. Reading
 * settings directly at each use site is how two call sites end up disagreeing
 * about what an unset value means -- and here that disagreement is a gate.
 *
 * Every default is the safe one:
 *
 * - gates are **off** until switched on, because a fresh install must not start
 *   refusing registrations before anybody has configured a core to ask;
 * - the fail mode is **closed**, because a gate that is on must not open when
 *   core goes away;
 * - the core URL and credential default to empty, and an unconfigured gate is
 *   treated as off rather than as a gate that denies everything.
 *
 * Those two "off" defaults pull in opposite directions and both are deliberate:
 * an unconfigured connector is inert, and a configured one fails closed. What
 * must never happen is a connector that is half-configured and silently open,
 * so `isConfigured()` is a single explicit test rather than a scattering of
 * empty checks.
 */
final class ConnectorSettings
{
    public const PREFIX = 'soulbind.';

    public const CORE_URL = self::PREFIX . 'core_url';
    public const CREDENTIAL = self::PREFIX . 'credential';
    public const WEBHOOK_SECRET = self::PREFIX . 'webhook_secret';
    public const FAIL_MODE = self::PREFIX . 'fail_mode';
    public const REGISTER_GATE = self::PREFIX . 'register_gate';
    public const POST_GATE = self::PREFIX . 'post_gate';
    public const TIMEOUT_MS = self::PREFIX . 'timeout_ms';

    public function __construct(private readonly Settings $settings)
    {
    }

    public function coreUrl(): string
    {
        return trim($this->settings->get(self::CORE_URL) ?? '');
    }

    public function credential(): string
    {
        // NOT trimmed. A credential is an opaque token, and silently altering it
        // would turn a copy-paste with a trailing space into an authentication
        // failure nobody can explain -- whereas passing it through unchanged
        // fails in a way the operator can see and fix.
        return $this->settings->get(self::CREDENTIAL) ?? '';
    }

    public function webhookSecret(): string
    {
        return $this->settings->get(self::WEBHOOK_SECRET) ?? '';
    }

    public function failMode(): FailMode
    {
        return FailMode::fromConfigName($this->settings->get(self::FAIL_MODE));
    }

    /** The gate name for registration, or null when registration is not gated. */
    public function registerGate(): ?string
    {
        return self::gateName($this->settings->get(self::REGISTER_GATE));
    }

    /** The gate name for posting, or null when posting is not gated. */
    public function postGate(): ?string
    {
        return self::gateName($this->settings->get(self::POST_GATE));
    }

    /** The default, when the setting is absent or unreadable. */
    public const DEFAULT_TIMEOUT_MS = 2000;

    /**
     * The synchronous decide timeout.
     *
     * Short on purpose: this runs while somebody is waiting on a page load, and
     * a gate that hangs is worse than a gate that denies -- a denial says
     * something, a hang says nothing and the person reloads.
     *
     * Unreadable values fall back to the DEFAULT, not to the floor. That
     * distinction was found by mutation: with the parse check removed, `2.5`
     * cast to 2 and clamped to 100ms, which is inside the permitted range and
     * therefore looked fine -- while being short enough that every call would
     * time out. A typo would have turned into a permanent outage, and a
     * permanent outage into a permanently closed gate.
     *
     * An explicit `0` falls back too. It means "no timeout" to most HTTP
     * clients, which is the hang; and clamping it to 100ms is not what the
     * operator meant either. Neither reading is safe, so neither is guessed.
     */
    public function timeoutMs(): int
    {
        $raw = trim($this->settings->get(self::TIMEOUT_MS) ?? '');
        if ($raw === '' || preg_match('/^\d+$/', $raw) !== 1) {
            return self::DEFAULT_TIMEOUT_MS;
        }
        $ms = (int) $raw;
        if ($ms === 0) {
            return self::DEFAULT_TIMEOUT_MS;
        }
        return max(100, min(10_000, $ms));
    }

    /**
     * Whether there is enough here to ask core anything.
     *
     * One explicit test, so a half-configured connector cannot be silently open
     * at one call site and closed at another.
     */
    public function isConfigured(): bool
    {
        return $this->coreUrl() !== '' && $this->credential() !== '';
    }

    private static function gateName(?string $raw): ?string
    {
        $name = trim($raw ?? '');
        return $name === '' ? null : $name;
    }
}
