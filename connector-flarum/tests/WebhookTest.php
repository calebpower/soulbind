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
 * The inbound webhook, run under PHPUnit.
 *
 * Second entry point to {@see WebhookChecks}; `tests/run-checks.php` is the
 * other, and the runner refuses to pass unless both invoke every check.
 */
final class WebhookTest extends TestCase
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
    #[DisplayName('a correctly signed, fresh delivery is accepted')]
    public function aProperlySignedDeliveryIsAccepted(): void
    {
        $this->assertNoFailures(
            WebhookChecks::aProperlySignedDeliveryIsAccepted(),
            'the webhook endpoint does not hold: a correctly signed, fresh delivery is accepted'
        );
    }

    #[Test]
    #[DisplayName('a delivery missing any signing header is malformed')]
    public function missingHeadersAreMalformed(): void
    {
        $this->assertNoFailures(
            WebhookChecks::missingHeadersAreMalformed(),
            'the webhook endpoint does not hold: a delivery missing any signing header is malformed'
        );
    }

    #[Test]
    #[DisplayName('a timestamp header is an integer or it is nothing')]
    public function nonIntegerTimestampsAreMalformed(): void
    {
        $this->assertNoFailures(
            WebhookChecks::nonIntegerTimestampsAreMalformed(),
            'the webhook endpoint does not hold: a timestamp header is an integer or it is nothing'
        );
    }

    #[Test]
    #[DisplayName('the freshness window is closed at both ends')]
    public function staleAndFutureTimestampsAreRefused(): void
    {
        $this->assertNoFailures(
            WebhookChecks::staleAndFutureTimestampsAreRefused(),
            'the webhook endpoint does not hold: the freshness window is closed at both ends'
        );
    }

    #[Test]
    #[DisplayName('a replayed delivery is refused')]
    public function aReplayedNonceIsRefused(): void
    {
        $this->assertNoFailures(
            WebhookChecks::aReplayedNonceIsRefused(),
            'the webhook endpoint does not hold: a replayed delivery is refused'
        );
    }

    #[Test]
    #[DisplayName('a tampered body or signature is refused')]
    public function aTamperedBodyOrSignatureIsRefused(): void
    {
        $this->assertNoFailures(
            WebhookChecks::aTamperedBodyOrSignatureIsRefused(),
            'the webhook endpoint does not hold: a tampered body or signature is refused'
        );
    }

    #[Test]
    #[DisplayName('a hostile header is a refusal, never a crash')]
    public function hostileInputDoesNotThrow(): void
    {
        $this->assertNoFailures(
            WebhookChecks::hostileInputDoesNotThrow(),
            'the webhook endpoint does not hold: a hostile header is a refusal, never a crash'
        );
    }

    #[Test]
    #[DisplayName('an endpoint with no secret accepts nothing')]
    public function anUnconfiguredEndpointAcceptsNothing(): void
    {
        $this->assertNoFailures(
            WebhookChecks::anUnconfiguredEndpointAcceptsNothing(),
            'the webhook endpoint does not hold: an endpoint with no secret accepts nothing'
        );
    }

    #[Test]
    #[DisplayName('header lookup is case-insensitive')]
    public function headerLookupIsCaseInsensitive(): void
    {
        $this->assertNoFailures(
            WebhookChecks::headerLookupIsCaseInsensitive(),
            'the webhook endpoint does not hold: header lookup is case-insensitive'
        );
    }

    #[Test]
    #[DisplayName('nothing this endpoint refuses is a 5xx')]
    public function refusalsAreNeverServerErrors(): void
    {
        $this->assertNoFailures(
            WebhookChecks::refusalsAreNeverServerErrors(),
            'the webhook endpoint does not hold: nothing this endpoint refuses is a 5xx'
        );
    }

    #[Test]
    #[DisplayName('the nonce store is bounded and fails closed')]
    public function theNonceStoreFailsClosedWhenFull(): void
    {
        $this->assertNoFailures(
            WebhookChecks::theNonceStoreFailsClosedWhenFull(),
            'the webhook endpoint does not hold: the nonce store is bounded and fails closed'
        );
    }
    #[Test]
    #[DisplayName('secrets are compared with hash_equals, never == or ===')]
    public function secretsAreComparedInConstantTime(): void
    {
        $this->assertNoFailures(
            WebhookChecks::secretsAreComparedInConstantTime(),
            'a secret comparison short-circuits, leaking how much of a guess was right'
        );
    }

    #[Test]
    #[DisplayName('reading the identities in a delivery is total and bounded')]
    public function payloadReadingIsTotal(): void
    {
        $this->assertNoFailures(
            WebhookChecks::payloadReadingIsTotal(),
            'a webhook payload threw, invented an identity, or was unbounded'
        );
    }
}
