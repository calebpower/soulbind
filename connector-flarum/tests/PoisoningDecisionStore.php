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

namespace Soulbind\Flarum\Tests;

use Soulbind\Flarum\Client\DecisionStore;

/**
 * A store that corrupts decision entries on the way in.
 *
 * Stands in for a shared cache written by something else -- another version of
 * this extension mid-upgrade, or anything else sharing the key space.
 *
 * A decorator rather than reflection into the cache's private key format: a
 * test that reconstructs the key by hand would keep passing while testing
 * nothing the day the format changed, and reaching into a private method
 * couples the test to an internal it has no business knowing.
 *
 * Generation markers are passed through untouched. They are the cache's own
 * bookkeeping, not decision entries, and corrupting them would test a different
 * thing while looking like this one.
 */
final class PoisoningDecisionStore implements DecisionStore
{
    /** @var array<string, string> */
    private array $entries = [];

    public function __construct(private readonly string $poison)
    {
    }

    public function get(string $key): ?string
    {
        return $this->entries[$key] ?? null;
    }

    public function put(string $key, string $value, int $ttlSeconds): void
    {
        $this->entries[$key] = str_contains($key, 'gen') ? $value : $this->poison;
    }

    public function forget(string $key): void
    {
        unset($this->entries[$key]);
    }
}
