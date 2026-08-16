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

/**
 * A store that lives for one request.
 *
 * For tests, and for a deployment with no shared cache configured -- where it
 * degrades to "no caching between requests" rather than to "wrong answers".
 * That degradation is visible in `soulbind` terms: every gate check becomes a
 * synchronous decide, which is slower and entirely correct.
 */
final class ArrayDecisionStore implements DecisionStore
{
    /** @var array<string, array{value: string, expiresAt: int}> */
    private array $entries = [];

    /** @param callable(): int|null $clock */
    public function __construct(private $clock = null)
    {
        $this->clock = $clock ?? static fn (): int => time();
    }

    public function get(string $key): ?string
    {
        $entry = $this->entries[$key] ?? null;
        if ($entry === null) {
            return null;
        }
        if ($entry['expiresAt'] <= ($this->clock)()) {
            unset($this->entries[$key]);
            return null;
        }
        return $entry['value'];
    }

    public function put(string $key, string $value, int $ttlSeconds): void
    {
        $this->entries[$key] = [
            'value' => $value,
            'expiresAt' => ($this->clock)() + $ttlSeconds,
        ];
    }

    public function forget(string $key): void
    {
        unset($this->entries[$key]);
    }

    public function size(): int
    {
        return count($this->entries);
    }
}
