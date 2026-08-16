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
 * The register and post gates, run under PHPUnit.
 *
 * Second entry point to {@see GateChecks}; `tests/run-checks.php` is the other,
 * and the runner refuses to pass unless both invoke every check.
 */
final class GateTest extends TestCase
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
    #[DisplayName('a fresh install gates nothing, and every default is the safe one')]
    public function defaultsAreSafe(): void
    {
        $this->assertNoFailures(
            GateChecks::defaultsAreSafe(),
            'the gate does not hold: a fresh install gates nothing, and every default is the safe one'
        );
    }

    #[Test]
    #[DisplayName('the decide timeout is bounded at both ends')]
    public function theTimeoutIsBoundedAtBothEnds(): void
    {
        $this->assertNoFailures(
            GateChecks::theTimeoutIsBoundedAtBothEnds(),
            'the gate does not hold: the decide timeout is bounded at both ends'
        );
    }

    #[Test]
    #[DisplayName('a credential is opaque and is not tidied up')]
    public function theCredentialIsNotAltered(): void
    {
        $this->assertNoFailures(
            GateChecks::theCredentialIsNotAltered(),
            'the gate does not hold: a credential is opaque and is not tidied up'
        );
    }

    #[Test]
    #[DisplayName('an unconfigured connector is inert, not closed')]
    public function anUnconfiguredConnectorIsInertNotClosed(): void
    {
        $this->assertNoFailures(
            GateChecks::anUnconfiguredConnectorIsInertNotClosed(),
            'the gate does not hold: an unconfigured connector is inert, not closed'
        );
    }

    #[Test]
    #[DisplayName('a configured gate asks core and honours the answer')]
    public function aConfiguredGateAsksCoreAndHonoursIt(): void
    {
        $this->assertNoFailures(
            GateChecks::aConfiguredGateAsksCoreAndHonoursIt(),
            'the gate does not hold: a configured gate asks core and honours the answer'
        );
    }

    #[Test]
    #[DisplayName('a denial always says something a person can act on')]
    public function aDenialIsNeverWordless(): void
    {
        $this->assertNoFailures(
            GateChecks::aDenialIsNeverWordless(),
            'the gate does not hold: a denial always says something a person can act on'
        );
    }

    #[Test]
    #[DisplayName('an outage denies, blames the system, and honours the cache')]
    public function anOutageDeniesAndBlamesTheSystem(): void
    {
        $this->assertNoFailures(
            GateChecks::anOutageDeniesAndBlamesTheSystem(),
            'the gate does not hold: an outage denies, blames the system, and honours the cache'
        );
    }

    #[Test]
    #[DisplayName('a refusal is not softened into a generic denial')]
    public function aRefusalIsNotSoftenedAtTheGate(): void
    {
        $this->assertNoFailures(
            GateChecks::aRefusalIsNotSoftenedAtTheGate(),
            'the gate does not hold: a refusal is not softened into a generic denial'
        );
    }

    #[Test]
    #[DisplayName('each action asks its own gate, and ungated actions ask nothing')]
    public function eachActionAsksItsOwnGate(): void
    {
        $this->assertNoFailures(
            GateChecks::eachActionAsksItsOwnGate(),
            'the gate does not hold: each action asks its own gate, and ungated actions ask nothing'
        );
    }
}
