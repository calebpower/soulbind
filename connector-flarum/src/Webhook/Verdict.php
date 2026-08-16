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
 * Why a webhook was accepted or refused.
 *
 * Distinct reasons rather than a boolean, because an operator staring at a
 * webhook that is not arriving needs to know whether the signature is wrong,
 * the clocks disagree, or it already arrived. Those have three different fixes.
 */
enum Verdict: string
{
    case ACCEPTED = 'accepted';
    case MALFORMED = 'malformed';
    case STALE_TIMESTAMP = 'stale-timestamp';
    case REPLAYED_NONCE = 'replayed-nonce';
    case BAD_SIGNATURE = 'bad-signature';
    /** No secret configured. The endpoint accepts nothing until one is set. */
    case NOT_CONFIGURED = 'not-configured';

    public function isAccepted(): bool
    {
        return $this === self::ACCEPTED;
    }

    /**
     * The HTTP status to answer with.
     *
     * Everything refused is 401 or 400 -- never 500. A rejected webhook is this
     * endpoint working correctly, and answering 5xx would make core retry a
     * delivery that will never be accepted, forever.
     */
    public function httpStatus(): int
    {
        return match ($this) {
            self::ACCEPTED => 200,
            self::MALFORMED => 400,
            // A replay is answered 200: the delivery it duplicates was already
            // accepted, so core has nothing to usefully retry. Answering 4xx
            // would make an at-least-once sender look permanently broken to its
            // own operator for behaving exactly as designed.
            self::REPLAYED_NONCE => 200,
            default => 401,
        };
    }
}
