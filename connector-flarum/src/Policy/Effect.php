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

namespace Soulbind\Flarum\Policy;

/** Allow or deny. There is no third answer, and there deliberately is not. */
enum Effect: string
{
    case ALLOW = 'allow';
    case DENY = 'deny';

    /**
     * Parses a wire value, returning null rather than defaulting.
     *
     * Null, not DENY, for the same reason the other side returns an empty
     * optional: a typo must not quietly become ALLOW, and it must not quietly
     * become DENY either without a caller deciding that is what an unreadable
     * value means. Every caller here decides "deny" -- but they decide it.
     */
    public static function fromWireName(?string $value): ?self
    {
        if ($value === null) {
            return null;
        }
        return self::tryFrom(strtolower(trim($value)));
    }
}
