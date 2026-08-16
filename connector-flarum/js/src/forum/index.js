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

import { extend } from 'flarum/common/extend';
import Application from 'flarum/common/Application';

/*
 * Show the reason a gate gave, instead of a message chosen by status code.
 *
 * This exists because of one branch in Flarum's own error handling:
 *
 *     case 401:
 *     case 403:
 *       content = app.translator.trans('core.lib.error.permission_denied_message');
 *
 * Only 422 renders the `detail` an extension puts in its response. Every other
 * status gets a fixed sentence. So a refusal that travelled correctly through
 * core, the connector, the gate and the API arrived at the person as
 * "You do not have permission to do that."
 *
 * Using 422 to get the detail rendered would have been the cheap fix. It also
 * tells every API client that their payload was unprocessable, which is untrue
 * and unactionable -- the same objection that ruled out 400 for a refusal.
 *
 * So the status stays honest -- 403 refused, 503 unavailable -- and this puts
 * the reason back on the page.
 *
 * The reason is CORE's, not a phrase invented here: core knows which platform
 * kinds are missing and this connector does not. The translations in locale/
 * remain the fallback for when this bundle has not loaded.
 */

const SOULBIND_ERROR_CODES = ['soulbind_gate_refused', 'soulbind_unavailable'];

app.initializers.add('soulbind/flarum-connector', () => {
  extend(Application.prototype, 'requestErrorCatch', function (_returned, error) {
    // `extend` runs AFTER the original, so error.alert is already built and
    // this replaces its content rather than racing to produce it.
    const errors = (error && error.response && error.response.errors) || [];
    const ours = errors.find((e) => e && SOULBIND_ERROR_CODES.includes(e.code));

    if (!ours || !ours.detail || !error.alert) {
      // Not ours, or nothing to say. Flarum's own message stands -- a partial
      // override that blanked the alert would be worse than not overriding.
      return;
    }

    // decodeURI, matching what Flarum does to details it renders itself, so a
    // reason containing an escaped newline reads the same either way.
    error.alert.content = decodeURI(ours.detail);
  });
});
