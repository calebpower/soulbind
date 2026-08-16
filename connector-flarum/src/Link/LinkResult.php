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

namespace Soulbind\Flarum\Link;

/**
 * What a link operation produced, and what to tell the person.
 *
 * Three outcomes, not two, for the same reason the client keeps three: core
 * saying no is not core being unreachable, and a member who is told "try again
 * later" when their code was simply wrong will try again later, forever.
 */
final class LinkResult
{
    /** @param array<string, mixed> $data */
    private function __construct(
        public readonly bool $ok,
        public readonly string $message,
        public readonly array $data,
        public readonly bool $unavailable
    ) {
    }

    /** @param array<string, mixed> $data */
    public static function success(array $data, string $message = ''): self
    {
        return new self(true, $message, $data, false);
    }

    /**
     * Core answered no. The message is core's own, because core knows why.
     */
    public static function refused(string $message): self
    {
        return new self(false, $message, [], false);
    }

    /**
     * Core did not answer.
     *
     * Distinct from a refusal all the way to the caller, so the HTTP status and
     * the wording differ -- 503 and "a problem on our side", not 403 and
     * "that code is not valid". Telling somebody their code was wrong when the
     * truth is we could not check it sends them to get a new one, which will
     * also fail.
     */
    public static function unavailable(string $message): self
    {
        return new self(false, $message, [], true);
    }
}
