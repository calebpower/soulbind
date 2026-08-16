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
 * The client, run under PHPUnit.
 *
 * Second entry point to {@see ClientChecks}; `tests/run-checks.php` is the
 * other, and the runner refuses to pass unless both invoke every check.
 */
final class SoulbindClientTest extends TestCase
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
    #[DisplayName('the request is a well-formed envelope, signed over the bytes actually sent')]
    public function requestIsWellFormedAndSigned(): void
    {
        $this->assertNoFailures(
            ClientChecks::requestIsWellFormedAndSigned(),
            'core would refuse this request, or accept one it should not'
        );
    }

    #[Test]
    #[DisplayName('nonces do not repeat')]
    public function noncesDoNotRepeat(): void
    {
        $this->assertNoFailures(
            ClientChecks::noncesDoNotRepeat(),
            'a repeated nonce is refused as a replay; a predictable one is a replay window'
        );
    }

    #[Test]
    #[DisplayName('an answer from core is fresh, and is cached')]
    public function anAnswerIsFreshAndCached(): void
    {
        $this->assertNoFailures(
            ClientChecks::anAnswerIsFreshAndCached(),
            'the cache can never help during an outage if answers do not reach it'
        );
    }

    #[Test]
    #[DisplayName('a refusal never consults the cache and never reaches the fail mode')]
    public function aRefusalNeverConsultsTheCache(): void
    {
        $this->assertNoFailures(
            ClientChecks::aRefusalNeverConsultsTheCache(),
            'a stale answer overruled a current one'
        );
    }

    #[Test]
    #[DisplayName('anything that is not a protocol envelope is an outage, not a refusal')]
    public function nonEnvelopeResponsesAreOutages(): void
    {
        $this->assertNoFailures(
            ClientChecks::nonEnvelopeResponsesAreOutages(),
            'a proxy error page was read as a policy decision'
        );
    }

    #[Test]
    #[DisplayName('an outage falls back to the cache, then to the fail mode')]
    public function outagesFallBackToCacheThenFailMode(): void
    {
        $this->assertNoFailures(
            ClientChecks::outagesFallBackToCacheThenFailMode(),
            'the outage path did not follow cache-then-fail-mode'
        );
    }
}
