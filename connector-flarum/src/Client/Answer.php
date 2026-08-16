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

use Soulbind\Flarum\Policy\Decision;

/**
 * A decision, and where it came from.
 *
 * The source is carried rather than discarded because an operator debugging
 * "why was this person denied" needs to know whether core said so, a stale
 * entry said so, or nobody said so and the fail mode answered.
 */
final class Answer
{
    public function __construct(
        public readonly Decision $decision,
        public readonly Source $source
    ) {
    }
}
