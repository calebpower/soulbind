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

use Flarum\Foundation\KnownError;
use RuntimeException;
use Soulbind\Flarum\Gate\GateOutcome;

/**
 * A gate said no.
 *
 * Implements the host's KnownError so the forum renders it as a message to the
 * person rather than as a server fault. A gate refusing is this extension
 * working; logging it as a 500 would bury a working refusal in an error report
 * and tell the person nothing.
 */
final class GateRefused extends RuntimeException implements KnownError
{
    public function __construct(public readonly GateOutcome $outcome)
    {
        parent::__construct($outcome->message);
    }

    public function getType(): string
    {
        return 'soulbind_gate_refused';
    }
}
