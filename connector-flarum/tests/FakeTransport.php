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

use Soulbind\Flarum\Client\Transport;
use Soulbind\Flarum\Client\TransportException;

/**
 * A transport that answers from a script and records what it was given.
 *
 * Records the request so the signature and headers can be asserted, which is
 * the half of the client that a response-only fake would leave untested.
 */
final class FakeTransport implements Transport
{
    public ?string $lastBody = null;
    /** @var array<string, string> */
    public array $lastHeaders = [];
    public int $calls = 0;

    /** @param list<string|TransportException> $responses */
    public function __construct(private array $responses)
    {
    }

    public static function replying(string $response): self
    {
        return new self([$response]);
    }

    public static function failing(string $why = 'connection refused'): self
    {
        return new self([new TransportException($why)]);
    }

    public static function ok(array $payload): self
    {
        return self::replying((string) json_encode(['ok' => true, 'payload' => $payload]));
    }

    public static function refusing(string $code, string $message): self
    {
        return self::replying((string) json_encode([
            'ok' => false,
            'error' => ['code' => $code, 'message' => $message],
        ]));
    }

    public function send(string $body, array $headers): string
    {
        $this->calls++;
        $this->lastBody = $body;
        $this->lastHeaders = $headers;

        $next = array_shift($this->responses);
        if ($next === null) {
            throw new TransportException('the fake transport ran out of scripted responses');
        }
        if ($next instanceof TransportException) {
            throw $next;
        }
        return $next;
    }
}
