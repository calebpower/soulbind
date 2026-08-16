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

/**
 * Turns a gate refusal into a response that carries its reason.
 *
 * This exists because Flarum's KnownError path cannot. `Registry::handle()`
 * tries known types first and only then custom handlers, and the known-type
 * branch builds a `HandledError` with no details -- so an exception that
 * implements `KnownError` can never explain itself. The person got a bare
 * status code, which the frontend rendered as "Oops! Something went wrong."
 *
 * 403, not 400: the request was well-formed, and the answer is that this account
 * may not do this. A 400 tells an API client to fix its payload, which is
 * neither the problem nor something it can act on.
 *
 * The detail is core's own wording, carried through unchanged. Core knows what
 * is missing; this connector does not, and inventing a friendlier sentence here
 * would be a second answer competing with the real one.
 */
final class GateRefusedHandler
{
    public function handle(GateRefused $e): HandledError
    {
        return (new HandledError($e, GateRefused::TYPE, 403))
            ->withDetails([['detail' => $e->getMessage()]]);
    }
}
