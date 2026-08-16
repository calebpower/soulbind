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

use Flarum\Settings\SettingsRepositoryInterface;

/**
 * Settings from the host's own store.
 *
 * The host's convention wins: settings live where the forum keeps settings,
 * database-backed and edited through its admin panel, not in a TOML file beside
 * it. Fighting a host platform's configuration system is worse than the
 * inconsistency, and the specification says so.
 */
final class HostSettings implements Settings
{
    public function __construct(private readonly SettingsRepositoryInterface $settings)
    {
    }

    public function get(string $key): ?string
    {
        $value = $this->settings->get($key);
        return is_string($value) ? $value : null;
    }
}
