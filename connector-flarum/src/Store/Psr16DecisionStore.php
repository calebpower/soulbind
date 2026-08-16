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

namespace Soulbind\Flarum\Store;

use Psr\SimpleCache\CacheInterface;
use Psr\SimpleCache\InvalidArgumentException;
use Soulbind\Flarum\Client\DecisionStore;

/**
 * Decisions in the host's shared cache, so they survive between requests.
 *
 * PSR-16 forbids several characters in a key and permits an implementation to
 * throw on others, so keys are hashed rather than passed through. The cache's
 * own key rules are not soulbind's to negotiate, and a key that works on one
 * driver and throws on another is a fault that only appears in somebody else's
 * deployment.
 *
 * Hashing also removes the unit separator the cache uses internally -- which
 * PSR-16 would reject outright -- while keeping the collision resistance that
 * separator was chosen for.
 */
final class Psr16DecisionStore implements DecisionStore
{
    private const PREFIX = 'soulbind.decision.';

    public function __construct(private readonly CacheInterface $cache)
    {
    }

    public function get(string $key): ?string
    {
        try {
            $value = $this->cache->get(self::hash($key));
        } catch (InvalidArgumentException) {
            // A cache that will not answer is a cache miss, not an error. The
            // caller re-asks core, which is slower and correct -- whereas
            // throwing here would turn a cache problem into a page failure.
            return null;
        }
        return is_string($value) ? $value : null;
    }

    public function put(string $key, string $value, int $ttlSeconds): void
    {
        try {
            $this->cache->set(self::hash($key), $value, $ttlSeconds);
        } catch (InvalidArgumentException) {
            // Failing to cache is not failing. The next request asks core.
        }
    }

    public function forget(string $key): void
    {
        try {
            $this->cache->delete(self::hash($key));
        } catch (InvalidArgumentException) {
            // Nothing useful to do, and an uncached decision is the safe
            // direction to fail in: the caller re-asks core.
        }
    }

    private static function hash(string $key): string
    {
        // sha256 rather than md5: not for secrecy -- these keys are not secret
        // -- but because a collision here serves one subject's decision to
        // another, which is the same harm the separator exists to prevent.
        return self::PREFIX . hash('sha256', $key);
    }
}
