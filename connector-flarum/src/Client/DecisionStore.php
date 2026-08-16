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
 * Where cached decisions actually live.
 *
 * This exists because PHP has no process to keep them in. Every request is a
 * fresh interpreter, so a cache held in an object field is empty by the time the
 * next page loads -- it would satisfy every unit test and cache nothing in
 * production, and the webhook that exists to keep it warm would be warming
 * something that never gets read.
 *
 * The rules -- TTL, expiry, key construction, the fail mode -- stay in
 * {@see DecisionCache}. This is only storage, and it is deliberately the
 * narrowest interface that a key-value cache can satisfy: no enumeration, no
 * prefix scan, no tags. Those are exactly the operations shared caches do not
 * agree on, and depending on them would make the connector work on one host's
 * cache driver and not another's.
 */
interface DecisionStore
{
    public function get(string $key): ?string;

    /** @param int $ttlSeconds always positive; the caller does not store forever. */
    public function put(string $key, string $value, int $ttlSeconds): void;

    public function forget(string $key): void;
}
