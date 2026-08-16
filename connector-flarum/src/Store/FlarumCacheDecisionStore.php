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

use Illuminate\Contracts\Cache\Repository;
use Soulbind\Flarum\Client\DecisionStore;
use Throwable;

/**
 * Decisions in the host's cache, so they survive between requests.
 *
 * Against the host's OWN cache contract, not PSR-16.
 *
 * The first version asked the container for {@code Psr\SimpleCache\CacheInterface}
 * because that is the standard interface, and Flarum does not bind it. Every gate
 * check therefore threw {@code BindingResolutionException: Target
 * [Psr\SimpleCache\CacheInterface] is not instantiable} -- which Flarum reported
 * as a generic 500, so a person registering was told "Oops! Something went wrong"
 * and an operator had no reason at all.
 *
 * Standards are worth preferring; a standard the host does not implement is
 * worth nothing. The seam that matters is {@see DecisionStore}, which is this
 * connector's own and stays testable without any of this.
 *
 * Keys are hashed. The cache's key rules are not soulbind's to negotiate, and a
 * key that works on one driver and throws on another is a fault that only ever
 * appears in somebody else's deployment.
 */
final class FlarumCacheDecisionStore implements DecisionStore
{
    private const PREFIX = 'soulbind.decision.';

    public function __construct(private readonly Repository $cache)
    {
    }

    public function get(string $key): ?string
    {
        try {
            $value = $this->cache->get(self::hash($key));
        } catch (Throwable) {
            // A cache that will not answer is a MISS, not an error. The caller
            // re-asks core, which is slower and correct -- whereas throwing
            // would turn a cache problem into a page failure, which is exactly
            // what the PSR-16 binding did.
            return null;
        }
        return is_string($value) ? $value : null;
    }

    public function put(string $key, string $value, int $ttlSeconds): void
    {
        try {
            $this->cache->put(self::hash($key), $value, $ttlSeconds);
        } catch (Throwable) {
            // Failing to cache is not failing. The next request asks core.
        }
    }

    public function forget(string $key): void
    {
        try {
            $this->cache->forget(self::hash($key));
        } catch (Throwable) {
            // Nothing useful to do, and an uncached decision is the safe
            // direction to fail in.
        }
    }

    private static function hash(string $key): string
    {
        // sha256 rather than md5: not for secrecy -- these keys are not secret
        // -- but because a collision serves one subject's decision to another,
        // which is the same harm the key separator exists to prevent.
        return self::PREFIX . hash('sha256', $key);
    }
}
