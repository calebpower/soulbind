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

use Illuminate\Contracts\Cache\Repository;
use Throwable;

/**
 * Nonces in the host's cache.
 *
 * The replay guard needs storage that outlives a request, and PHP gives it none
 * of its own.
 *
 * **Atomic**, via the host cache's {@code add()}, which writes only if the key
 * is absent and reports whether it did. That is the operation this class has
 * always wanted: an earlier version used PSR-16, which offers no such method, so
 * it did {@code has()} then {@code set()} and documented the race it could not
 * close. Moving to the host's own contract closed it, which is a better outcome
 * than the standard interface would have allowed.
 *
 * **Fails CLOSED.** Every path that cannot prove a nonce is new returns false,
 * and the verifier reads that as a replay and refuses. That is the opposite of
 * the decision cache's failure direction, deliberately: a decision cache that
 * cannot answer degrades to asking core, while a replay guard that cannot answer
 * degrades to having no replay guard.
 */
final class CacheNonceStore implements NonceStore
{
    private const PREFIX = 'soulbind.nonce.';

    public function __construct(private readonly Repository $cache)
    {
    }

    public function recordIfNew(string $nonce, int $now, int $ttlSeconds): bool
    {
        try {
            // add() is write-if-absent, and returns whether it wrote. Two
            // identical deliveries arriving in the same instant cannot both be
            // told they are new -- which is precisely the replay this exists to
            // stop, and precisely what has()-then-set() could not promise.
            return (bool) $this->cache->add(
                self::PREFIX . hash('sha256', $nonce),
                '1',
                $ttlSeconds
            );
        } catch (Throwable) {
            return false;
        }
    }
}
