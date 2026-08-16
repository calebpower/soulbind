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

use InvalidArgumentException;

/**
 * A policy decision, as this side sees it.
 *
 * Mirrors the other implementation's record. The reason is a stable
 * machine-readable code and is what callers match on; `detail` is prose for a
 * human and is never matched against, because prose gets reworded.
 */
final class Decision
{
    /** @param list<string> $missingKinds */
    public function __construct(
        public readonly Effect $effect,
        public readonly string $reason,
        public readonly ?string $detail,
        public readonly int $ttlSeconds,
        public readonly array $missingKinds = []
    ) {
        if ($ttlSeconds < 0) {
            throw new InvalidArgumentException('ttlSeconds must not be negative');
        }
    }

    public function isAllowed(): bool
    {
        return $this->effect === Effect::ALLOW;
    }

    /**
     * Reads a decide response.
     *
     * An unreadable or absent effect is a DENY, and so is a negative TTL. A
     * malformed answer from core is not a reason to let somebody through: the
     * one thing this must never do is turn a response nobody can parse into an
     * allow.
     *
     * @param array<string, mixed> $payload
     */
    public static function fromPayload(array $payload): self
    {
        $effect = Effect::fromWireName(
            is_string($payload['effect'] ?? null) ? $payload['effect'] : null
        ) ?? Effect::DENY;

        $ttl = $payload['ttlSeconds'] ?? 0;
        $ttl = is_int($ttl) ? $ttl : 0;

        $missing = $payload['missingKinds'] ?? [];
        $missing = is_array($missing)
            ? array_values(array_filter($missing, 'is_string'))
            : [];

        return new self(
            $effect,
            is_string($payload['reason'] ?? null) ? $payload['reason'] : 'unreadable',
            is_string($payload['detail'] ?? null) ? $payload['detail'] : null,
            max(0, $ttl),
            $missing
        );
    }
}
