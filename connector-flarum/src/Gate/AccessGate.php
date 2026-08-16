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

namespace Soulbind\Flarum\Gate;

use Soulbind\Flarum\Client\SoulbindClient;
use Soulbind\Flarum\Settings\ConnectorSettings;

/**
 * The gate decision, with no forum in sight.
 *
 * Deliberately knows nothing about the host platform: no request, no user
 * model, no exception type. The listeners that call it are a few lines each and
 * do the translating, which keeps the part with the rules in it testable
 * without standing up a forum -- and the rules are what must not be wrong.
 *
 * The platform kind is fixed at construction rather than passed per call. It
 * identifies which platform this connector speaks for, and a caller able to
 * change it could ask about somebody else's account on another platform.
 */
final class AccessGate
{
    public function __construct(
        private readonly SoulbindClient $client,
        private readonly ConnectorSettings $settings,
        private readonly string $platformKind
    ) {
    }

    /** Whether registration may proceed for an account identified this way. */
    public function checkRegistration(string $platformId): GateOutcome
    {
        return $this->check($this->settings->registerGate(), $platformId);
    }

    /** Whether posting may proceed. */
    public function checkPosting(string $platformId): GateOutcome
    {
        return $this->check($this->settings->postGate(), $platformId);
    }

    private function check(?string $gate, string $platformId): GateOutcome
    {
        if ($gate === null) {
            return GateOutcome::notGated('no gate configured for this action');
        }

        // An unconfigured connector is INERT, not obstructive.
        //
        // This is the one place the fail-closed rule deliberately does not
        // apply, and the distinction is between two different situations that a
        // single "deny when in doubt" would conflate:
        //
        //   - core is configured and unreachable -- an outage. Deny; somebody
        //     chose to gate this, and the gate should hold when the answer is
        //     unavailable.
        //   - core was never configured -- not an outage. There is no gate. A
        //     freshly installed extension that locked everybody out of a working
        //     forum, before its owner had entered a URL, would be uninstalled
        //     within the minute and would deserve it.
        //
        // The gate name being set while the connection is not is the
        // half-configured case, and it resolves to inert too: the operator is
        // mid-setup, and an admin panel that bricks the forum between two form
        // fields is not a safety feature.
        if (!$this->settings->isConfigured()) {
            return GateOutcome::notGated(
                'soulbind is not configured, so this gate is inert rather than closed'
            );
        }

        $answer = $this->client->decide($gate, $this->platformKind, $platformId);

        if ($answer->decision->isAllowed()) {
            return GateOutcome::allow($answer->decision->reason, $answer->source);
        }

        return GateOutcome::deny(
            // Core's own detail when it gave one, because core knows what is
            // missing and this connector does not. Only when it gave none does
            // this side supply wording -- and never a bare "denied", which
            // tells somebody nothing they can act on.
            $answer->decision->detail !== null && $answer->decision->detail !== ''
                ? $answer->decision->detail
                : 'This action needs a linked account. Link one and try again.',
            $answer->decision->reason,
            $answer->source
        );
    }
}
