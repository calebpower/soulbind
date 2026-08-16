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

use Soulbind\Flarum\Client\Source;

/**
 * Whether an action may proceed, and what to tell the person if not.
 *
 * The message is carried here rather than composed at the point of refusal so
 * that every refusal path produces one that has been read by somebody. A gate
 * that denies without saying why generates a support ticket.
 */
final class GateOutcome
{
    private function __construct(
        public readonly bool $allowed,
        public readonly string $message,
        public readonly string $reason,
        public readonly ?Source $source
    ) {
    }

    /** Allowed, and why -- the reason is kept for the audit trail, not the person. */
    public static function allow(string $reason, ?Source $source = null): self
    {
        return new self(true, '', $reason, $source);
    }

    public static function deny(string $message, string $reason, ?Source $source = null): self
    {
        return new self(false, $message, $reason, $source);
    }

    /**
     * Not gated at all.
     *
     * Distinct from an allow, and the distinction is not pedantry: "nobody
     * configured this gate" and "core said yes" look identical to the person and
     * must not look identical to an operator wondering why a rule is not biting.
     */
    public static function notGated(string $reason): self
    {
        return new self(true, '', $reason, null);
    }
}
