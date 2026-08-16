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

namespace Soulbind\Flarum\Provider;

use Flarum\Foundation\AbstractServiceProvider;
use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Contracts\Cache\Repository as CacheRepository;
use Soulbind\Flarum\Client\CurlTransport;
use Soulbind\Flarum\Client\DecisionCache;
use Soulbind\Flarum\Client\SoulbindClient;
use Soulbind\Flarum\Gate\AccessGate;
use Soulbind\Flarum\Link\LinkService;
use Soulbind\Flarum\Settings\ConnectorSettings;
use Soulbind\Flarum\Settings\HostSettings;
use Soulbind\Flarum\Store\FlarumCacheDecisionStore;
use Soulbind\Flarum\Webhook\CacheNonceStore;
use Soulbind\Flarum\Webhook\WebhookVerifier;

/**
 * Assembles the connector from the host's pieces.
 *
 * The only file that knows both halves: which host service supplies what, and
 * how the connector's own objects fit together. Every class it wires is
 * testable without it -- that is the point of the interfaces it satisfies here.
 */
final class SoulbindProvider extends AbstractServiceProvider
{
    /** The platform kind this connector speaks for. */
    public const PLATFORM_KIND = 'forum';

    public function register(): void
    {
        $this->container->singleton(ConnectorSettings::class, static function ($container) {
            return new ConnectorSettings(
                new HostSettings($container->make(SettingsRepositoryInterface::class))
            );
        });

        $this->container->singleton(DecisionCache::class, static function ($container) {
            $settings = $container->make(ConnectorSettings::class);
            return new DecisionCache(
                $settings->failMode(),
                // The host's cache, so decisions survive between requests. PHP
                // has no process to keep them in, and a cache that does not
                // survive is a cache that never helps.
                new FlarumCacheDecisionStore($container->make(CacheRepository::class))
            );
        });

        $this->container->singleton(SoulbindClient::class, static function ($container) {
            $settings = $container->make(ConnectorSettings::class);
            return new SoulbindClient(
                new CurlTransport($settings->coreUrl(), $settings->timeoutMs()),
                $settings->credential(),
                $container->make(DecisionCache::class)
            );
        });

        $this->container->singleton(AccessGate::class, static function ($container) {
            return new AccessGate(
                $container->make(SoulbindClient::class),
                $container->make(ConnectorSettings::class),
                self::PLATFORM_KIND
            );
        });

        $this->container->singleton(LinkService::class, static function ($container) {
            return new LinkService(
                $container->make(SoulbindClient::class),
                self::PLATFORM_KIND
            );
        });

        $this->container->singleton(WebhookVerifier::class, static function ($container) {
            $settings = $container->make(ConnectorSettings::class);
            return new WebhookVerifier(
                $settings->webhookSecret(),
                new CacheNonceStore($container->make(CacheRepository::class))
            );
        });
    }
}
