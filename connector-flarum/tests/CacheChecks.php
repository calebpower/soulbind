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

namespace Soulbind\Flarum\Tests;

use Soulbind\Flarum\Client\Answer;
use Soulbind\Flarum\Client\DecisionCache;
use Soulbind\Flarum\Client\FailMode;
use Soulbind\Flarum\Client\Source;
use Soulbind\Flarum\Policy\Decision;
use Soulbind\Flarum\Policy\Effect;

/**
 * The decision cache and the fail mode behind it.
 *
 * These rules are shared with the other implementation, not merely similar to
 * it: a forum and a game server that disagree about what an outage means is one
 * person let in on one and turned away on the other, at the same moment, for
 * the same reason.
 *
 * Same shape as {@see VectorChecks} -- every check returns its failures so a
 * caller can report all of them and decide what a failure means.
 */
final class CacheChecks
{
    private const NOW = 1_700_000_000;

    private function __construct()
    {
    }

    private static function decision(Effect $effect, int $ttl): Decision
    {
        return new Decision($effect, 'requirements-met', 'because', $ttl);
    }

    /**
     * The shipped default denies. This is the single most important line here.
     *
     * @return list<string>
     */
    public static function defaultFailModeIsClosed(): array
    {
        $failures = [];

        if ((new DecisionCache())->failMode !== FailMode::CLOSED) {
            $failures[] = 'the no-argument cache does not fail CLOSED. Failing open by default '
                . 'means every deployment that never touched the setting is open during an '
                . 'outage, and nobody would find out until one happened.';
        }

        if (FailMode::fromConfigName(null) !== FailMode::CLOSED) {
            $failures[] = 'an absent fail mode is not CLOSED';
        }

        // A typo must never be the thing that opens a gate.
        foreach (['opne', 'OPEN_', 'true', '1', 'yes', 'allow', '', ' ', 'closed'] as $typo) {
            if (FailMode::fromConfigName($typo) !== FailMode::CLOSED) {
                $failures[] = "the configured value '{$typo}' opened the gate. Only an exact "
                    . "'open' may do that; anything else is a typo, and a typo must not be the "
                    . 'thing that opens a gate.';
            }
        }

        // ...but the real value must still work, or the setting is decorative.
        foreach (['open', 'OPEN', ' Open ', "\topen\n"] as $valid) {
            if (FailMode::fromConfigName($valid) !== FailMode::OPEN) {
                $failures[] = "the configured value '{$valid}' did not open the gate; the "
                    . 'setting does not work and an operator who set it would not know';
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function storeAndRetrieve(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 60), self::NOW);

        $hit = $cache->cached('join', 'chat:abc', self::NOW);
        if ($hit === null || !$hit->isAllowed()) {
            $failures[] = 'a stored decision did not come back';
        }

        if ($cache->cached('other-gate', 'chat:abc', self::NOW) !== null) {
            $failures[] = 'a decision stored for one gate answered for another';
        }
        if ($cache->cached('join', 'chat:different', self::NOW) !== null) {
            $failures[] = 'a decision stored for one identity answered for another';
        }

        return $failures;
    }

    /**
     * A zero TTL means do not cache, and also means forget what was cached.
     *
     * @return list<string>
     */
    public static function zeroTtlIsNotCached(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 0), self::NOW);
        if ($cache->cached('join', 'chat:abc', self::NOW) !== null) {
            $failures[] = 'a decision with ttl 0 was cached. Core says zero when it means it.';
        }

        // The eviction half: an earlier entry must not survive a later
        // do-not-cache answer, or the cache serves a decision core has just
        // withdrawn permission to keep.
        $cache = new DecisionCache();
        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 600), self::NOW);
        $cache->store('join', 'chat:abc', self::decision(Effect::DENY, 0), self::NOW);
        if ($cache->cached('join', 'chat:abc', self::NOW) !== null) {
            $failures[] = 'a ttl-0 answer left the previous entry in place, so the cache still '
                . 'serves an allow that core has withdrawn';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function expiryIsExclusive(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 60), self::NOW);

        if ($cache->cached('join', 'chat:abc', self::NOW + 59) === null) {
            $failures[] = 'a decision expired a second early';
        }
        if ($cache->cached('join', 'chat:abc', self::NOW + 60) !== null) {
            $failures[] = 'a decision was still served at the instant it expired. Expiry is '
                . 'exclusive of the instant it names, and the other side compares the same '
                . 'way -- an off-by-one here is a decision served past its licence.';
        }
        if ($cache->cached('join', 'chat:abc', self::NOW + 61) !== null) {
            $failures[] = 'an expired decision was served';
        }

        return $failures;
    }

    /**
     * Two different (gate, identity) pairs must never collide on one key.
     *
     * A collision here serves one subject's decision to another. Joining with a
     * colon would make ("a:b", "c") and ("a", "b:c") the same key; the unit
     * separator cannot appear in either field.
     *
     * @return list<string>
     */
    public static function keysCannotCollide(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $cache->store('a:b', 'c', self::decision(Effect::ALLOW, 600), self::NOW);
        $cache->store('a', 'b:c', self::decision(Effect::DENY, 600), self::NOW);

        $first = $cache->cached('a:b', 'c', self::NOW);
        $second = $cache->cached('a', 'b:c', self::NOW);

        if ($first === null || !$first->isAllowed()) {
            $failures[] = 'gate "a:b" identity "c" lost its decision to a colliding key';
        }
        if ($second === null || $second->isAllowed()) {
            $failures[] = 'gate "a" identity "b:c" was served the OTHER subject\'s decision. '
                . 'A key collision here is one person\'s access answered with another\'s.';
        }
        if ($cache->size() !== 2) {
            $failures[] = 'two distinct pairs produced ' . $cache->size() . ' cache entries';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function unreachableUsesCacheThenFailMode(): array
    {
        $failures = [];

        // A live cached decision wins over the fail mode.
        $cache = new DecisionCache();
        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 60), self::NOW);
        $answer = $cache->whenUnreachable('join', 'chat:abc', self::NOW);
        if (!$answer->decision->isAllowed() || $answer->source !== Source::CACHED) {
            $failures[] = 'an unexpired cached decision was not used during an outage; the '
                . 'cache exists precisely for this moment';
        }

        // Expired, so the fail mode decides.
        $answer = $cache->whenUnreachable('join', 'chat:abc', self::NOW + 600);
        if ($answer->decision->isAllowed() || $answer->source !== Source::FAIL_MODE) {
            $failures[] = 'an EXPIRED cached decision was served during an outage. An outage '
                . 'does not extend a decision\'s lifetime.';
        }

        // Nothing cached at all.
        $answer = (new DecisionCache())->whenUnreachable('join', 'nobody', self::NOW);
        if ($answer->decision->isAllowed()) {
            $failures[] = 'the default fail mode allowed during an outage';
        }
        if ($answer->source !== Source::FAIL_MODE) {
            $failures[] = 'a fail-mode answer did not report itself as one, so an operator '
                . 'debugging a denial cannot tell core said no from nobody said anything';
        }

        // Fail-open, when deliberately configured.
        $open = new DecisionCache(FailMode::OPEN);
        if (!$open->whenUnreachable('join', 'nobody', self::NOW)->decision->isAllowed()) {
            $failures[] = 'an explicitly fail-open cache denied during an outage, so the '
                . 'setting does not work';
        }

        return $failures;
    }

    /** @return list<string> */
    public static function failModeAnswerIsNeverCached(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $answer = $cache->whenUnreachable('join', 'chat:abc', self::NOW);

        if ($answer->decision->ttlSeconds !== 0) {
            $failures[] = 'a fail-mode decision carries a ttl of ' . $answer->decision->ttlSeconds
                . '. It must be 0: a fail-mode answer is the ABSENCE of a decision, and caching '
                . 'it would extend an outage past the end of the outage.';
        }
        if ($cache->size() !== 0) {
            $failures[] = 'asking during an outage populated the cache';
        }

        return $failures;
    }

    /**
     * The denial blames the system, not the person.
     *
     * @return list<string>
     */
    public static function failClosedMessageBlamesTheSystem(): array
    {
        $failures = [];

        $detail = (new DecisionCache())
            ->whenUnreachable('join', 'nobody', self::NOW)->decision->detail ?? '';

        if ($detail !== DecisionCache::FAIL_CLOSED_MESSAGE) {
            $failures[] = 'the fail-closed denial does not use the shared message';
        }
        foreach (['our side', 'try again'] as $phrase) {
            if (!str_contains($detail, $phrase)) {
                $failures[] = "the fail-closed message no longer says '{$phrase}'. Somebody "
                    . 'refused because a server they have never heard of is unreachable must '
                    . 'not be told they are not allowed.';
            }
        }

        return $failures;
    }

    /** @return list<string> */
    public static function invalidationIsScopedToOneIdentity(): array
    {
        $failures = [];

        $cache = new DecisionCache();
        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 600), self::NOW);
        $cache->store('post', 'chat:abc', self::decision(Effect::ALLOW, 600), self::NOW);
        $cache->store('join', 'chat:xyz', self::decision(Effect::ALLOW, 600), self::NOW);

        $cache->invalidateIdentity('chat:abc');

        if ($cache->cached('join', 'chat:abc', self::NOW) !== null
            || $cache->cached('post', 'chat:abc', self::NOW) !== null) {
            $failures[] = 'invalidating an identity left one of its gates cached, so a webhook '
                . 'saying "this person changed" does not take effect on every gate';
        }
        if ($cache->cached('join', 'chat:xyz', self::NOW) === null) {
            $failures[] = 'invalidating one identity dropped another\'s decisions. Over-broad '
                . 'invalidation is not harmless: it turns one webhook into a stampede of '
                . 'synchronous decides, which is what the cache exists to prevent.';
        }

        return $failures;
    }

    /**
     * An unparseable answer is a denial, never an allow.
     *
     * @return list<string>
     */
    public static function unreadableDecisionsDeny(): array
    {
        $failures = [];

        $cases = [
            'an absent effect' => ['reason' => 'x'],
            'an unreadable effect' => ['effect' => 'maybe', 'reason' => 'x'],
            'a non-string effect' => ['effect' => true, 'reason' => 'x'],
            'an empty payload' => [],
        ];

        foreach ($cases as $what => $payload) {
            if (Decision::fromPayload($payload)->isAllowed()) {
                $failures[] = "{$what} produced an ALLOW. A response nobody can parse must "
                    . 'never be the thing that lets somebody through.';
            }
        }

        // The obverse: a well-formed allow must still parse as one, or the rule
        // above is satisfied by a parser that denies everything.
        $ok = Decision::fromPayload([
            'effect' => 'allow',
            'reason' => 'requirements-met',
            'detail' => 'fine',
            'ttlSeconds' => 30,
            'missingKinds' => [],
        ]);
        if (!$ok->isAllowed() || $ok->ttlSeconds !== 30) {
            $failures[] = 'a well-formed allow did not parse; a parser that denies everything '
                . 'would pass every assertion above';
        }

        // A negative TTL is clamped rather than throwing: core should not send
        // one, but a connector crashing on a malformed field is a worse failure
        // than a connector declining to cache it.
        if (Decision::fromPayload(['effect' => 'allow', 'ttlSeconds' => -5])->ttlSeconds !== 0) {
            $failures[] = 'a negative ttl was not clamped to 0';
        }

        return $failures;
    }
}
