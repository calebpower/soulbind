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

namespace Soulbind\Flarum\Client;

/**
 * The one place this connector touches a socket.
 *
 * An interface so the client's logic -- signing, the outage/refusal
 * distinction, cache population, the fail-mode fallback -- is testable without
 * a network. None of that needs a socket, and a test that needs one is a test
 * that does not get run.
 */
interface Transport
{
    /**
     * Sends a signed request body and returns the response body.
     *
     * @param array<string, string> $headers
     * @throws TransportException when the request did not complete. Anything
     *     that is not a completed HTTP exchange is an outage -- never a
     *     refusal, because core did not say no; nothing reached core.
     */
    public function send(string $body, array $headers): string;
}
