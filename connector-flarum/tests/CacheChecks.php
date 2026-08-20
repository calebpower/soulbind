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
use Soulbind\Flarum\Client\ArrayDecisionStore;
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

    /**
     * @return list<string>
     *
     * A zero TTL must FORGET the key, not write a doomed entry.
     *
     * `zeroTtlIsNotCached` above asserts what can be read back, and both
     * spellings look identical through that lens: with `<= 0` the cache calls
     * forget(), and with `< 0` it falls through and calls put($key, $json, 0),
     * which ArrayDecisionStore treats as already expired. Mutation coverage
     * found exactly that, along with deleting the `return` after the forget.
     *
     * The double is what made them look safe. Flarum's real cache backends do
     * not agree that a zero TTL means "already gone" -- in several of them a
     * zero or absent TTL means FOREVER, which would cache permanently the one
     * kind of decision core explicitly said not to keep. So this asserts the
     * call rather than the readback.
     */
    public static function zeroTtlForgetsRatherThanWriting(): array
    {
        $failures = [];

        $store = new RecordingDecisionStore();
        $cache = new DecisionCache(FailMode::CLOSED, $store);

        $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 600), self::NOW);
        $beforeZero = count($store->decisionPuts());

        $cache->store('join', 'chat:abc', self::decision(Effect::DENY, 0), self::NOW);

        $puts = $store->decisionPuts();
        if (count($puts) !== $beforeZero) {
            $failures[] = 'a zero-ttl decision was WRITTEN to the store (ttl '
                . $puts[count($puts) - 1]['ttl'] . ') instead of forgetting the key. A '
                . 'backend that reads a zero ttl as "no expiry" would then cache forever '
                . 'the one answer core said not to keep.';
        }

        $forgets = array_filter(
            $store->calls(),
            static fn (array $c): bool => $c['op'] === 'forget'
        );
        if ($forgets === []) {
            $failures[] = 'a zero-ttl decision never called forget(), so an entry cached '
                . 'a moment earlier is left for whatever the backend does next';
        }

        return $failures;
    }

    /**
     * @return list<string>
     *
     * A generation marker with trailing garbage is not a generation.
     *
     * The validator is anchored at both ends. Dropping the trailing anchor lets
     * "123abc" through as 123, and mutation coverage showed nothing noticed --
     * while `PoisoningDecisionStore` in this very suite establishes that a
     * hostile store IS a modelled threat. A poisoned marker that parses moves
     * every lookup into a namespace the attacker chose.
     *
     * Dropping the LEADING anchor is harmless by contrast, and is left alone:
     * "abc123" then matches, casts to 0, and 0 is what the rejection path
     * returns anyway.
     */
    public static function aPoisonedGenerationIsRefused(): array
    {
        $failures = [];

        foreach (['123abc', '12 34', "7\x00", 'NaN', '-5', '1e3'] as $poison) {
            $store = new ArrayDecisionStore();
            $cache = new DecisionCache(FailMode::CLOSED, $store);

            // Cache an answer, then poison the generation marker behind it.
            $cache->store('join', 'chat:abc', self::decision(Effect::ALLOW, 600), self::NOW);
            $store->put("soulbind\x1Fgen\x1Fchat:abc", $poison, 600);

            // The entry was written under generation 0. A poisoned marker that
            // PARSES moves the lookup elsewhere and the answer vanishes; one
            // that is correctly refused leaves generation 0 and the answer
            // reachable. Either way the cache must not honour the poison.
            $answer = $cache->cached('join', 'chat:abc', self::NOW);
            if ($answer === null) {
                $failures[] = "the generation marker '" . $poison . "' was honoured rather "
                    . 'than refused, so a hostile store can redirect every lookup into a '
                    . 'namespace it chooses';
            }
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

        $store = new ArrayDecisionStore(static fn (): int => self::NOW);
        $cache = new DecisionCache(FailMode::CLOSED, $store);
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
        if ($store->size() !== 2) {
            $failures[] = 'two distinct pairs produced ' . $store->size() . ' cache entries';
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

        $store = new ArrayDecisionStore(static fn (): int => self::NOW);
        $cache = new DecisionCache(FailMode::CLOSED, $store);
        $answer = $cache->whenUnreachable('join', 'chat:abc', self::NOW);

        if ($answer->decision->ttlSeconds !== 0) {
            $failures[] = 'a fail-mode decision carries a ttl of ' . $answer->decision->ttlSeconds
                . '. It must be 0: a fail-mode answer is the ABSENCE of a decision, and caching '
                . 'it would extend an outage past the end of the outage.';
        }
        if ($store->size() !== 0) {
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
     * The cache must survive between requests, or it caches nothing.
     *
     * PHP starts a fresh interpreter per request. A cache held in an object
     * field would satisfy every other check in this file and cache nothing in
     * production -- and the webhook that exists to keep it warm would be warming
     * something no later request can read. Two cache objects over one store is
     * how that gets caught.
     *
     * @return list<string>
     */
    public static function theCacheSurvivesBetweenRequests(): array
    {
        $failures = [];

        $store = new ArrayDecisionStore(static fn (): int => self::NOW);

        // "Request one" stores a decision, then goes away.
        (new DecisionCache(FailMode::CLOSED, $store))
            ->store('join', 'forum:u1', self::decision(Effect::ALLOW, 600), self::NOW);

        // "Request two" is a different object entirely.
        $later = new DecisionCache(FailMode::CLOSED, $store);
        $hit = $later->cached('join', 'forum:u1', self::NOW);

        if ($hit === null) {
            $failures[] = 'a decision stored by one request was invisible to the next. A cache '
                . 'that lives in an object field caches nothing in PHP, and the webhook that '
                . 'warms it would be warming something nobody reads.';
        } elseif (!$hit->isAllowed()) {
            $failures[] = 'a decision came back from the store with its effect changed';
        }

        // And the outage path must see it too -- that is the moment it matters.
        $answer = $later->whenUnreachable('join', 'forum:u1', self::NOW);
        if ($answer->source !== Source::CACHED) {
            $failures[] = 'an outage in a later request did not find the earlier decision';
        }

        return $failures;
    }

    /**
     * Invalidation reaches a later request, and only the named identity.
     *
     * @return list<string>
     */
    public static function invalidationReachesLaterRequests(): array
    {
        $failures = [];

        $store = new ArrayDecisionStore(static fn (): int => self::NOW);

        $first = new DecisionCache(FailMode::CLOSED, $store);
        $first->store('join', 'forum:u1', self::decision(Effect::ALLOW, 600), self::NOW);
        $first->store('post', 'forum:u1', self::decision(Effect::ALLOW, 600), self::NOW);
        $first->store('join', 'forum:u2', self::decision(Effect::ALLOW, 600), self::NOW);

        // The webhook arrives as its own request.
        (new DecisionCache(FailMode::CLOSED, $store))->invalidateIdentity('forum:u1');

        $later = new DecisionCache(FailMode::CLOSED, $store);

        foreach (['join', 'post'] as $gate) {
            if ($later->cached($gate, 'forum:u1', self::NOW) !== null) {
                $failures[] = "the {$gate} decision for the invalidated identity survived into "
                    . 'a later request. Bumping the generation must orphan every gate at once, '
                    . 'because a shared cache cannot be enumerated to find them.';
            }
        }

        if ($later->cached('join', 'forum:u2', self::NOW) === null) {
            $failures[] = 'invalidating one identity dropped another\'s decisions. Over-broad '
                . 'invalidation turns one webhook into a stampede of synchronous decides, '
                . 'which is what the cache exists to prevent.';
        }

        // A decision stored AFTER the bump must be readable -- otherwise
        // invalidation is permanent and the cache never refills.
        $later->store('join', 'forum:u1', self::decision(Effect::ALLOW, 600), self::NOW);
        if ($later->cached('join', 'forum:u1', self::NOW) === null) {
            $failures[] = 'nothing could be cached for an identity after it was invalidated, '
                . 'so one webhook would disable caching for that person forever';
        }

        return $failures;
    }

    /**
     * An invalidated decision must not come back from the dead.
     *
     * Invalidation works by orphaning entries behind a bumped generation. If a
     * generation marker expired while an entry it had orphaned was still live,
     * the generation would read back as 0 and the orphan would become reachable
     * again -- the one thing invalidation exists to prevent, happening silently,
     * hours later.
     *
     * Core chooses the decision TTL and could choose a long one, so the cache
     * caps what it stores at the generation lifetime rather than assuming the
     * relationship holds. This walks the clock forward past a generation's life
     * to prove it.
     *
     * @return list<string>
     */
    public static function anInvalidatedDecisionCannotResurrect(): array
    {
        $failures = [];

        $clock = self::NOW;
        $store = new ArrayDecisionStore(static function () use (&$clock): int {
            return $clock;
        });
        $cache = new DecisionCache(FailMode::CLOSED, $store);

        // Core hands back a decision it would like cached for a year.
        $cache->store('join', 'forum:u1', self::decision(Effect::ALLOW, 365 * 86_400), self::NOW);
        $cache->invalidateIdentity('forum:u1');

        if ($cache->cached('join', 'forum:u1', $clock) !== null) {
            return ['invalidation did not take effect at all'];
        }

        // Walk past any plausible generation lifetime, a day at a time.
        foreach ([3_600, 86_400, 86_401, 172_800, 400 * 86_400] as $ahead) {
            $clock = self::NOW + $ahead;
            $hit = $cache->cached('join', 'forum:u1', $clock);
            if ($hit !== null) {
                $failures[] = "an invalidated decision became readable again {$ahead}s later. "
                    . 'The generation marker expired before the entry it orphaned, so the '
                    . 'generation read back as 0 and the old key was reachable once more.';
                break;
            }
        }

        return $failures;
    }

    /**
     * The store honours its own expiry, independently of the cache.
     *
     * The cache checks expiry too, so removing this from the store changes no
     * cache behaviour -- which is exactly why it needs asserting directly. A
     * redundancy that nothing checks is a redundancy that quietly stops being
     * one, and this store is also what a real cache driver is substituted for.
     *
     * @return list<string>
     */
    public static function theStoreHonoursItsOwnExpiry(): array
    {
        $failures = [];

        $clock = self::NOW;
        $store = new ArrayDecisionStore(static function () use (&$clock): int {
            return $clock;
        });

        $store->put('k', 'v', 60);

        if ($store->get('k') !== 'v') {
            $failures[] = 'a value did not come back from the store at all';
        }

        $clock = self::NOW + 59;
        if ($store->get('k') !== 'v') {
            $failures[] = 'the store expired a value a second early';
        }

        $clock = self::NOW + 60;
        if ($store->get('k') !== null) {
            $failures[] = 'the store served a value at the instant it expired; expiry is '
                . 'exclusive of the instant it names, here as everywhere else';
        }

        $clock = self::NOW + 61;
        if ($store->get('k') !== null) {
            $failures[] = 'the store served an expired value';
        }
        if ($store->size() !== 0) {
            $failures[] = 'an expired value was still occupying space after being read, so the '
                . 'store only ever grows';
        }

        $store->put('gone', 'v', 60);
        $store->forget('gone');
        if ($store->get('gone') !== null) {
            $failures[] = 'forget() did not forget';
        }

        return $failures;
    }

    /**
     * A cache entry nobody can parse is absent, never an allow.
     *
     * A shared cache can be written by another version of this extension
     * mid-upgrade, or by anything else that shares the key space.
     *
     * @return list<string>
     */
    public static function anUnreadableEntryIsIgnored(): array
    {
        $failures = [];

        foreach ([
            'a truncated JSON object' => '{"effect":"allow"',
            'a bare string' => 'allow',
            'an empty value' => '',
            'JSON with no expiry' => '{"effect":"allow","reason":"x"}',
            'an expiry that is not a number' => '{"effect":"allow","expiresAt":"soon"}',
            'a JSON array' => '["allow"]',
        ] as $what => $poison) {
            $cache = new DecisionCache(FailMode::CLOSED, new PoisoningDecisionStore($poison));
            $cache->store('join', 'forum:u1', self::decision(Effect::ALLOW, 600), self::NOW);

            $hit = $cache->cached('join', 'forum:u1', self::NOW);
            if ($hit !== null && $hit->isAllowed()) {
                $failures[] = "{$what} was read as an ALLOW. An entry nobody can parse is an "
                    . 'entry nobody should act on.';
            }
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
