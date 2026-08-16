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

/**
 * A nonce store that lives for one request.
 *
 * For tests, and for nothing else. It is deliberately NOT the production
 * default: a store that forgets between requests provides no replay protection
 * at all, and a connector that silently fell back to it would report a replay
 * guard it does not have.
 *
 * Bounded for the same reason the other side's is -- an unauthenticated caller
 * can put entries here, and an unbounded store is a way to exhaust memory. Full
 * means refuse, never evict: evicting the oldest entry is how a replay gets in.
 */
final class InMemoryNonceStore implements NonceStore
{
    public const MAX_ENTRIES = 100_000;

    /** @var array<string, int> nonce => expiry */
    private array $seen = [];

    public function recordIfNew(string $nonce, int $now, int $ttlSeconds): bool
    {
        foreach ($this->seen as $key => $expiresAt) {
            if ($expiresAt <= $now) {
                unset($this->seen[$key]);
            }
        }

        if (count($this->seen) >= self::MAX_ENTRIES) {
            return false; // fail closed
        }

        if (array_key_exists($nonce, $this->seen)) {
            return false;
        }

        $this->seen[$nonce] = $now + $ttlSeconds;
        return true;
    }

    public function size(): int
    {
        return count($this->seen);
    }
}
