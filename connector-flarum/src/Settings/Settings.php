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

namespace Soulbind\Flarum\Settings;

/**
 * This connector's settings, read from wherever the host keeps them.
 *
 * An interface for the same reason {@see \Soulbind\Flarum\Client\Transport} is
 * one: the decisions that depend on configuration -- which gates are on, what
 * happens during an outage -- must be testable without standing up a forum and
 * a database.
 *
 * Every getter has a default, and every default is the safe one. A setting
 * nobody has touched must never be the reason a gate is open.
 */
interface Settings
{
    public function get(string $key): ?string;
}
