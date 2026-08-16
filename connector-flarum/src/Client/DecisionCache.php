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

use Soulbind\Flarum\Policy\Decision;
use Soulbind\Flarum\Policy\Effect;

/**
 * A short-lived decision cache, and the fail mode behind it.
 *
 * The counterpart of the other implementation's cache, and deliberately the
 * same rules rather than similar ones -- a forum and a game server that
 * disagree about what an outage means is a person let in on one and turned away
 * on the other, for the same reason, at the same moment.
 *
 * The default is CLOSED, and it is the only default. Departing from it is a
 * visible line of configuration, and a check asserts the shipped default is
 * closed.
 *
 * The user-facing message on a fail-closed denial blames THE SYSTEM, not the
 * person. Somebody refused because a server they have never heard of is
 * unreachable should not be told they are not allowed.
 */
final class DecisionCache
{
    /**
     * The cache key separator.
     *
     * A unit separator, which cannot appear in a gate name or a platform
     * identifier. Joining with a colon would let ("a:b", "c") and ("a", "b:c")
     * collide on one key -- and a collision here serves one subject's decision
     * to another.
     */
    private const KEY_SEPARATOR = "\x1F";

    /** Blames the system, by design. Identical wording on both sides. */
    public const FAIL_CLOSED_MESSAGE =
        'This check is temporarily unavailable, so access is on hold. '
        . 'This is a problem on our side, not yours -- please try again shortly.';

    /** @var array<string, array{decision: Decision, expiresAt: int}> */
    private array $entries = [];

    public function __construct(
        public readonly FailMode $failMode = FailMode::CLOSED
    ) {
    }

    /**
     * Parses a configured fail mode.
     *
     * An unreadable value becomes CLOSED -- not an exception, and certainly not
     * OPEN. A typo in a fail mode must never be the thing that opens a gate.
     */
    public static function withConfiguredFailMode(?string $configured): self
    {
        return new self(FailMode::fromConfigName($configured));
    }

    /**
     * Stores a decision, if it is cacheable at all.
     *
     * A TTL of zero means "do not cache", and is honoured rather than treated
     * as a missing value to be defaulted. Core says zero when it means it.
     */
    public function store(string $gate, string $identityRef, Decision $decision, int $now): void
    {
        if ($decision->ttlSeconds <= 0) {
            // Also EVICT any earlier entry for this key. Leaving the previous
            // decision in place would serve a stale answer that core has just
            // told us not to keep -- the opposite of what a zero TTL asks for.
            unset($this->entries[self::key($gate, $identityRef)]);
            return;
        }

        $this->entries[self::key($gate, $identityRef)] = [
            'decision' => $decision,
            'expiresAt' => $now + $decision->ttlSeconds,
        ];
    }

    /** An unexpired cached decision, or null. */
    public function cached(string $gate, string $identityRef, int $now): ?Decision
    {
        $key = self::key($gate, $identityRef);
        $entry = $this->entries[$key] ?? null;
        if ($entry === null) {
            return null;
        }
        // Expiry is exclusive of the instant it names: an entry that expires
        // "at" now is expired. The other side compares the same way, and an
        // off-by-one here is a decision served one second past its licence.
        if ($entry['expiresAt'] <= $now) {
            unset($this->entries[$key]);
            return null;
        }
        return $entry['decision'];
    }

    /**
     * What to answer when core could not be reached.
     *
     * An unexpired cached decision first; otherwise the fail mode decides.
     */
    public function whenUnreachable(string $gate, string $identityRef, int $now): Answer
    {
        $cached = $this->cached($gate, $identityRef, $now);
        if ($cached !== null) {
            return new Answer($cached, Source::CACHED);
        }

        $open = $this->failMode === FailMode::OPEN;

        return new Answer(
            new Decision(
                $open ? Effect::ALLOW : Effect::DENY,
                'unreachable',
                $open
                    ? 'core was unreachable and this gate is configured to fail open'
                    : self::FAIL_CLOSED_MESSAGE,
                // Never cached. A fail-mode answer is the absence of a decision,
                // not a decision, and caching it would extend an outage past the
                // end of the outage.
                0
            ),
            Source::FAIL_MODE
        );
    }

    public function size(): int
    {
        return count($this->entries);
    }

    public function clear(): void
    {
        $this->entries = [];
    }

    /**
     * Drops a subject's cached decisions.
     *
     * What a webhook calls when core says something about this subject changed.
     * Dropping is right and refreshing would be wrong: the next question
     * re-asks core, whereas a refresh here would guess at an answer core has
     * not given.
     */
    public function invalidateIdentity(string $identityRef): void
    {
        $suffix = self::KEY_SEPARATOR . $identityRef;
        foreach (array_keys($this->entries) as $key) {
            if (str_ends_with($key, $suffix)) {
                unset($this->entries[$key]);
            }
        }
    }

    private static function key(string $gate, string $identityRef): string
    {
        return $gate . self::KEY_SEPARATOR . $identityRef;
    }
}
