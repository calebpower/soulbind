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

namespace Soulbind\Flarum\Controller;

use Laminas\Diactoros\Response\JsonResponse;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Server\RequestHandlerInterface;
use Soulbind\Flarum\Client\DecisionCache;
use Soulbind\Flarum\Webhook\WebhookPayload;
use Soulbind\Flarum\Webhook\WebhookVerifier;

/**
 * Receives core's webhooks and keeps the decision cache honest.
 *
 * Thin, like the listeners: verification lives in {@see WebhookVerifier} and
 * payload reading in {@see WebhookPayload}, both covered without a forum. This
 * class translates PSR-7 to those and back, and does nothing else worth a bug.
 */
final class WebhookController implements RequestHandlerInterface
{
    public function __construct(
        private readonly WebhookVerifier $verifier,
        private readonly DecisionCache $cache
    ) {
    }

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        // The RAW body. Not the parsed one: the signature covers the bytes core
        // sent, and re-encoding a decoded array would reorder keys, change
        // spacing and escape differently -- producing a body that means the same
        // thing and signs to something else.
        $body = (string) $request->getBody();

        $headers = [];
        foreach ($request->getHeaders() as $name => $values) {
            $headers[$name] = $values[0] ?? '';
        }

        $verdict = $this->verifier->verify($headers, $body, time());

        if ($verdict->isAccepted()) {
            foreach (WebhookPayload::affectedIdentities($body) as $identityRef) {
                $this->cache->invalidateIdentity($identityRef);
            }
        }

        // The verdict is reported in the body as well as the status, because a
        // replay and an acceptance share a status on purpose and an operator
        // reading a log needs to tell them apart.
        return new JsonResponse(
            ['verdict' => $verdict->value],
            $verdict->httpStatus()
        );
    }
}
