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
use Soulbind\Flarum\Listener\GateRefused;
use Soulbind\Flarum\Listener\GateRefusedHandler;
use Soulbind\Flarum\Listener\GateRegistration;
use Soulbind\Flarum\Provider\SoulbindProvider;
use Soulbind\Flarum\Settings\ConnectorSettings;

return [
    (new Extend\ServiceProvider())->register(SoulbindProvider::class),

    (new Extend\Frontend('admin'))->js(__DIR__ . '/js/dist/admin.js'),

    /*
     * Forum JavaScript, for one purpose: putting a gate's reason back on the
     * page. Flarum renders a response `detail` only for status 422, and shows a
     * fixed sentence for every other status -- so without this, a refusal that
     * travelled correctly all the way to the browser arrives as "You do not have
     * permission to do that."
     */
    (new Extend\Frontend('forum'))->js(__DIR__ . '/js/dist/forum.js'),

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

    /*
     * The refusal has to REACH the person.
     *
     * GateRefused implements KnownError, which stops Flarum logging it as a
     * server fault -- but that alone is not enough. Flarum maps a KnownError to
     * an HTTP status through this registry, and an unregistered type falls
     * through to a generic 500, which the frontend renders as
     * "Oops! Something went wrong. Please reload the page and try again."
     *
     * So the gate refused correctly, for the right reason, with core's own
     * wording attached -- and the person was told nothing at all. Every unit
     * check passed, because every one of them asserts the message on the
     * GateOutcome, and the message on the GateOutcome was right. What was
     * missing was the last hop.
     *
     * Found by the browser tier, which is the only thing that looks at what a
     * person actually sees.
     *
     * 403, not 400: the request was well-formed and the answer is that this
     * account may not do this. A 400 would tell an API client to fix its
     * payload, which is not the problem and cannot be the fix.
     */
    (new Extend\ErrorHandling())
        // A HANDLER, not just a status.
        //
        // Flarum resolves KnownError types before custom handlers, and that path
        // builds a response with no details -- so a refusal registered only by
        // status arrives as a bare code and the frontend shows "Oops! Something
        // went wrong." The handler is the only place the reason can be attached.
        ->handler(GateRefused::class, GateRefusedHandler::class),

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
