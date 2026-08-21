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

'use strict';

/**
 * Fails any test that observes a 5xx.
 *
 * §14 Phase 8 asks the `browser` tier to run "the T5 suite against the real
 * stack (no injection here -- the 5xx watchdog is on)", and §11 Tier 10 says
 * why the two travel together: "Watchdog fails any test observing a 5xx --
 * which is why fault injection lives on Tier 5 and never here."
 *
 * The distinction is the whole point. A 500 during `@outage` is the fixture
 * doing its job. A 500 during `@refused` is the product failing, and without a
 * watchdog it is invisible: the spec asserts the page says the right thing, and
 * a page can say the right thing while the request behind it five-hundreds and
 * the UI falls back to a cached or default state.
 *
 * ENABLED BY THE CALLER, per pass. `SOULBIND_5XX_WATCHDOG=1` turns it on, and
 * the injection passes deliberately leave it off -- a watchdog that had to be
 * clever about which 5xx were expected would be a second implementation of the
 * fault injector, and would disagree with it.
 */
function armWatchdog(test) {
  const armed = process.env.SOULBIND_5XX_WATCHDOG === '1';

  test.beforeEach(async ({ page }, testInfo) => {
    if (!armed) {
      return;
    }
    const seen = [];
    page.on('response', (response) => {
      if (response.status() >= 500) {
        seen.push(`${response.status()} ${response.request().method()} ${response.url()}`);
      }
    });
    // Attached to the test rather than asserted at the end of it, so a spec
    // that fails for its own reasons still reports the 5xx it saw -- the server
    // error is usually the cause and the assertion failure the symptom.
    testInfo.serverErrors = seen;
  });

  test.afterEach(async ({}, testInfo) => {
    if (!armed) {
      return;
    }
    const seen = testInfo.serverErrors || [];
    if (seen.length > 0) {
      throw new Error(
        `the watchdog saw ${seen.length} server error(s) during this test, with no fault ` +
        `injection running:\n  ${seen.join('\n  ')}\n` +
        'A page can render the right words while the request behind it five-hundreds, ' +
        'which is exactly what this catches.');
    }
  });
}

module.exports = { armWatchdog };
