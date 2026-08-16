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

namespace Soulbind\Flarum\Listener;

use Flarum\User\Event\Saving;
use Illuminate\Support\Arr;
use Soulbind\Flarum\Gate\AccessGate;
use Soulbind\Flarum\Gate\GateOutcome;

/**
 * Applies the registration gate.
 *
 * Deliberately thin. Everything decidable lives in {@see AccessGate}, which has
 * no forum in it and is covered by `GateChecks`; this class exists only to
 * translate between the host's event and that decision, and to turn a refusal
 * into the exception the host understands.
 *
 * The thinness is the design, not laziness: this file cannot be tested without
 * standing up a forum, so the less judgement it contains, the less judgement is
 * going untested.
 */
final class GateRegistration
{
    public function __construct(private readonly AccessGate $gate)
    {
    }

    public function handle(Saving $event): void
    {
        // Registration only. `Saving` also fires for every profile edit, and
        // gating those would lock an existing member out of their own settings
        // page -- including, if the gate is misconfigured, out of the page they
        // would use to link an account and satisfy it.
        if ($event->user->exists) {
            return;
        }

        $identifier = self::identifierFor($event);
        if ($identifier === null) {
            // No stable identifier yet means there is nothing to ask about. The
            // post gate still applies, so this is a deferral rather than a way
            // through.
            return;
        }

        $outcome = $this->gate->checkRegistration($identifier);
        if (!$outcome->allowed) {
            throw new GateRefused($outcome);
        }
    }

    /**
     * The identifier this connector vouches for.
     *
     * The email address, lowercased, because a user id does not exist yet at
     * registration time and the username is not stable across a rename. It is
     * the same value the forum itself treats as the account's identity.
     */
    private static function identifierFor(Saving $event): ?string
    {
        $email = Arr::get($event->data, 'attributes.email');
        if (!is_string($email) || trim($email) === '') {
            return null;
        }
        // Lowercased with an explicit ASCII fold rather than mb_strtolower: an
        // address that differs only by the case of a non-ASCII character is a
        // different address to some mail systems, and folding it here would
        // silently merge two people.
        return strtr(trim($email), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz');
    }
}
