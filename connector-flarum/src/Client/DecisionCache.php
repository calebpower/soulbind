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
 * on the other, at the same moment, for the same reason.
 *
 * The default is CLOSED, and it is the only default. Departing from it is a
 * visible line of configuration, and a check asserts the shipped default is
 * closed.
 *
 * The user-facing message on a fail-closed denial blames THE SYSTEM, not the
 * person. Somebody refused because a server they have never heard of is
 * unreachable should not be told they are not allowed.
 *
 * Storage is a {@see DecisionStore}, because PHP has no process to hold a cache
 * in. The rules live here; only the bytes live there.
 */
final class DecisionCache
{
    /**
     * The key separator.
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

    /**
     * How long a generation marker outlives the decisions it invalidates.
     *
     * A generation must not expire before the entries it has orphaned, or those
     * entries become reachable again -- an invalidated decision coming back from
     * the dead. Decisions are capped well below this by their own TTL.
     */
    private const GENERATION_TTL_SECONDS = 86_400;

    private readonly DecisionStore $store;

    public function __construct(
        public readonly FailMode $failMode = FailMode::CLOSED,
        ?DecisionStore $store = null
    ) {
        $this->store = $store ?? new ArrayDecisionStore();
    }

    /**
     * Parses a configured fail mode.
     *
     * An unreadable value becomes CLOSED -- not an exception, and certainly not
     * OPEN. A typo in a fail mode must never be the thing that opens a gate.
     */
    public static function withConfiguredFailMode(
        ?string $configured,
        ?DecisionStore $store = null
    ): self {
        return new self(FailMode::fromConfigName($configured), $store);
    }

    /**
     * Stores a decision, if it is cacheable at all.
     *
     * A TTL of zero means "do not cache", and is honoured rather than treated as
     * a missing value to be defaulted. Core says zero when it means it.
     */
    public function store(string $gate, string $identityRef, Decision $decision, int $now): void
    {
        $key = $this->entryKey($gate, $identityRef);

        if ($decision->ttlSeconds <= 0) {
            // Also EVICT any earlier entry. Leaving the previous decision in
            // place would serve a stale answer core has just told us not to
            // keep -- the opposite of what a zero TTL asks for.
            $this->store->forget($key);
            return;
        }

        // Capped at the generation lifetime, and this is a correctness cap,
        // not a tidiness one. Invalidation works by orphaning entries behind a
        // bumped generation; if a generation marker expired while an entry it
        // had orphaned was still live, the old generation would be read back as
        // 0 and the orphan would become reachable again -- an invalidated
        // decision returning from the dead, which is the one thing invalidation
        // exists to prevent. Core chooses the TTL and could choose a long one,
        // so the relationship is enforced here rather than assumed.
        $ttl = min($decision->ttlSeconds, self::GENERATION_TTL_SECONDS);

        $this->store->put(
            $key,
            (string) json_encode([
                'effect' => $decision->effect->value,
                'reason' => $decision->reason,
                'detail' => $decision->detail,
                'ttlSeconds' => $decision->ttlSeconds,
                'missingKinds' => $decision->missingKinds,
                'expiresAt' => $now + $ttl,
            ]),
            $ttl
        );
    }

    /** An unexpired cached decision, or null. */
    public function cached(string $gate, string $identityRef, int $now): ?Decision
    {
        $raw = $this->store->get($this->entryKey($gate, $identityRef));
        if ($raw === null) {
            return null;
        }

        $entry = json_decode($raw, true);
        if (!is_array($entry) || !is_int($entry['expiresAt'] ?? null)) {
            // Unreadable is treated as absent, never as an allow. A cache entry
            // that cannot be parsed is a cache entry nobody should act on --
            // and a shared cache can be written by another version of this
            // extension mid-upgrade.
            return null;
        }

        // Expiry is exclusive of the instant it names: an entry that expires
        // "at" now is expired. The other side compares the same way, and an
        // off-by-one here is a decision served one second past its licence.
        //
        // Checked HERE as well as by the store's own TTL: the store's clock is
        // not this clock, and the rule belongs where the rest of the rules are.
        if ($entry['expiresAt'] <= $now) {
            return null;
        }

        return Decision::fromPayload($entry);
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

    /**
     * Drops a subject's cached decisions.
     *
     * What a webhook calls when core says something about this subject changed.
     * Dropping is right and refreshing would be wrong: the next question re-asks
     * core, whereas a refresh here would guess at an answer core has not given.
     *
     * Implemented by bumping a per-identity GENERATION rather than enumerating
     * keys, because a shared cache cannot be enumerated -- no prefix scan, no
     * tags, nothing portable across drivers. Every entry key embeds the
     * generation current when it was written, so a bump orphans all of them at
     * once, for every gate, without knowing which gates exist. The orphans
     * expire on their own TTL.
     */
    public function invalidateIdentity(string $identityRef): void
    {
        $key = $this->generationKey($identityRef);
        $this->store->put(
            $key,
            (string) ($this->generation($identityRef) + 1),
            self::GENERATION_TTL_SECONDS
        );
    }

    private function generation(string $identityRef): int
    {
        $raw = $this->store->get($this->generationKey($identityRef));
        return is_string($raw) && preg_match('/^\d+$/', $raw) === 1 ? (int) $raw : 0;
    }

    private function generationKey(string $identityRef): string
    {
        return 'soulbind' . self::KEY_SEPARATOR . 'gen' . self::KEY_SEPARATOR . $identityRef;
    }

    private function entryKey(string $gate, string $identityRef): string
    {
        return 'soulbind' . self::KEY_SEPARATOR . 'd' . self::KEY_SEPARATOR
            . $this->generation($identityRef) . self::KEY_SEPARATOR
            . $gate . self::KEY_SEPARATOR . $identityRef;
    }
}
