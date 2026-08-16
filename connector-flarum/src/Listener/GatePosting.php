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

use Flarum\Post\Event\Saving;
use Soulbind\Flarum\Gate\AccessGate;

/**
 * Applies the posting gate.
 *
 * Thin for the same reason as {@see GateRegistration}: the decision lives in
 * {@see \Soulbind\Flarum\Gate\AccessGate}, which is tested without a forum.
 */
final class GatePosting
{
    public function __construct(private readonly AccessGate $gate)
    {
    }

    public function handle(Saving $event): void
    {
        // New posts only. Gating edits would strand somebody mid-sentence over a
        // rule that changed after they started, and an edit is not the action
        // the gate is about.
        if ($event->post->exists) {
            return;
        }

        $actor = $event->actor;
        if ($actor->isGuest()) {
            // Guests cannot post anyway; the forum's own permissions answer
            // this. Asking core would be a round trip to reach a conclusion the
            // host already reached.
            return;
        }

        $outcome = $this->gate->checkPosting((string) $actor->id);
        if (!$outcome->allowed) {
            throw new GateRefused($outcome);
        }
    }
}
