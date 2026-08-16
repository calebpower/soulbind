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
 * The golden vectors, run under PHPUnit.
 *
 * <p>The assertions themselves live in {@see VectorChecks} and are shared with
 * `tests/run-vectors.php`, which runs them with no dependencies at all. This
 * class is one of two ENTRY POINTS to one implementation, deliberately: two
 * copies of the assertions would drift, and the copy run less often would drift
 * further while still looking like coverage.
 *
 * The dependency-free runner exists because PHPUnit needs `ext-xmlwriter`, and
 * the cross-language oracle is the one thing that must be checkable on any
 * machine with PHP. An oracle nobody can run is not an oracle.
 *
 * Per-check granularity rather than per-row: every failure message names the
 * corpus line, so a failing row is still identified exactly.
 */
final class GoldenVectorTest extends TestCase
{
    /** @param list<string> $failures */
    private function assertNoFailures(array $failures, string $what): void
    {
        $this->assertSame(
            [],
            $failures,
            $what . ' -- ' . count($failures) . " disagreement(s):\n  "
                . implode("\n  ", $failures)
        );
    }

    #[Test]
    #[DisplayName('every normalisation vector holds')]
    public function everyNormalisationVectorHolds(): void
    {
        $this->assertNoFailures(
            VectorChecks::normalisation(),
            'the normalisation corpus disagrees with this implementation, which means it '
                . 'disagrees with the other one'
        );
    }

    #[Test]
    #[DisplayName('every signing vector holds, and verify agrees with sign')]
    public function everySigningVectorHolds(): void
    {
        $this->assertNoFailures(
            VectorChecks::signing(),
            'the signing corpus disagrees with this implementation'
        );
    }

    #[Test]
    #[DisplayName('normalisation is idempotent')]
    public function normalisationIsIdempotent(): void
    {
        $this->assertNoFailures(
            VectorChecks::idempotence(),
            'a normalised code does not survive a second normalisation'
        );
    }

    #[Test]
    #[DisplayName('the corpus is large enough and balanced enough to mean something')]
    public function theCorpusIsBalanced(): void
    {
        $this->assertNoFailures(
            VectorChecks::corpusShape(),
            'the corpus would pass with whole behaviours deleted'
        );
    }

    #[Test]
    #[DisplayName('case folding cannot synthesise a code -- every code point')]
    public function foldingCannotSynthesiseACode(): void
    {
        $this->assertNoFailures(
            VectorChecks::foldingCannotSynthesise(),
            'case folding turned a character outside the alphabet into a code somebody else '
                . 'can redeem'
        );
    }

    #[Test]
    #[DisplayName('the signer refuses what the other implementation refuses')]
    public function theSignerArgumentContractHolds(): void
    {
        $this->assertNoFailures(
            VectorChecks::signerArgumentValidation(),
            'the signer disagrees with the other implementation about which arguments are '
                . 'legal, so one side will produce signatures the other refuses to verify'
        );
    }

    #[Test]
    #[DisplayName('the hostile run is actually hostile')]
    public function theHostileRunIsActuallyHostile(): void
    {
        $this->assertNoFailures(
            VectorChecks::hostilityTookEffect(),
            'the hostile configuration did not take effect, so the hostile run proves nothing '
                . 'the ordinary run does not'
        );
    }
}
