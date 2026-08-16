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
 * The member-facing link flow, run under PHPUnit.
 *
 * Second entry point to {@see LinkChecks}; `tests/run-checks.php` is the other,
 * and the runner refuses to pass unless both invoke every check.
 */
final class LinkTest extends TestCase
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
    #[DisplayName('link status reports what core says, and defaults to unlinked')]
    public function statusReportsWhatCoreSays(): void
    {
        $this->assertNoFailures(
            LinkChecks::statusReportsWhatCoreSays(),
            'a member would be told the wrong thing about linking'
        );
    }

    #[Test]
    #[DisplayName('an issued code comes back, or the response is not called a success')]
    public function aCodeComesBackOrTheReasonDoes(): void
    {
        $this->assertNoFailures(
            LinkChecks::aCodeComesBackOrTheReasonDoes(),
            'a member would be told the wrong thing about linking'
        );
    }

    #[Test]
    #[DisplayName('a refusal keeps core wording and an outage is not called a refusal')]
    public function refusalAndOutageStayDistinct(): void
    {
        $this->assertNoFailures(
            LinkChecks::refusalAndOutageStayDistinct(),
            'a member would be told the wrong thing about linking'
        );
    }

    #[Test]
    #[DisplayName('a code that cannot be a code is refused without a round trip')]
    public function anImpossibleCodeIsRefusedLocally(): void
    {
        $this->assertNoFailures(
            LinkChecks::anImpossibleCodeIsRefusedLocally(),
            'a member would be told the wrong thing about linking'
        );
    }

    #[Test]
    #[DisplayName('the normalised code is what travels to core')]
    public function theNormalisedCodeIsWhatTravels(): void
    {
        $this->assertNoFailures(
            LinkChecks::theNormalisedCodeIsWhatTravels(),
            'a member would be told the wrong thing about linking'
        );
    }

    #[Test]
    #[DisplayName('the platform kind is this connector own, never the caller supplied one')]
    public function thePlatformKindIsNotCallerSupplied(): void
    {
        $this->assertNoFailures(
            LinkChecks::thePlatformKindIsNotCallerSupplied(),
            'a member would be told the wrong thing about linking'
        );
    }
}
