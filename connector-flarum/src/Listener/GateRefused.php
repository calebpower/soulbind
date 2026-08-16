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

use RuntimeException;
use Soulbind\Flarum\Gate\GateOutcome;

/**
 * A gate said no.
 *
 * Deliberately NOT a Flarum KnownError, which it used to be.
 *
 * Flarum resolves a KnownError before it consults custom handlers, and the
 * KnownError path produces a response with no details -- so implementing it
 * meant the refusal could never carry its reason, and the person saw a bare
 * status code. A custom handler is the only path that can attach the wording,
 * and Flarum will not reach one for a KnownError.
 *
 * Nothing is lost by dropping it: HandledError::shouldBeReported() is true only
 * for the type `unknown`, so a handler that names its type keeps a refusal out
 * of the error log exactly as KnownError did.
 *
 * @see \Soulbind\Flarum\Listener\GateRefusedHandler
 */
final class GateRefused extends RuntimeException
{
    public function __construct(public readonly GateOutcome $outcome)
    {
        parent::__construct($outcome->message);
    }

    /**
     * The wire type, and the key extend.php registers a status for.
     *
     * A constant because it is written in two places that must agree: here,
     * and in the ErrorHandling extender. When they disagreed -- which is to
     * say, when the extender did not exist at all -- Flarum fell through to a
     * generic 500 and the person was shown "Oops! Something went wrong"
     * instead of the reason.
     */
    public const TYPE = 'soulbind_gate_refused';

    /**
     * The type used when the gate held because core could not be reached.
     *
     * A SEPARATE type, and this is the whole reason there are two.
     *
     * Flarum's frontend renders a message chosen by the error type, not the
     * detail in the response body -- so with one type, a person refused by a
     * policy and a person refused because a server they have never heard of is
     * unreachable are told exactly the same thing. The first is true and the
     * second is a lie, and it is the lie this project has been careful about
     * since the fail-closed message was written.
     *
     * Two types, two translations, and the distinction survives the last hop.
     */
    public const UNAVAILABLE_TYPE = 'soulbind_unavailable';

    public function getType(): string
    {
        return self::TYPE;
    }
}
