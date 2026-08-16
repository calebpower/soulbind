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

use Flarum\Extend;
use Flarum\Post\Event\Saving as PostSaving;
use Flarum\User\Event\Saving as UserSaving;
use Soulbind\Flarum\Controller\WebhookController;
use Soulbind\Flarum\Listener\GatePosting;
use Soulbind\Flarum\Listener\GateRegistration;
use Soulbind\Flarum\Provider\SoulbindProvider;
use Soulbind\Flarum\Settings\ConnectorSettings;

return [
    (new Extend\ServiceProvider())->register(SoulbindProvider::class),

    (new Extend\Frontend('admin'))->js(__DIR__ . '/js/dist/admin.js'),

    (new Extend\Locales(__DIR__ . '/locale')),

    /*
     * The webhook endpoint.
     *
     * Outside the API namespace and unauthenticated by design: core presents a
     * signature, not a session, and it has no forum account to authenticate as.
     * Everything that makes this safe is in WebhookVerifier, which is why that
     * class is the most heavily checked file in the extension.
     */
    (new Extend\Routes('forum'))
        ->post('/soulbind/webhook', 'soulbind.webhook', WebhookController::class),

    (new Extend\Event())
        ->listen(UserSaving::class, GateRegistration::class)
        ->listen(PostSaving::class, GatePosting::class),

    /*
     * Settings the admin page reads.
     *
     * The credential and the webhook secret are deliberately ABSENT: anything
     * serialized here is sent to every admin's browser and sits in the page
     * source. The admin page writes them and never reads them back, which is
     * the same discipline as showing a minted credential once.
     */
    (new Extend\Settings())
        ->serializeToForum('soulbind.registerGate', ConnectorSettings::REGISTER_GATE)
        ->serializeToForum('soulbind.postGate', ConnectorSettings::POST_GATE)
        ->serializeToForum('soulbind.configured', ConnectorSettings::CORE_URL, function ($value) {
            // A boolean, not the URL. The admin page needs to know whether the
            // connector is configured; it does not need to publish where core
            // lives to every logged-in member.
            return is_string($value) && trim($value) !== '';
        }),
];
