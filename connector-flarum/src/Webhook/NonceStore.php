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
 * Records nonces so a captured webhook cannot be replayed.
 *
 * An interface because PHP has no process to hold this in: each request is a
 * fresh interpreter, so production needs shared storage while tests need
 * something that does not. The rule the implementation must honour is the same
 * as the other side's:
 *
 * **Record and test in one atomic step.** A `has()` followed by an `add()`
 * lets two copies of the same request, arriving at the same moment, both pass —
 * which is precisely the replay this exists to stop.
 *
 * Entries need only outlive the timestamp window; anything older is refused by
 * the clock before it reaches the store.
 */
interface NonceStore
{
    /**
     * Records a nonce, returning whether it was new.
     *
     * @return bool true if this nonce had not been seen; false if it is a
     *     replay, and false as well if the store cannot be sure — an
     *     unavailable store fails CLOSED, because a replay guard that opens
     *     under load is not a replay guard.
     */
    public function recordIfNew(string $nonce, int $now, int $ttlSeconds): bool;
}
