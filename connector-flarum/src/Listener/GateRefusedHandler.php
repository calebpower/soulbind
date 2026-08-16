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

namespace Soulbind\Flarum\Listener;

use Flarum\Foundation\ErrorHandling\HandledError;
use Soulbind\Flarum\Client\Source;

/**
 * Turns a gate refusal into a response the forum can render truthfully.
 *
 * Two types, not one, because Flarum's frontend picks its message from the
 * error TYPE and ignores the detail in the body. With a single type, somebody
 * refused by a policy and somebody refused because core is unreachable are told
 * the same thing -- and for the second that is a lie.
 *
 * This project has been careful about that sentence since the fail-closed
 * message was written: a person refused because a server they have never heard
 * of is unreachable must not be told they are not allowed. That care is worth
 * nothing if it is discarded at the last hop.
 *
 * The detail still carries core's own wording, unchanged, for anything reading
 * the API rather than the page.
 */
final class GateRefusedHandler
{
    public function handle(GateRefused $e): HandledError
    {
        $unavailable = $e->outcome->source === Source::FAIL_MODE;

        return (new HandledError(
            $e,
            $unavailable ? GateRefused::UNAVAILABLE_TYPE : GateRefused::TYPE,
            // 503 for an outage: the request was fine and the service could not
            // answer. 403 for a refusal: the request was fine and the answer is
            // no. Neither is a 400 -- there is nothing for a client to fix, and
            // saying otherwise sends somebody to edit a payload that is correct.
            $unavailable ? 503 : 403
        ))->withDetails([['detail' => $e->getMessage()]]);
    }
}
