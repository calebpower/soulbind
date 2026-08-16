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

namespace Soulbind\Flarum\Webhook;

use Psr\SimpleCache\CacheInterface;
use Psr\SimpleCache\InvalidArgumentException;

/**
 * Nonces in the host's shared cache.
 *
 * The replay guard needs storage that outlives a request, and PHP gives it
 * none of its own.
 *
 * **Fails CLOSED.** Every path that cannot prove a nonce is new returns false,
 * which the verifier reads as a replay and refuses. That is the opposite of the
 * decision cache's failure direction, and deliberately: a decision cache that
 * cannot answer degrades to asking core, while a replay guard that cannot
 * answer degrades to having no replay guard.
 */
final class CacheNonceStore implements NonceStore
{
    private const PREFIX = 'soulbind.nonce.';

    public function __construct(private readonly CacheInterface $cache)
    {
    }

    public function recordIfNew(string $nonce, int $now, int $ttlSeconds): bool
    {
        $key = self::PREFIX . hash('sha256', $nonce);

        try {
            if ($this->cache->has($key)) {
                return false;
            }
            // NOT atomic, and PSR-16 offers nothing that is.
            //
            // Two identical deliveries arriving in the same instant could both
            // pass this. The window is milliseconds wide and requires the
            // attacker to have a valid signed delivery already -- so the residual
            // risk is a duplicate cache invalidation, which is idempotent and
            // costs one extra decide.
            //
            // Written down rather than glossed: if this endpoint ever does
            // something that is NOT idempotent, this line stops being adequate
            // and needs a store with an atomic add.
            return $this->cache->set($key, '1', $ttlSeconds);
        } catch (InvalidArgumentException) {
            return false;
        }
    }
}
