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
 * What came back: an answer, a refusal, or nothing.
 *
 * PHP has no sealed interface, so this is a marker the three outcomes
 * implement. The exhaustiveness the other side gets from the compiler is not
 * available here; what replaces it is that every consumer is a handful of lines
 * long and every one is checked.
 *
 * The distinction this type exists to keep, stated the same way on both sides:
 *
 * A **refusal** is core answering "no". It is final, and it is NEVER softened by
 * a cached answer or a fail mode -- if core says this connector lacks the
 * capability, quietly serving a cached allow is using a stale answer to overrule
 * a current one.
 *
 * An **outage** is core not answering. The connector falls back to its cache,
 * and then to its fail mode.
 *
 * Collapsing the two turns "you may not" into "try again later", and turns a
 * misconfigured credential into an intermittent fault nobody can reproduce.
 */
interface Outcome
{
}
