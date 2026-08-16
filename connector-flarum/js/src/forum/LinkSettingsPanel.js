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

import Component from 'flarum/common/Component';
import Button from 'flarum/common/components/Button';
import LoadingIndicator from 'flarum/common/components/LoadingIndicator';

/*
 * What a member sees and does about linking.
 *
 * Three states, and the third is the one that is usually skipped: linked,
 * not linked, and "we could not find out". A panel that renders "not linked"
 * when core is unreachable tells somebody to go and link an account they may
 * already have linked -- so unavailability is its own state, worded as the
 * system's fault, exactly as the gate words it.
 */
export default class LinkSettingsPanel extends Component {
  oninit(vnode) {
    super.oninit(vnode);
    this.loading = true;
    this.unavailable = false;
    this.linked = false;
    this.identities = [];
    this.code = null;
    this.entered = '';
    this.message = null;
    this.messageIsError = false;
    this.refresh();
  }

  /** One request shape for all three actions; the server decides. */
  request(body) {
    return app
      .request({
        method: 'POST',
        url: `${app.forum.attribute('apiUrl').replace(/\/api$/, '')}/soulbind/link`,
        body,
        // Handled here rather than by Flarum's global alert: an outage in this
        // panel is a state the panel renders, not a page-level error banner.
        errorHandler: (error) => {
          this.loading = false;
          this.unavailable = error.status === 503;
          this.messageIsError = true;
          this.message = this.detailOf(error);
          m.redraw();
        },
      })
      .then((response) => {
        this.loading = false;
        return response;
      });
  }

  detailOf(error) {
    const errors = (error && error.response && error.response.errors) || [];
    const detail = errors.map((e) => e && e.detail).find(Boolean);
    return detail || app.translator.trans('soulbind-connector.forum.link.unavailable');
  }

  refresh() {
    this.loading = true;
    this.request({ action: 'status' }).then((response) => {
      if (!response) return;
      this.unavailable = false;
      this.linked = !!(response.data && response.data.linked);
      this.identities = (response.data && response.data.identities) || [];
      // The server counts OTHERS: identity.describe includes this account, and a
      // browser subtracting one would be assuming its own identity is always
      // in the list.
      this.otherCount = (response.data && response.data.otherCount) || 0;
      m.redraw();
    });
  }

  issue() {
    this.message = null;
    this.request({ action: 'issue' }).then((response) => {
      if (!response) return;
      this.code = response.data && response.data.code;
      m.redraw();
    });
  }

  redeem() {
    this.message = null;
    this.request({ action: 'redeem', code: this.entered }).then((response) => {
      if (!response) return;
      this.messageIsError = !response.ok;
      this.message = response.message;
      if (response.ok) {
        this.entered = '';
        this.refresh();
      }
      m.redraw();
    });
  }

  view() {
    if (this.loading) {
      return m('.SoulbindPanel', [m(LoadingIndicator)]);
    }

    return m('.SoulbindPanel', [
      m('h3', app.translator.trans('soulbind-connector.forum.link.title')),

      // The unavailable state is FIRST and exclusive. Rendering the link
      // controls underneath a "we cannot check" notice invites somebody to
      // redeem a code that cannot be checked either.
      this.unavailable
        ? m('.SoulbindPanel-unavailable.Alert.Alert--error', [
            app.translator.trans('soulbind-connector.forum.link.unavailable'),
          ])
        : [
            m(
              '.SoulbindPanel-status',
              this.linked
                ? app.translator.trans('soulbind-connector.forum.link.linked', {
                    count: this.otherCount,
                  })
                : app.translator.trans('soulbind-connector.forum.link.not_linked')
            ),

            m('.SoulbindPanel-issue', [
              this.code
                ? m('code.SoulbindPanel-code', this.code)
                : Button.component(
                    { className: 'Button', onclick: () => this.issue() },
                    app.translator.trans('soulbind-connector.forum.link.get_code')
                  ),
            ]),

            m('.SoulbindPanel-redeem', [
              m('input.FormControl', {
                name: 'soulbind-code',
                placeholder: app.translator.trans('soulbind-connector.forum.link.code_placeholder'),
                value: this.entered,
                oninput: (e) => {
                  this.entered = e.target.value;
                },
              }),
              Button.component(
                { className: 'Button Button--primary', onclick: () => this.redeem() },
                app.translator.trans('soulbind-connector.forum.link.redeem')
              ),
            ]),

            this.message
              ? m(
                  '.SoulbindPanel-message.Alert',
                  { className: this.messageIsError ? 'Alert--error' : 'Alert--success' },
                  this.message
                )
              : null,
          ],
    ]);
  }
}
