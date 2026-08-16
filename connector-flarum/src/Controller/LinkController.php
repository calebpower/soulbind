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

use Flarum\Http\RequestUtil;
use Laminas\Diactoros\Response\JsonResponse;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Server\RequestHandlerInterface;
use Soulbind\Flarum\Link\LinkResult;
use Soulbind\Flarum\Link\LinkService;

/**
 * The member's own link status, code and redemption.
 *
 * Thin, like the other host-facing classes: every decision lives in
 * {@see LinkService}, which has no forum in it and is checked without one.
 *
 * The identity is taken from the ACTOR, never from the request body. A member
 * asking about their link status may only ask about their own, and a body
 * carrying a user id would let anybody read -- or link -- somebody else's
 * account. That is the same rule the platform kind follows, for the same
 * reason.
 */
final class LinkController implements RequestHandlerInterface
{
    public function __construct(private readonly LinkService $link)
    {
    }

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        $actor = RequestUtil::getActor($request);
        $actor->assertRegistered();

        $platformId = (string) $actor->id;
        $display = (string) $actor->username;

        $body = $request->getParsedBody();
        $body = is_array($body) ? $body : [];
        $action = is_string($body['action'] ?? null) ? $body['action'] : 'status';

        $result = match ($action) {
            'issue' => $this->link->issueCode($platformId, $display),
            'redeem' => $this->link->redeemCode(
                is_string($body['code'] ?? null) ? $body['code'] : '',
                $platformId,
                $display
            ),
            // Anything unrecognised reads as a status request rather than an
            // error: this endpoint is called by one settings panel, and a new
            // client asking for something this build does not have should see
            // where it stands, not a failure.
            default => $this->link->status($platformId),
        };

        return new JsonResponse(
            [
                'ok' => $result->ok,
                'message' => $result->message,
                'data' => $result->data,
            ],
            self::statusFor($result)
        );
    }

    /**
     * 503 for an outage, 403 for a refusal, 200 for an answer.
     *
     * The same split the gate makes, for the same reason: a member told their
     * code was rejected, when the truth is nobody could check it, will fetch a
     * new code and be rejected again.
     */
    private static function statusFor(LinkResult $result): int
    {
        if ($result->ok) {
            return 200;
        }
        return $result->unavailable ? 503 : 403;
    }
}
