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

import app from 'flarum/admin/app';

/*
 * The admin settings page.
 *
 * Deliberately the only JavaScript in this extension. Everything that decides
 * anything is in PHP and is checked there; this renders eight fields and
 * validates nothing that matters, because a validation that lives only in a
 * browser is a validation an attacker skips.
 *
 * The client-side hints below exist to catch typos while somebody is looking at
 * the form, not to enforce anything. Every one of them is enforced again in
 * PHP -- the fail mode, the timeout bounds, whether the connector counts as
 * configured -- and PHP is the one that decides.
 */
app.initializers.add('soulbind/flarum-connector', () => {
  const t = (key) => app.translator.trans(`soulbind-flarum.admin.settings.${key}`);

  app.extensionData
    .for('soulbind-flarum-connector')

    .registerSetting({
      setting: 'soulbind.core_url',
      label: t('core_url_label'),
      help: t('core_url_help'),
      type: 'url',
      placeholder: 'https://core.example.com/rpc',
    })

    /*
     * Write-only, both of these.
     *
     * The current value is never sent to this page -- see extend.php, which
     * deliberately does not serialize them. An empty field therefore means
     * "leave it alone", not "the secret is empty", and the placeholder says so
     * rather than leaving somebody to wonder whether they wiped it by saving.
     */
    .registerSetting({
      setting: 'soulbind.credential',
      label: t('credential_label'),
      help: t('credential_help'),
      type: 'password',
      placeholder: '••••••••  (unchanged)',
    })

    .registerSetting({
      setting: 'soulbind.webhook_secret',
      label: t('webhook_secret_label'),
      help: t('webhook_secret_help'),
      type: 'password',
      placeholder: '••••••••  (unchanged)',
    })

    /*
     * A select, not a free-text field.
     *
     * PHP treats anything that is not exactly "open" as closed, which is the
     * right rule for a value that might arrive from anywhere -- but an operator
     * who typed "Open " and got a silently closed gate would have no way to see
     * why. Two options mean the typo cannot be made here at all.
     */
    .registerSetting({
      setting: 'soulbind.fail_mode',
      label: t('fail_mode_label'),
      help: t('fail_mode_help'),
      type: 'select',
      options: {
        closed: t('fail_mode_closed'),
        open: t('fail_mode_open'),
      },
      default: 'closed',
    })

    .registerSetting({
      setting: 'soulbind.register_gate',
      label: t('register_gate_label'),
      help: t('register_gate_help'),
      type: 'text',
      placeholder: '(not gated)',
    })

    .registerSetting({
      setting: 'soulbind.post_gate',
      label: t('post_gate_label'),
      help: t('post_gate_help'),
      type: 'text',
      placeholder: '(not gated)',
    })

    .registerSetting({
      setting: 'soulbind.timeout_ms',
      label: t('timeout_label'),
      help: t('timeout_help'),
      type: 'number',
      min: 100,
      max: 10000,
      placeholder: '2000',
    });
});
