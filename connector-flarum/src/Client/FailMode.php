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

/** What to do when core cannot be reached and nothing usable is cached. */
enum FailMode: string
{
    /** Deny. The default, and the only default. */
    case CLOSED = 'closed';

    /**
     * Allow.
     *
     * A deliberate choice for a gate whose cost of wrongly denying exceeds its
     * cost of wrongly allowing. Never arrived at by omission.
     */
    case OPEN = 'open';

    /**
     * Parses a configured value.
     *
     * Anything that is not exactly "open" is CLOSED. Not an exception, and
     * certainly not OPEN: a typo in a fail mode must never be the thing that
     * opens a gate. "OPEN ", "Open" and "open" are the same answer; "opne" is
     * closed, and silently so, because the alternative is a forum that will not
     * boot over a spelling mistake.
     */
    public static function fromConfigName(?string $value): self
    {
        if ($value === null) {
            return self::CLOSED;
        }
        return strtolower(trim($value)) === 'open' ? self::OPEN : self::CLOSED;
    }
}
