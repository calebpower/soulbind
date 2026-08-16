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
 * The extension's identity, run under PHPUnit.
 *
 * Second entry point to {@see PackagingChecks}; `tests/run-checks.php` is the
 * other, and the runner refuses to pass unless both invoke every check.
 */
final class PackagingTest extends TestCase
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
    #[DisplayName('the extension id is what Flarum will compute, not the intuitive one')]
    public function theExtensionIdIsWhatFlarumWillCompute(): void
    {
        $this->assertNoFailures(
            PackagingChecks::theExtensionIdIsWhatFlarumWillCompute(),
            'the extension would install and then not exist as far as Flarum is concerned'
        );
    }

    #[Test]
    #[DisplayName('the id derivation rule from Flarum is implemented correctly')]
    public function theDerivationRuleIsRight(): void
    {
        $this->assertNoFailures(
            PackagingChecks::theDerivationRuleIsRight(),
            'the extension would install and then not exist as far as Flarum is concerned'
        );
    }
    #[Test]
    #[DisplayName('a gate refusal is registered, so its reason reaches the person')]
    public function theRefusalTypeIsRegistered(): void
    {
        $this->assertNoFailures(
            PackagingChecks::theRefusalTypeIsRegistered(),
            'a refusal would render as a generic error and tell the person nothing'
        );
    }
    #[Test]
    #[DisplayName('the endpoint is the configured base plus the protocol path')]
    public function theEndpointMatchesTheProtocolPath(): void
    {
        $this->assertNoFailures(
            PackagingChecks::theEndpointMatchesTheProtocolPath(),
            'the two connectors would read one config value as two different endpoints'
        );
    }
}
