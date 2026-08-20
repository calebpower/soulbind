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

use PHPUnit\Framework\Attributes\DisplayName;
use PHPUnit\Framework\Attributes\Test;
use PHPUnit\Framework\TestCase;

/**
 * The decision cache and fail mode, run under PHPUnit.
 *
 * Second entry point to {@see CacheChecks}; `tests/run-checks.php` is the
 * other, and the runner refuses to pass unless both invoke every check.
 */
final class DecisionCacheTest extends TestCase
{
    /** @param list<string> $failures */
    private function assertNoFailures(array $failures, string $what): void
    {
        $this->assertSame(
            [],
            $failures,
            $what . ' -- ' . count($failures) . " problem(s):\n  " . implode("\n  ", $failures)
        );
    }

    #[Test]
    #[DisplayName('the shipped default fails CLOSED, and a typo cannot open a gate')]
    public function defaultFailModeIsClosed(): void
    {
        $this->assertNoFailures(
            CacheChecks::defaultFailModeIsClosed(),
            'the fail mode can be opened by something other than a deliberate configuration'
        );
    }

    #[Test]
    #[DisplayName('a stored decision comes back, for that gate and identity only')]
    public function storeAndRetrieve(): void
    {
        $this->assertNoFailures(CacheChecks::storeAndRetrieve(), 'the cache misfiled a decision');
    }

    #[Test]
    #[DisplayName('a zero TTL is not cached, and evicts what was')]
    public function zeroTtlIsNotCached(): void
    {
        $this->assertNoFailures(
            CacheChecks::zeroTtlIsNotCached(),
            'the cache kept a decision core told it not to keep'
        );
    }

    #[Test]
    #[DisplayName('a zero ttl forgets the key rather than writing a doomed entry')]
    public function zeroTtlForgetsRatherThanWriting(): void
    {
        $this->assertNoFailures(
            CacheChecks::zeroTtlForgetsRatherThanWriting(),
            'the cache does not hold: a zero ttl forgets rather than writes'
        );
    }

    #[Test]
    #[DisplayName('a poisoned generation marker is refused')]
    public function aPoisonedGenerationIsRefused(): void
    {
        $this->assertNoFailures(
            CacheChecks::aPoisonedGenerationIsRefused(),
            'the cache does not hold: a poisoned generation marker is refused'
        );
    }

    #[Test]
    #[DisplayName('expiry is exclusive of the instant it names')]
    public function expiryIsExclusive(): void
    {
        $this->assertNoFailures(
            CacheChecks::expiryIsExclusive(),
            'a decision was served outside its lifetime'
        );
    }

    #[Test]
    #[DisplayName('two different gate/identity pairs cannot collide on one key')]
    public function keysCannotCollide(): void
    {
        $this->assertNoFailures(
            CacheChecks::keysCannotCollide(),
            'a key collision served one subject the other subject\'s decision'
        );
    }

    #[Test]
    #[DisplayName('an outage uses the cache first, then the fail mode')]
    public function unreachableUsesCacheThenFailMode(): void
    {
        $this->assertNoFailures(
            CacheChecks::unreachableUsesCacheThenFailMode(),
            'the outage path did not follow cache-then-fail-mode'
        );
    }

    #[Test]
    #[DisplayName('a fail-mode answer is never cached')]
    public function failModeAnswerIsNeverCached(): void
    {
        $this->assertNoFailures(
            CacheChecks::failModeAnswerIsNeverCached(),
            'caching a fail-mode answer would extend an outage past the end of the outage'
        );
    }

    #[Test]
    #[DisplayName('the fail-closed denial blames the system, not the person')]
    public function failClosedMessageBlamesTheSystem(): void
    {
        $this->assertNoFailures(
            CacheChecks::failClosedMessageBlamesTheSystem(),
            'somebody refused by an outage would be told they are not allowed'
        );
    }

    #[Test]
    #[DisplayName('invalidating one identity leaves the others alone')]
    public function invalidationIsScopedToOneIdentity(): void
    {
        $this->assertNoFailures(
            CacheChecks::invalidationIsScopedToOneIdentity(),
            'webhook invalidation is either too narrow to take effect or broad enough to '
                . 'stampede'
        );
    }

    #[Test]
    #[DisplayName('an unparseable decide response denies, never allows')]
    public function unreadableDecisionsDeny(): void
    {
        $this->assertNoFailures(
            CacheChecks::unreadableDecisionsDeny(),
            'a response nobody can parse let somebody through'
        );
    }
    #[Test]
    #[DisplayName('a decision stored by one request is visible to the next')]
    public function theCacheSurvivesBetweenRequests(): void
    {
        $this->assertNoFailures(
            CacheChecks::theCacheSurvivesBetweenRequests(),
            'a cache that lives in an object field caches nothing in PHP'
        );
    }

    #[Test]
    #[DisplayName('invalidation reaches later requests, and only the named identity')]
    public function invalidationReachesLaterRequests(): void
    {
        $this->assertNoFailures(
            CacheChecks::invalidationReachesLaterRequests(),
            'a webhook either does not take effect or takes effect too broadly'
        );
    }

    #[Test]
    #[DisplayName('a cache entry nobody can parse is absent, never an allow')]
    public function anUnreadableEntryIsIgnored(): void
    {
        $this->assertNoFailures(
            CacheChecks::anUnreadableEntryIsIgnored(),
            'a corrupt shared-cache entry let somebody through'
        );
    }
    #[Test]
    #[DisplayName('an invalidated decision cannot come back from the dead')]
    public function anInvalidatedDecisionCannotResurrect(): void
    {
        $this->assertNoFailures(
            CacheChecks::anInvalidatedDecisionCannotResurrect(),
            'a generation marker expired before the entries it orphaned'
        );
    }

    #[Test]
    #[DisplayName('the store honours its own expiry, independently of the cache')]
    public function theStoreHonoursItsOwnExpiry(): void
    {
        $this->assertNoFailures(
            CacheChecks::theStoreHonoursItsOwnExpiry(),
            'a redundancy nothing checks has quietly stopped being one'
        );
    }
}
