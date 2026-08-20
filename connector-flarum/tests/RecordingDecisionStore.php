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

use Soulbind\Flarum\Client\ArrayDecisionStore;
use Soulbind\Flarum\Client\DecisionStore;

/**
 * An ArrayDecisionStore that also records the calls it received.
 *
 * Exists because some properties are about what the cache ASKS the store to do,
 * not about what can be read back afterwards -- and those two are not the same
 * claim once a real backend is involved.
 *
 * The case that produced it: `store()` with a zero TTL must FORGET the key.
 * Mutation coverage showed that changing `<= 0` to `< 0` -- so the zero case
 * falls through and calls `put($key, $json, 0)` instead -- passed every check,
 * because ArrayDecisionStore treats a zero TTL as already expired and
 * `cached()` returns null either way. Flarum's real cache backends do not agree
 * on that: a zero or absent TTL means "forever" in several of them, which would
 * cache a decision core explicitly said not to keep, permanently.
 *
 * So the assertion has to be about the call, not about the readback.
 */
final class RecordingDecisionStore implements DecisionStore
{
    private readonly ArrayDecisionStore $inner;

    /** @var list<array{op: string, key: string, ttl: int}> */
    private array $calls = [];

    public function __construct(?ArrayDecisionStore $inner = null)
    {
        $this->inner = $inner ?? new ArrayDecisionStore();
    }

    public function get(string $key): ?string
    {
        return $this->inner->get($key);
    }

    public function put(string $key, string $value, int $ttlSeconds): void
    {
        $this->calls[] = ['op' => 'put', 'key' => $key, 'ttl' => $ttlSeconds];
        $this->inner->put($key, $value, $ttlSeconds);
    }

    public function forget(string $key): void
    {
        $this->calls[] = ['op' => 'forget', 'key' => $key, 'ttl' => 0];
        $this->inner->forget($key);
    }

    /** @return list<array{op: string, key: string, ttl: int}> */
    public function calls(): array
    {
        return $this->calls;
    }

    /** Puts recorded so far, ignoring the generation bookkeeping keys. */
    public function decisionPuts(): array
    {
        return array_values(array_filter(
            $this->calls,
            static fn (array $c): bool => $c['op'] === 'put'
                && !str_contains($c['key'], "\x1F" . 'gen' . "\x1F")
        ));
    }
}
